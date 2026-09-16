package org.ohmyloader.core.mixin

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.ohmyloader.api.mixin.CallbackInfo
import org.ohmyloader.api.mixin.CallbackInfoReturnable
import org.ohmyloader.core.transformer.injection.Boxing
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Mixin handler registry and runtime bridge. During the scan phase each `@Inject` handler is assigned an int
 * id written into the injection bytecode; at the injection point the game calls a static method on the **bridge
 * class**, which invokes the handler reflectively; handler exceptions are caught and isolated so they cannot
 * disturb the game itself. Why a generated bridge: a handler is an **instance method** on the mixin class
 * while the target method's `this` is a different object, and the injection point's shape is not
 * decidable at compile time (captures depend on each handler's parameter table); a flat `vararg Object[]`
 * would box primitives in the game's main loop (`runTick` runs every frame), while the concrete-descriptor
 * bridge keeps the injection point a clean `INVOKESTATIC` aligned with `DslValue.Local` — zero boxing.
 * Generated **once at startup** (self-check phase), one method per handler (cancellable return `Z`, else `V`).
 */
object OMLMixinRegistry {

    internal const val BRIDGE_CLASS = "org/ohmyloader/core/mixin/GeneratedMixinBridge"

    /**
     * The bridge kind — decides the generated bridge method name, descriptor, and which entry point is called.
     *
     * - [NOTIFY]: announce only, `(captures…, I)V`
     * - [CANCELLABLE]: short-circuit a void target, `(captures…, I)Z`
     * - [RETURNABLE]: short-circuit a non-void target with a value, `(captures…, I, CallbackInfoReturnable)V`
     * - [MODIFY]: use the handler's return value as the result, `(captures…, I)<R>` (`R` = modified argument/constant type)
     * - [MIRROR]: the whole call is replaced by this static method, `(captures…)<R>` — no id parameter (id is
     *   encoded in the method name) and the descriptor **must equal the replaced call's descriptor** (`@Redirect`;
     *   on an instance call the receiver is the first capture)
     */
    enum class BridgeKind { NOTIFY, CANCELLABLE, RETURNABLE, MODIFY, MIRROR }

    /**
     * @param captureTypes JVM descriptors of the captured parameters, order == handler parameter order == push
     *   order. Slot numbers are not here — they belong to the injection point (resolved/validated there by
     *   data-flow analysis); the registry only needs the bridge method's parameter shape.
     * @param handlerParamCount the handler's parameter count (including implicit captures; picks the exact overload)
     * @param returnsValue whether the handler takes a `CallbackInfoReturnable` (non-void target short-circuiting
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
        val cancellable: Boolean,
        val captureTypes: List<String> = emptyList(),
        val handlerParamCount: Int = 1,
        val returnsValue: Boolean = false,
        val returnType: String = "V",
        val mirror: Boolean = false,
    )

    private val nextId = AtomicInteger()
    private val handlers = mutableMapOf<Int, Handler>()
    private val mixinInstances = mutableMapOf<String, Any>()
    private var loader: ClassLoader? = null
    private var bridgeLoaded = false

    /** The injection bytecode needs to load mod classes at dispatch time; set by OMLCore during assembly. */
    fun installClassLoader(loader: ClassLoader) {
        this.loader = loader
    }

    fun register(
        modId: String,
        mixinClass: String,
        handlerMethod: String,
        targetMethod: String,
        cancellable: Boolean,
        captureTypes: List<String> = emptyList(),
        handlerParamCount: Int = captureTypes.size + 1,
        returnsValue: Boolean = false,
        returnType: String = "V",
        mirror: Boolean = false,
    ): Int {
        val id = nextId.incrementAndGet()
        handlers[id] = Handler(
            id, modId, mixinClass, handlerMethod, targetMethod, cancellable,
            captureTypes, handlerParamCount, returnsValue, returnType, mirror,
        )
        return id
    }

    /** Look up a registered handler (for the front end to build the bridge-call descriptor). */
    fun handler(id: Int): Handler? = handlers[id]

    /** Which kind of bridge this handler uses (derived from the flags set at registration). */
    private fun kindOf(handler: Handler): BridgeKind = when {
        handler.returnsValue -> BridgeKind.RETURNABLE
        handler.cancellable -> BridgeKind.CANCELLABLE
        handler.mirror -> BridgeKind.MIRROR
        handler.returnType != "V" -> BridgeKind.MODIFY
        else -> BridgeKind.NOTIFY
    }

    /** Bridge method name: `inject$<id>` / `injectCancellable$<id>` / … ([BridgeKind.MIRROR] uses `redirect$<id>`). */
    fun bridgeMethodName(id: Int, cancellable: Boolean): String =
        bridgeMethodName(id, if (cancellable) BridgeKind.CANCELLABLE else BridgeKind.NOTIFY)

    /** Bridge method name by kind ([BridgeKind.RETURNABLE] etc. can only use this overload). */
    fun bridgeMethodName(id: Int, kind: BridgeKind): String = when (kind) {
        BridgeKind.NOTIFY -> "inject$$id"
        BridgeKind.CANCELLABLE -> "injectCancellable$$id"
        BridgeKind.RETURNABLE -> "injectReturn$$id"
        BridgeKind.MODIFY -> "injectValue$$id"
        BridgeKind.MIRROR -> "redirect$$id"
    }

    /**
     * The bridge method's JVM descriptor. Convention: `(captures…, id[, callback handle])` -- the captured
     * arguments come first with id right after, and [BridgeKind.RETURNABLE] appends a `CallbackInfoReturnable`
     * at the end (created by the engine); [BridgeKind.MIRROR] is the only kind **without an id parameter** (its
     * descriptor must exactly equal the replaced call, so id can only be encoded in the method name).
     *
     * This is the **only** interface convention between the injection bytecode and the reflective call; both the
     * front end's `INVOKESTATIC` construction and the generator must agree with it here.
     */
    fun bridgeDesc(id: Int, cancellable: Boolean): String =
        bridgeDesc(id, if (cancellable) BridgeKind.CANCELLABLE else BridgeKind.NOTIFY)

    /** Bridge descriptor by kind. */
    fun bridgeDesc(id: Int, kind: BridgeKind): String {
        val handler = handlers[id]
        val types = handler?.captureTypes.orEmpty().joinToString("")
        val ret = handler?.returnType ?: "V"
        return when (kind) {
            BridgeKind.NOTIFY -> "(${types}I)V"
            BridgeKind.CANCELLABLE -> "(${types}I)Z"
            BridgeKind.RETURNABLE -> "(${types}I$CALLBACK_RETURNABLE_DESC)V"
            BridgeKind.MODIFY -> "(${types}I)$ret"
            BridgeKind.MIRROR -> "($types)$ret"
        }
    }

    /** A handler's bridge kind (the front end builds the `INVOKESTATIC` from this). */
    fun bridgeKind(id: Int): BridgeKind? = handlers[id]?.let { kindOf(it) }

    /**
     * Generate the bridge class (idempotent). **Must be called before any injection bytecode runs** — called
     * once by [org.ohmyloader.core.MixinScanner.createTransformer] after scanning **all** mods and registering
     * **all** handlers. A handler registered after this call has no bridge method, so generation can only
     * happen at the point where everything is registered. The result is a class of static methods whose
     * bodies are "pack captures → look up → reflectively invoke"; it is generated rather than hard-coded
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

    /** Generate the bridge method for a single handler. */
    private fun emitBridgeMethod(cw: ClassWriter, handler: Handler) {
        val kind = kindOf(handler)
        val argTypes = handler.captureTypes.map { Type.getType(it) }
        val desc = bridgeDesc(handler.id, kind)
        val mv = cw.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC,
            bridgeMethodName(handler.id, kind), desc, null, null,
        )
        mv.visitCode()

        // Pack the captured arguments into an Object[] (parameter slots: captures… then id[, callback handle]),
        // forwarding to dispatch. The boxing cost only kicks in when the handler **actually declares captures**;
        // a bridge with no declared captures is an empty array + one INVOKESTATIC, equivalent to the static
        // bridge path.
        mv.visitLdcInsn(argTypes.size)
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object")
        var slot = 0
        argTypes.forEachIndexed { i, type ->
            mv.visitInsn(Opcodes.DUP)
            mv.visitLdcInsn(i)
            mv.visitVarInsn(loadOpcode(type), slot)
            boxIfPrimitive(mv, type)
            mv.visitInsn(Opcodes.AASTORE)
            slot += type.size
        }

        // id comes right after the captured arguments; **except MIRROR** -- its descriptor must equal the replaced
        // call, leaving no room for an id parameter, so the id is encoded in the method name and pushed with LDC here
        if (kind == BridgeKind.MIRROR) mv.visitLdcInsn(handler.id) else mv.visitVarInsn(Opcodes.ILOAD, slot)
        when (kind) {
            BridgeKind.NOTIFY -> {
                mv.visitLdcInsn(false)
                mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC, REGISTRY,
                    "dispatch", "([Ljava/lang/Object;IZ)Z", false,
                )
                mv.visitInsn(Opcodes.POP)
                mv.visitInsn(Opcodes.RETURN)
            }

            BridgeKind.CANCELLABLE -> {
                mv.visitLdcInsn(true)
                mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC, REGISTRY,
                    "dispatch", "([Ljava/lang/Object;IZ)Z", false,
                )
                mv.visitInsn(Opcodes.IRETURN)
            }

            BridgeKind.RETURNABLE -> {
                // The handle is the last parameter (created and passed in by the engine)
                mv.visitVarInsn(Opcodes.ALOAD, slot + 1)
                mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC, REGISTRY,
                    "dispatchReturnable", "([Ljava/lang/Object;I$CALLBACK_RETURNABLE_DESC)V", false,
                )
                mv.visitInsn(Opcodes.RETURN)
            }

            // MODIFY / MIRROR: whatever the handler returns is the result; unbox and return it
            BridgeKind.MODIFY, BridgeKind.MIRROR -> {
                mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC, REGISTRY,
                    "dispatchValue", "([Ljava/lang/Object;I)Ljava/lang/Object;", false,
                )
                emitUnboxAndReturn(mv, Type.getType(handler.returnType))
            }
        }

        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /** Convert the `Object` returned by `dispatchValue` to [type] and `xRETURN` (primitives are CHECKCAST first, then unboxed). */
    private fun emitUnboxAndReturn(mv: org.objectweb.asm.MethodVisitor, type: Type) {
        if (type == Type.VOID_TYPE) {
            mv.visitInsn(Opcodes.POP)
            mv.visitInsn(Opcodes.RETURN)
            return
        }
        val unbox = Boxing.unbox(type)
        if (unbox == null) {
            mv.visitTypeInsn(Opcodes.CHECKCAST, type.internalName)
        } else {
            mv.visitTypeInsn(Opcodes.CHECKCAST, unbox.owner)
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, unbox.owner, unbox.name, unbox.desc, false)
        }
        mv.visitInsn(returnOpcode(type))
    }

    private fun returnOpcode(type: Type): Int = when (type.sort) {
        Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Opcodes.IRETURN
        Type.LONG -> Opcodes.LRETURN
        Type.FLOAT -> Opcodes.FRETURN
        Type.DOUBLE -> Opcodes.DRETURN
        else -> Opcodes.ARETURN
    }

    /** Load instruction for a local via [Boxing] -- the mapping is maintained in exactly one place (shared with the injection engine). */
    private fun loadOpcode(type: Type): Int = Boxing.loadOpcode(type)

    /** Box via [Boxing] -- the mapping is maintained in exactly one place (shared with the injection engine). */
    private fun boxIfPrimitive(mv: org.objectweb.asm.MethodVisitor, type: Type) {
        val box = Boxing.box(type) ?: return
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, box.owner, box.name, box.desc, false)
    }

    // ---------- entry points called by the generated bridge class (reflection and exception isolation live here) ----------

    /**
     * [Bridge entry point] Execute the handler and return whether it cancelled.
     *
     * @param captures the captured locals (boxed), in the same order as the handler's parameters
     */
    @JvmStatic
    fun dispatch(captures: Array<Any?>, id: Int, cancellable: Boolean): Boolean {
        val handler = handlers[id] ?: return false
        var canceled = false
        try {
            val instance = mixinInstance(handler.mixinClass)
            val method = resolveHandlerMethod(instance.javaClass, handler)
                ?: throw NoSuchMethodException(handlerDescription(handler))
            val ci = CallbackInfo(handler.targetMethod, cancellable)
            method.invoke(instance, *(captures + ci))
            canceled = ci.canceled
        } catch (t: Throwable) {
            reportHandlerFailure(handler, t)
        }
        return canceled
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
    fun dispatchReturnable(captures: Array<Any?>, id: Int, callback: CallbackInfoReturnable<*>) {
        val handler = handlers[id] ?: return
        try {
            val instance = mixinInstance(handler.mixinClass)
            val method = resolveHandlerMethod(instance.javaClass, handler)
                ?: throw NoSuchMethodException(handlerDescription(handler))
            method.invoke(instance, *(captures + callback))
            if (callback.canceled && callback.returnValue == null) {
                // Cancelled without setting a value: the engine will use the zero value for the return type
                // (0/false/null), and this is almost always a mistake
                System.err.println(
                    "[OML] a Mixin handler cancelled a non-void target but did not set a return value, will return the zero value " +
                        "(mod=${handler.modId}, ${handler.mixinClass}.${handler.handlerMethod} → " +
                        "${handler.targetMethod}); use ci.setReturnValue(v)"
                )
            }
        } catch (t: Throwable) {
            reportHandlerFailure(handler, t)
        }
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
        val instance = mixinInstance(handler.mixinClass)
        val method = resolveHandlerMethod(instance.javaClass, handler)
            ?: throw NoSuchMethodException(handlerDescription(handler))
        return try {
            method.invoke(instance, *captures)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun handlerDescription(handler: Handler): String = when {
        handler.returnsValue -> "${handler.mixinClass}.${handler.handlerMethod} (expected ${handler.handlerParamCount} parameter(s)," +
            " i.e. ${handler.handlerParamCount - 1} capture(s) + CallbackInfoReturnable)"

        handler.mirror || handler.returnType != "V" ->
            "${handler.mixinClass}.${handler.handlerMethod} (expected shaped (captures…)${handler.returnType}," +
                " the return type must match the position being rewritten)"

        else -> "${handler.mixinClass}.${handler.handlerMethod} (expected ${handler.handlerParamCount} parameter(s)," +
            " i.e. ${handler.handlerParamCount - 1} capture(s) + CallbackInfo)"
    }

    private fun reportHandlerFailure(handler: Handler, t: Throwable) {
        val cause = (t as? InvocationTargetException)?.targetException ?: t
        System.err.println(
            "[OML] Mixin handler exception (mod=${handler.modId}, " +
                "${handler.mixinClass}.${handler.handlerMethod}): $cause"
        )
    }

    /**
     * Pick out the handler method for this id.
     *
     * **Match by exact descriptor first, then fall back to parameter count.** Matching only by count picks the
     * wrong overload when the same mixin class has multiple overloads with the same parameter count, and the
     * order of `declaredMethods` / `methods` is undefined. The expected parameter table varies by kind:
     * notify/short-circuit kinds end with the callback handle; [BridgeKind.MODIFY] / [BridgeKind.MIRROR] have
     * no handle, their parameters are captures + the return type.
     */
    private fun resolveHandlerMethod(clazz: Class<*>, handler: Handler): java.lang.reflect.Method? {
        val wanted = when (kindOf(handler)) {
            BridgeKind.NOTIFY, BridgeKind.CANCELLABLE ->
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
            ?: candidates.firstOrNull()
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
    private fun mixinInstance(mixinClass: String): Any = synchronized(mixinInstances) {
        mixinInstances.getOrPut(mixinClass) {
            val cl = loader ?: throw IllegalStateException("the Mixin class loader is not ready yet")
            val clazz = Class.forName(mixinClass, true, cl)
            val ctor = clazz.getDeclaredConstructor()
            ctor.isAccessible = true
            ctor.newInstance()
        }
    }

    private const val REGISTRY = "org/ohmyloader/core/mixin/OMLMixinRegistry"
    private const val CALLBACK_INFO_DESC = "Lorg/ohmyloader/api/mixin/CallbackInfo;"
    private const val CALLBACK_RETURNABLE_DESC = "Lorg/ohmyloader/api/mixin/CallbackInfoReturnable;"
}
