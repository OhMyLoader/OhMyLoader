package org.ohmyloader.core.hook

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.ohmyloader.api.OmlLog
import org.ohmyloader.api.hook.HookContext
import org.ohmyloader.api.hook.HookValue
import org.ohmyloader.core.transformer.injection.Boxing
import java.lang.invoke.CallSite
import java.lang.invoke.ConstantCallSite
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Mixin handler registry and runtime bridge. During the scan phase each `@Inject` handler is assigned an int
 * id written into the injection bytecode; at the injection point the game calls a static method on the **bridge
 * class**, which invokes the handler.
 *
 * Each bridge method is one `invokedynamic` whose bootstrap runs at **first execution** — the point where the
 * handler class is safe to load. The bootstrap binds a `MethodHandle` to the handler (direct static call, or a
 * bound instance for non-static handlers) and returns a `ConstantCallSite`, so the steady-state path has no
 * reflection, no boxing and no captures array. Kinds whose result must be read back from a per-call handle
 * ([BridgeKind.CANCELABLE] / [BridgeKind.RETURNABLE]), or handlers whose class cannot be reached directly,
 * fall back to the reflective entry points below with identical semantics.
 *
 * The bridge class itself is generated once at startup, one method per handler (cancelable return `Z`, else
 * `V`), because the capture descriptor is only known after scanning.
 */
object HookRegistry {

    internal const val BRIDGE_CLASS = "org/ohmyloader/core/hook/GeneratedHookBridge"

    /**
     * The bridge kind — decides the generated bridge method name, descriptor, and which entry point is called.
     *
     * - [NOTIFY]: announce only, `(captures…, I)V`
     * - [CANCELABLE]: short-circuit a void target, `(captures…, I)Z`
     * - [RETURNABLE]: short-circuit a non-void target with a value, `(captures…, I, HookValue)V`
     * - [MODIFY]: use the handler's return value as the result, `(captures…, I)<R>` (`R` = modified argument/constant type)
     * - [MIRROR]: the whole call is replaced by this static method, `(captures…)<R>` — no id parameter (id is
     *   encoded in the method name) and the descriptor **must equal the replaced call's descriptor** (`@Redirect`;
     *   on an instance call the receiver is the first capture)
     */
    enum class BridgeKind { NOTIFY, CANCELABLE, RETURNABLE, MODIFY, MIRROR }

    /**
     * @param captureTypes JVM descriptors of the captured parameters, order == handler parameter order == push
     *   order. Slot numbers are not here — they belong to the injection point (resolved/validated there by
     *   data-flow analysis); the registry only needs the bridge method's parameter shape.
     * @param handlerParamCount the handler's parameter count (including implicit captures; picks the exact overload)
     * @param returnsValue whether the handler takes a `HookValue` (non-void target short-circuiting
     *   with a value)
     * @param returnType the handler's (and the bridge method's) return-type descriptor; `MODIFY`/`MIRROR` pass
     *   through the result with it
     */
    data class Handler(
        val id: Int,
        val modId: String,
        val mixinClass: String,
        val handlerMethod: String,
        val targetMethod: String,
        val cancelable: Boolean,
        val captureTypes: List<String> = emptyList(),
        val handlerParamCount: Int = 1,
        val returnsValue: Boolean = false,
        val returnType: String = "V",
        val mirror: Boolean = false,
        /**
         * The bridge kind the front end built its `INVOKESTATIC` from. A value-handler may return `void`
         * (`@ModifyArgs`) while still needing the id-dropping `MODIFY` shape, which the return type alone
         * cannot express.
         */
        val kind: BridgeKind? = null,
    )

    private val nextId = AtomicInteger()

    /**
     * Written once per handler at scan time, read from the game thread at every dispatch —
     * a concurrent map keeps the read path lock-free. Iteration ([ensureBridgeGenerated]) and
     * clearing ([resetForTests]) stay under the object monitor.
     */
    private val handlers = ConcurrentHashMap<Int, Handler>()
    private val mixinInstances = ConcurrentHashMap<String, Any>()

    /**
     * Handler methods resolved per handler id. Resolution is deterministic (the mixin instance — and with it
     * the handler class — is fixed at registration), so it happens once per handler; the dispatch path must not
     * pay a `getMethods` clone per invocation. Cleared in [resetForTests] (ids restart from 1).
     */
    private val resolvedMethods = ConcurrentHashMap<Int, Method>()

    /** The resolved handler call: a static method's receiver is null (invoked with null). */
    private class ResolvedCall(val receiver: Any?, val method: Method)

    private val resolvedCalls = ConcurrentHashMap<Int, ResolvedCall>()
    private var loader: ClassLoader? = null
    private var bridgeLoaded = false

    /** The `invokedynamic` bootstrap every bridge method links through (id and kind travel as bsm arguments). */
    private val BOOTSTRAP = Handle(
        Opcodes.H_INVOKESTATIC,
        REGISTRY,
        "bootstrap",
        "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;II)" +
            "Ljava/lang/invoke/CallSite;",
        false,
    )

    /** Recovery for a failing NOTIFY handler: the same isolation as [dispatch], without a per-call wrapper. */
    private val REPORT_NOTIFY_FAILURE: MethodHandle by lazy {
        MethodHandles.lookup().findStatic(
            HookRegistry::class.java,
            "reportNotifyFailure",
            MethodType.methodType(Void.TYPE, Integer.TYPE, Throwable::class.java),
        )
    }

    private val fallbackCounter = AtomicInteger()

    /** The injection bytecode needs to load mod classes at dispatch time; set by OMLCore during assembly. */
    fun installClassLoader(loader: ClassLoader) {
        this.loader = loader
    }

    fun register(
        modId: String,
        mixinClass: String,
        handlerMethod: String,
        targetMethod: String,
        cancelable: Boolean,
        captureTypes: List<String> = emptyList(),
        handlerParamCount: Int = captureTypes.size + 1,
        returnsValue: Boolean = false,
        returnType: String = "V",
        mirror: Boolean = false,
        kind: BridgeKind? = null,
    ): Int {
        val id = nextId.incrementAndGet()
        handlers[id] = Handler(
            id, modId, mixinClass, handlerMethod, targetMethod, cancelable,
            captureTypes, handlerParamCount, returnsValue, returnType, mirror, kind,
        )
        return id
    }

    /** Look up a registered handler (for the front end to build the bridge-call descriptor). */
    fun handler(id: Int): Handler? = handlers[id]

    /** Which kind of bridge this handler uses: the front end's explicit kind, else derived from the flags. */
    private fun kindOf(handler: Handler): BridgeKind = handler.kind ?: when {
        handler.returnsValue -> BridgeKind.RETURNABLE
        handler.cancelable -> BridgeKind.CANCELABLE
        handler.mirror -> BridgeKind.MIRROR
        handler.returnType != "V" -> BridgeKind.MODIFY
        else -> BridgeKind.NOTIFY
    }

    /** Bridge method name: `inject$<id>` / `injectCancelable$<id>` / … ([BridgeKind.MIRROR] uses `redirect$<id>`). */
    fun bridgeMethodName(id: Int, cancelable: Boolean): String =
        bridgeMethodName(id, if (cancelable) BridgeKind.CANCELABLE else BridgeKind.NOTIFY)

    /** Bridge method name by kind ([BridgeKind.RETURNABLE] etc. can only use this overload). */
    fun bridgeMethodName(id: Int, kind: BridgeKind): String = when (kind) {
        BridgeKind.NOTIFY -> "inject$$id"
        BridgeKind.CANCELABLE -> "injectCancelable$$id"
        BridgeKind.RETURNABLE -> "injectReturn$$id"
        BridgeKind.MODIFY -> "injectValue$$id"
        BridgeKind.MIRROR -> "redirect$$id"
    }

    /**
     * The bridge method's JVM descriptor. Convention: `(captures…, id[, callback handle])` -- the captured
     * arguments come first with id right after, and [BridgeKind.RETURNABLE] appends a `HookValue`
     * at the end (created by the engine); [BridgeKind.MIRROR] is the only kind **without an id parameter** (its
     * descriptor must exactly equal the replaced call, so id can only be encoded in the method name).
     *
     * This is the **only** interface convention between the injection bytecode and the reflective call; both the
     * front end's `INVOKESTATIC` construction and the generator must agree with it here.
     */
    fun bridgeDesc(id: Int, cancelable: Boolean): String =
        bridgeDesc(id, if (cancelable) BridgeKind.CANCELABLE else BridgeKind.NOTIFY)

    /** Bridge descriptor by kind. */
    fun bridgeDesc(id: Int, kind: BridgeKind): String {
        val handler = handlers[id]
        val types = handler?.captureTypes.orEmpty().joinToString("")
        val ret = handler?.returnType ?: "V"
        return when (kind) {
            BridgeKind.NOTIFY -> "(${types}I)V"
            BridgeKind.CANCELABLE -> "(${types}I)Z"
            BridgeKind.RETURNABLE -> "(${types}I$CALLBACK_RETURNABLE_DESC)V"
            BridgeKind.MODIFY -> "(${types}I)$ret"
            BridgeKind.MIRROR -> "($types)$ret"
        }
    }

    /** A handler's bridge kind (the front end builds the `INVOKESTATIC` from this). */
    fun bridgeKind(id: Int): BridgeKind? = handlers[id]?.let { kindOf(it) }

    /**
     * Generate the bridge class (idempotent). **Must be called before any injection bytecode runs** — called
     * once by [org.ohmyloader.core.HookScanner.createTransformer] after scanning **all** mods and registering
     * **all** handlers. A handler registered after this call has no bridge method, so generation can only
     * happen at the point where everything is registered. The result is a class of static methods, each one
     * a single `invokedynamic` linked lazily through [bootstrap]; it is generated rather than hard-coded
     * because each handler's capture descriptor is unknown at compile time (see the class doc). In tests,
     * [resetForTests] returns the not-yet-generated state.
     */
    @Synchronized
    fun ensureBridgeGenerated() {
        if (bridgeLoaded) return
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        cw.visit(
            Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SYNTHETIC,
            BRIDGE_CLASS, null, "java/lang/Object", null,
        )
        cw.visitSource("OMLMixinBridge", null)

        // Private constructor: a purely static utility class should not be instantiated
        cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }

        for (handler in handlers.values) {
            emitBridgeMethod(cw, handler)
        }

        cw.visitEnd()
        val bytes = cw.toByteArray()
        val cl = loader ?: ClassLoader.getSystemClassLoader()
        loadIntoLoader(cl, bytes)
        bridgeLoaded = true
    }

    /**
     * Clear the registry and bridge state back to "not yet generated".
     *
     * Test-only: in production the generation point is "the moment everything is registered", while unit tests
     * register case by case; without resetting, the first test would pin the bridge shape forever.
     */
    internal fun resetForTests() {
        synchronized(this) {
            nextId.set(0)
            handlers.clear()
            resolvedMethods.clear()
            resolvedCalls.clear()
            mixinInstances.clear()
            bridgeClass = null
            bridgeLoaded = false
        }
    }

    /**
     * Define the generated bytecode into the loader. Prefer
     * [org.ohmyloader.core.classloader.OMLClassLoader.defineGeneratedClass] — the public channel the
     * loader itself opens. **Do not** pry open `ClassLoader.defineClass` reflectively: since Java 26
     * `java.lang` is no longer open to unnamed modules, and `setAccessible` throws
     * `InaccessibleObjectException` outright. In test environments the loader is a plain `ClassLoader`
     * (without that channel), in which case a one-off child loader defines the class and the result is
     * recorded in [bridgeClass] — the bridge methods are only used by [bridgeClass]'s callers and need not
     * be reachable via `Class.forName`.
     */
    private fun loadIntoLoader(loader: ClassLoader, bytes: ByteArray) {
        val bridgeName = BRIDGE_CLASS.replace('/', '.')
        val define = loader.javaClass.methods.firstOrNull {
            it.name == "defineGeneratedClass" && it.parameterCount == 2
        }
        val defined: Class<*> = if (define != null) {
            define.invoke(loader, BRIDGE_CLASS, bytes) as Class<*>
        } else {
            object : ClassLoader(loader) {
                fun define(): Class<*> = defineClass(bridgeName, bytes, 0, bytes.size)
            }.define()
        }
        bridgeClass = defined
        // Trigger linking and verification: any generation error (if any) should blow up at startup, not when the
        // first injected instruction executes
        defined.declaredMethods.size
    }

    /** The generated bridge class; available after [ensureBridgeGenerated]. */
    @Volatile
    var bridgeClass: Class<*>? = null
        private set

    /**
     * The bootstrap every bridge method's `invokedynamic` links through. It runs **once per handler**, at the
     * first execution of that bridge (the point where loading the handler class is safe), and returns a
     * constant call site so the steady state is a plain handle invocation.
     */
    @JvmStatic
    fun bootstrap(lookup: MethodHandles.Lookup, name: String, type: MethodType, id: Int, kindOrdinal: Int): CallSite {
        val handler = handlers[id] ?: throw IllegalStateException("unknown hook handler id $id ($name)")
        val kind = BridgeKind.entries[kindOrdinal]
        val target = try {
            when (kind) {
                BridgeKind.CANCELABLE, BridgeKind.RETURNABLE -> directHandleReadback(handler, kind, type)
                else -> directCallSite(handler, kind, type)
            }
        } catch (_: Throwable) {
            // Private/odd handler shapes or an unresolvable class path: keep the reflective semantics
            reflectiveCallSite(handler, kind, type)
        }
        return ConstantCallSite(target)
    }

    /** Bind the handler (static, or an instance bound to its receiver) with private access where allowed. */
    private fun handlerHandle(handler: Handler): MethodHandle {
        val resolved = resolvedCall(handler)
        val method = resolved.method
        val owner = method.declaringClass
        val privateLookup = MethodHandles.privateLookupIn(owner, MethodHandles.lookup())
        val methodType = methodTypeOf(method)
        return if (Modifier.isStatic(method.modifiers)) {
            privateLookup.findStatic(owner, method.name, methodType)
        } else {
            privateLookup.findVirtual(owner, method.name, methodType).bindTo(resolved.receiver)
        }
    }

    /** Handler exceptions are reported and swallowed: the same isolation the reflective entry points provide. */
    private fun isolate(handle: MethodHandle, handler: Handler): MethodHandle {
        val report = MethodHandles.insertArguments(REPORT_NOTIFY_FAILURE, 0, handler.id)
        val recovery = MethodHandles.dropArguments(report, 1, handle.type().parameterList())
        return MethodHandles.catchException(handle, Throwable::class.java, recovery)
    }

    /** Direct `MethodHandle` binding for the kinds whose result needs no per-call read-back. */
    private fun directCallSite(handler: Handler, kind: BridgeKind, bridgeType: MethodType): MethodHandle {
        var handle = handlerHandle(handler)
        return when (kind) {
            BridgeKind.NOTIFY -> {
                // Not cancelable: one immutable context per handler is enough, and `cancel()` throws anyway
                val context = HookContext(handler.targetMethod, false)
                handle = MethodHandles.insertArguments(handle, handle.type().parameterCount() - 1, context)
                handle = isolate(handle, handler)
                MethodHandles.dropArguments(handle, handle.type().parameterCount(), Integer.TYPE).asType(bridgeType)
            }

            BridgeKind.MODIFY -> MethodHandles.dropArguments(
                handle, handle.type().parameterCount(), Integer.TYPE,
            ).asType(bridgeType)

            BridgeKind.MIRROR -> handle.asType(bridgeType)

            else -> throw UnsupportedOperationException("no plain handle for $kind")
        }
    }

    /**
     * CANCELABLE / RETURNABLE read a per-call context (or the engine's handle) **after** the handler returns, which
     * a plain handle chain cannot express. The stub below is generated with the exact bridge descriptor: it creates
     * the context, invokes the pre-bound (and exception-isolated) handler handle natively, then reads the result.
     */
    private fun directHandleReadback(handler: Handler, kind: BridgeKind, bridgeType: MethodType): MethodHandle {
        val isolated = isolate(handlerHandle(handler), handler)
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        val internal = "$REGISTRY\$Direct${fallbackCounter.incrementAndGet()}"
        cw.visit(
            Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SYNTHETIC,
            internal, null, "java/lang/Object", null,
        )
        cw.visitField(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "HANDLER", "Ljava/lang/invoke/MethodHandle;", null, null,
        ).visitEnd()
        val desc = bridgeType.toMethodDescriptorString()
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "invoke", desc, null, null)
        mv.visitCode()
        val argTypes = Type.getArgumentTypes(desc)
        val captureCount = handler.captureTypes.size
        val captureSlots = argTypes.take(captureCount).sumOf { it.size }
        val captureDesc = argTypes.take(captureCount).joinToString("") { it.descriptor }
        fun loadField() = mv.visitFieldInsn(
            Opcodes.GETSTATIC, internal, "HANDLER", "Ljava/lang/invoke/MethodHandle;",
        )
        fun loadCaptures() {
            var slot = 0
            for (i in 0 until captureCount) {
                mv.visitVarInsn(argTypes[i].getOpcode(Opcodes.ILOAD), slot)
                slot += argTypes[i].size
            }
        }
        when (kind) {
            BridgeKind.CANCELABLE -> {
                val contextSlot = captureSlots + 1
                mv.visitTypeInsn(Opcodes.NEW, "org/ohmyloader/api/hook/HookContext")
                mv.visitInsn(Opcodes.DUP)
                mv.visitLdcInsn(handler.targetMethod)
                mv.visitInsn(Opcodes.ICONST_1)
                mv.visitMethodInsn(
                    Opcodes.INVOKESPECIAL, "org/ohmyloader/api/hook/HookContext", "<init>",
                    "(Ljava/lang/String;Z)V", false,
                )
                mv.visitVarInsn(Opcodes.ASTORE, contextSlot)
                loadField()
                loadCaptures()
                mv.visitVarInsn(Opcodes.ALOAD, contextSlot)
                mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "invokeExact",
                    "(${captureDesc}Lorg/ohmyloader/api/hook/HookContext;)V", false,
                )
                mv.visitVarInsn(Opcodes.ALOAD, contextSlot)
                mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, "org/ohmyloader/api/hook/HookContext", "getCanceled", "()Z", false,
                )
                mv.visitInsn(Opcodes.IRETURN)
            }

            BridgeKind.RETURNABLE -> {
                val idSlot = captureSlots
                val callbackSlot = captureSlots + 1
                loadField()
                loadCaptures()
                mv.visitVarInsn(Opcodes.ALOAD, callbackSlot)
                mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "invokeExact",
                    "(${captureDesc}Lorg/ohmyloader/api/hook/HookValue;)V", false,
                )
                mv.visitVarInsn(Opcodes.ILOAD, idSlot)
                mv.visitVarInsn(Opcodes.ALOAD, callbackSlot)
                mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC, REGISTRY, "checkReturnable",
                    "(ILorg/ohmyloader/api/hook/HookValue;)V", false,
                )
                mv.visitInsn(Opcodes.RETURN)
            }

            else -> throw UnsupportedOperationException("no read-back stub for $kind")
        }
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        cw.visitEnd()
        val lookup = MethodHandles.lookup()
        val defined = lookup.defineClass(cw.toByteArray())
        defined.getDeclaredField("HANDLER").apply { isAccessible = true }.set(null, isolated)
        return lookup.findStatic(defined, "invoke", bridgeType)
    }

    /**
     * The reflective path: generate a tiny static stub with the **exact bridge descriptor** (pack captures,
     * call [dispatch] / [dispatchReturnable] / [dispatchValue], unbox), define it in this package and return
     * a handle to it. `asCollector` cannot collect primitive captures, and a stub keeps the previous
     * semantics exactly — including the observable exceptions when the handler is missing.
     */
    private fun reflectiveCallSite(handler: Handler, kind: BridgeKind, bridgeType: MethodType): MethodHandle {
        val lookup = MethodHandles.lookup()
        val defined = lookup.defineClass(fallbackStub(handler, kind, bridgeType))
        return lookup.findStatic(defined, "invoke", bridgeType)
    }

    /** The body used to be the generated bridge method itself; keep it as a stub when a direct call is impossible. */
    private fun fallbackStub(handler: Handler, kind: BridgeKind, bridgeType: MethodType): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        val internal = "$REGISTRY\$Fallback${fallbackCounter.incrementAndGet()}"
        cw.visit(
            Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SYNTHETIC,
            internal, null, "java/lang/Object", null,
        )
        val desc = bridgeType.toMethodDescriptorString()
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "invoke", desc, null, null)
        mv.visitCode()
        val argTypes = Type.getArgumentTypes(desc)
        val captureCount = handler.captureTypes.size
        mv.visitLdcInsn(captureCount)
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object")
        var slot = 0
        for (i in 0 until captureCount) {
            val type = argTypes[i]
            mv.visitInsn(Opcodes.DUP)
            mv.visitLdcInsn(i)
            mv.visitVarInsn(type.getOpcode(Opcodes.ILOAD), slot)
            Boxing.box(type)?.let { box ->
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, box.owner, box.name, box.desc, false)
            }
            mv.visitInsn(Opcodes.AASTORE)
            slot += type.size
        }
        if (kind == BridgeKind.MIRROR) {
            mv.visitLdcInsn(handler.id)
        } else {
            mv.visitVarInsn(Opcodes.ILOAD, slot)
            slot += 1
        }
        when (kind) {
            BridgeKind.NOTIFY -> {
                mv.visitLdcInsn(false)
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY, "dispatch", "([Ljava/lang/Object;IZ)Z", false)
                mv.visitInsn(Opcodes.POP)
                mv.visitInsn(Opcodes.RETURN)
            }

            BridgeKind.CANCELABLE -> {
                mv.visitLdcInsn(true)
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY, "dispatch", "([Ljava/lang/Object;IZ)Z", false)
                mv.visitInsn(Opcodes.IRETURN)
            }

            BridgeKind.RETURNABLE -> {
                mv.visitVarInsn(Opcodes.ALOAD, slot)
                mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC, REGISTRY, "dispatchReturnable",
                    "([Ljava/lang/Object;I$CALLBACK_RETURNABLE_DESC)V", false,
                )
                mv.visitInsn(Opcodes.RETURN)
            }

            BridgeKind.MODIFY, BridgeKind.MIRROR -> {
                mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC, REGISTRY, "dispatchValue",
                    "([Ljava/lang/Object;I)Ljava/lang/Object;", false,
                )
                val ret = Type.getReturnType(desc)
                if (ret == Type.VOID_TYPE) {
                    mv.visitInsn(Opcodes.POP)
                    mv.visitInsn(Opcodes.RETURN)
                } else {
                    val unbox = Boxing.unbox(ret)
                    if (unbox == null) {
                        mv.visitTypeInsn(Opcodes.CHECKCAST, ret.internalName)
                    } else {
                        mv.visitTypeInsn(Opcodes.CHECKCAST, unbox.owner)
                        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, unbox.owner, unbox.name, unbox.desc, false)
                    }
                    mv.visitInsn(Boxing.returnOpcode(ret))
                }
            }
        }
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun methodTypeOf(method: Method): MethodType =
        MethodType.methodType(method.returnType, method.parameterTypes)

    /** Generate the bridge method for a single handler: one `invokedynamic`, linked at first execution. */
    private fun emitBridgeMethod(cw: ClassWriter, handler: Handler) {
        val kind = kindOf(handler)
        val desc = bridgeDesc(handler.id, kind)
        val mv = cw.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC,
            bridgeMethodName(handler.id, kind), desc, null, null,
        )
        mv.visitCode()
        // The indy call site takes the bridge's own parameters as-is: no capture array, no boxing
        var slot = 0
        for (argument in Type.getArgumentTypes(desc)) {
            mv.visitVarInsn(argument.getOpcode(Opcodes.ILOAD), slot)
            slot += argument.size
        }
        mv.visitInvokeDynamicInsn("call", desc, BOOTSTRAP, handler.id, kind.ordinal)
        mv.visitInsn(Type.getReturnType(desc).getOpcode(Opcodes.IRETURN))
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }


    // ---------- entry points used by the fallback stub (reflection and exception isolation live here) ----------

    /**
     * [Bridge entry point] Execute the handler and return whether it canceled.
     *
     * @param captures the captured locals (boxed), in the same order as the handler's parameters
     */
    @JvmStatic
    fun dispatch(captures: Array<Any?>, id: Int, cancelable: Boolean): Boolean {
        val handler = handlers[id] ?: return false
        var canceled = false
        try {
            val call = resolvedCall(handler)
            val ci = HookContext(handler.targetMethod, cancelable)
            call.method.invoke(call.receiver, *(captures + ci))
            canceled = ci.canceled
        } catch (t: Throwable) {
            reportHandlerFailure(handler, t)
        }
        return canceled
    }

    /** Recovery for a failing NOTIFY handler: the same isolation as [dispatch], without a per-call wrapper. */
    @JvmStatic
    fun reportNotifyFailure(id: Int, t: Throwable) {
        val handler = handlers[id] ?: return
        reportHandlerFailure(handler, t)
    }

    /**
     * [Bridge entry point] Short-circuit with a return value: the handle is **created and passed in by the
     * engine**; once the handler calls `setReturnValue(v)` (which includes `cancel()`), the engine reads back
     * `canceled` and the return value and emits `xRETURN`. This method only needs to call the handler into
     * action and keep exceptions isolated.
     *
     * @param callback the callback handle created by the injection point on the target method
     */
    @JvmStatic
    fun dispatchReturnable(captures: Array<Any?>, id: Int, callback: HookValue<*>) {
        val handler = handlers[id] ?: return
        try {
            val call = resolvedCall(handler)
            call.method.invoke(call.receiver, *(captures + callback))
            checkReturnable(handler.id, callback)
        } catch (t: Throwable) {
            reportHandlerFailure(handler, t)
        }
    }

    /** Warn on "canceled without a return value": the engine would return the zero value (0/false/null). */
    @JvmStatic
    fun checkReturnable(handlerId: Int, callback: HookValue<*>) {
        if (!callback.canceled || callback.returnValue != null) return
        val handler = handlers[handlerId] ?: return
        OmlLog.warn(
            "Mixin",
            "a Mixin handler canceled a non-void target but did not set a return value, will return the zero value " +
                "(mod=${handler.modId}, ${handler.mixinClass}.${handler.handlerMethod} → " +
                "${handler.targetMethod}); use ci.setReturnValue(v)",
        )
    }

    /**
     * [Bridge entry point] Take the handler's **return value** as the result -- used by both `@ModifyArg` /
     * `@ModifyConstant` (modifying an argument/constant) and `@Redirect` (replacing a whole call).
     *
     * **Exceptions are not isolated** (unlike [dispatch] / [dispatchReturnable]): these two kinds "replace real
     * behavior", so swallowing an exception would mean that call/assignment silently did not happen -- exactly
     * the failure mode this project fears most. So the reflection wrapper is unwrapped and the handler's
     * exception propagates as-is (the stack trace shows which mixin handler it came from).
     */
    @JvmStatic
    fun dispatchValue(captures: Array<Any?>, id: Int): Any? {
        val handler = handlers[id] ?: return null
        val call = resolvedCall(handler)
        return try {
            call.method.invoke(call.receiver, *captures)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    /**
     * Resolve (and cache) the handler call for [handler]: the method plus its receiver — **null for a
     * static handler**, which the bridge dispatches without instantiating the mixin class. Unresolvable
     * handlers are **not** cached: they throw on every dispatch, matching the pre-cache failure behavior.
     */
    private fun resolvedCall(handler: Handler): ResolvedCall =
        resolvedCalls[handler.id] ?: run {
            val cl = loader ?: throw IllegalStateException("the Mixin class loader is not ready yet")
            val clazz = Class.forName(handler.mixinClass, true, cl)
            val method = handlerMethod(handler, clazz)
            val receiver = if ((method.modifiers and java.lang.reflect.Modifier.STATIC) != 0) {
                null
            } else {
                mixinInstance(handler.mixinClass)
            }
            ResolvedCall(receiver, method).also { resolvedCalls[handler.id] = it }
        }

    /**
     * Resolve (and cache) the handler method for [handler] on [clazz]. Unresolvable handlers are **not**
     * cached: they throw on every dispatch, matching the pre-cache failure behavior.
     */
    private fun handlerMethod(handler: Handler, clazz: Class<*>): Method =
        resolvedMethods[handler.id] ?: resolveHandlerMethod(clazz, handler).also { resolvedMethods[handler.id] = it }

    private fun handlerDescription(handler: Handler): String = when {
        handler.returnsValue -> "${handler.mixinClass}.${handler.handlerMethod} (expected ${handler.handlerParamCount} parameter(s)," +
            " i.e. ${handler.handlerParamCount - 1} capture(s) + HookValue)"

        handler.mirror || handler.returnType != "V" ->
            "${handler.mixinClass}.${handler.handlerMethod} (expected shaped (captures…)${handler.returnType}," +
                " the return type must match the position being rewritten)"

        else -> "${handler.mixinClass}.${handler.handlerMethod} (expected ${handler.handlerParamCount} parameter(s)," +
            " i.e. ${handler.handlerParamCount - 1} capture(s) + HookContext)"
    }

    private fun reportHandlerFailure(handler: Handler, t: Throwable) {
        val cause = (t as? InvocationTargetException)?.targetException ?: t
        OmlLog.error(
            "Mixin",
            "Mixin handler exception (mod=${handler.modId}, " +
                "${handler.mixinClass}.${handler.handlerMethod}): $cause",
        )
    }

    /**
     * Pick out the handler method for this id.
     *
     * **Match by exact descriptor only.** When the same mixin class has multiple overloads with the same
     * parameter count, the order of `declaredMethods` / `methods` is undefined — binding "the first
     * candidate" would invoke an arbitrary overload. A descriptor mismatch is a provable rule error, so
     * it fails the dispatch (isolated for notify kinds, propagated for value-modifying kinds) instead of
     * picking a method the rule did not declare. The expected parameter table varies by kind:
     * notify/short-circuit kinds end with the callback handle; [BridgeKind.MODIFY] / [BridgeKind.MIRROR]
     * have no handle, their parameters are captures + the return type.
     */
    private fun resolveHandlerMethod(clazz: Class<*>, handler: Handler): Method {
        val wanted = when (kindOf(handler)) {
            BridgeKind.NOTIFY, BridgeKind.CANCELABLE ->
                "(${handler.captureTypes.joinToString("")}$CALLBACK_INFO_DESC)V"

            BridgeKind.RETURNABLE ->
                "(${handler.captureTypes.joinToString("")}$CALLBACK_RETURNABLE_DESC)V"

            BridgeKind.MODIFY, BridgeKind.MIRROR ->
                "(${handler.captureTypes.joinToString("")})${handler.returnType}"
        }
        val candidates = clazz.methods.filter {
            it.name == handler.handlerMethod && it.parameterCount == handler.handlerParamCount
        }
        return candidates.firstOrNull { Type.getMethodDescriptor(it) == wanted }
            ?: throw NoSuchMethodException(
                "${handlerDescription(handler)} — no overload of ${clazz.name} matches the wanted descriptor " +
                    "$wanted (candidates: ${
                        candidates.joinToString { Type.getMethodDescriptor(it) }.ifEmpty { "none" }
                    })",
            )
    }

    /**
     * Get (and lazily create) an instance of the mixin class.
     *
     * **Constructor access must be opened explicitly**: a mixin class does not necessarily have a `public`
     * constructor -- Kotlin's `object` (singleton holder) has a private constructor, and using it as a mixin
     * container is natural. Without opening access you get an `IllegalAccessException: … cannot access a member
     * of class … with modifiers "private"`, which the exception-isolation mechanism swallows into one log line,
     * making it hard to see the problem is the constructor and not the handler.
     */
    private fun mixinInstance(mixinClass: String): Any = mixinInstances.computeIfAbsent(mixinClass) {
        val cl = loader ?: throw IllegalStateException("the Mixin class loader is not ready yet")
        val clazz = Class.forName(mixinClass, true, cl)
        val ctor = clazz.getDeclaredConstructor()
        ctor.isAccessible = true
        ctor.newInstance()
    }

    private const val REGISTRY = "org/ohmyloader/core/hook/HookRegistry"
}
