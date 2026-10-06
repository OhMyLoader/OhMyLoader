package org.ohmyloader.core.mixin

import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import org.ohmyloader.core.mixin.ClassMerger.inspectConstructor
import org.ohmyloader.core.mixin.ClassMerger.isMergeableCtorInsn

/**
 * **Class-merge engine**: merges the mixin class's fields/methods into the target class so the merged code
 * lives there: `this` is the target instance, the target's `private` members are directly visible, `@Overwrite`
 * replaces a whole target method body. Constraints that make or break the merge:
 * 1. Self-references must be re-pointed — a moved-in `GETFIELD/INVOKEVIRTUAL <mixin>.…` still naming the mixin
 *    class is a runtime `NoSuchFieldError` / `NoSuchMethodError`.
 * 2. Stack frames must move along and be re-pointed too; write-back uses `COMPUTE_MAXS`, **not** `COMPUTE_FRAMES`
 *    (which would reverse-load other classes mid-definition, risking re-entrancy and identity splits).
 * 3. Renaming must be internally consistent — one missed reference surfaces only as a runtime `NoSuchFieldError`.
 * 4. Instance initialization must land in the target's constructor (a `final` field is only assignable in its
 *    declaring class's `<init>`), see [mergeConstructor].
 */
internal object ClassMerger {

    /** A mixin pending merge (produced by [MixinScanner] during the scan phase). */
    internal class MixinClass(
        val modId: String,
        val className: String,
        val targetInternal: String,
        val node: ClassNode,
        /**
         * "`name + desc` → handler method directly referenced by injection rules".
         *
         * These methods get two special treatments, **both mandatory**:
         * 1. **Keep the original name** -- the injection point hard-codes this name
         *    (`INVOKEVIRTUAL target class.<handler name>`); renaming would cause a runtime
         *    `NoSuchMethodError` at the injection point;
         * 2. **Access flags lifted to public** -- handlers are often `private`, and the JVM requires
         *    `invokevirtual` never to call a private method (would give a `VerifyError`).
         */
        val handlerMethods: Set<String> = emptySet(),
    )

    /** The result of one merge (for the startup report and self-check). */
    internal class Result(
        /** Number of new fields merged into the target class. */
        val fields: Int,
        /** Number of new methods merged into the target class (excluding the segment spliced into `<clinit>`). */
        val methods: Int,
        /** Number of methods replaced by `@Overwrite`. */
        val overwrites: Int,
        /** Number of members whose `@Shadow` check passed (fields + methods). */
        val shadows: Int,
        /** Number of members auto-renamed for sharing a name and descriptor with the target. */
        val renamed: Int,
        val problems: List<String>,
        /** Number of `@Accessor` synthesized for an interface mixin. */
        val accessors: Int = 0,
        /** Number of `@Invoker` synthesized for an interface mixin. */
        val invokers: Int = 0,
        /** Number of target constructors that received the mixin constructor body. */
        val constructors: Int = 0,
    ) {
        val changed: Boolean
            get() = fields > 0 || methods > 0 || overwrites > 0 ||
                accessors > 0 || invokers > 0 || constructors > 0
    }

    private const val SHADOW_DESC = "Lorg/ohmyloader/api/mixin/Shadow;"
    private const val OVERWRITE_DESC = "Lorg/ohmyloader/api/mixin/Overwrite;"
    private const val ACCESSOR_DESC = "Lorg/ohmyloader/api/mixin/Accessor;"
    private const val INVOKER_DESC = "Lorg/ohmyloader/api/mixin/Invoker;"

    /** `@Overwrite` ledger key: **must include the target class** -- otherwise two mixins overwriting the same-named method in different classes would yield a false conflict. */
    private fun overwriteKey(target: String, name: String, desc: String): String = "$target.$name$desc"

    /**
     * Merge [mixin] into [target].
     *
     * @param overwritten the cross-mixin "who already @Overwrite'd this method" ledger (key = `name+desc`) --
     *   two mixins fighting over the same target method must report a conflict, never silently pick one
     */
    /**
     * Does the merged-in code use instructions the target class's class-file version cannot express?
     *
     * Merging moves bytecode verbatim into another class, so it must fit the **target's** version:
     * `invokedynamic` and `MethodHandle`/`MethodType` constants require class-file version ≥ 51, and a
     * target compiled below that makes the **whole class fail to load** (`ClassFormatError`) — with the
     * error naming only the target class, not who stuffed the constant in. Kotlin string templates and
     * lambdas both compile to `invokedynamic`; such code belongs in a class that is not merged in (e.g.
     * hand the value over through one static call).
     */
    private fun versionClash(code: InsnList, target: ClassNode): String? {
        if (target.version >= Opcodes.V1_7) return null
        for (insn in code) {
            if (insn is InvokeDynamicInsnNode) return "invokedynamic"
            if (insn is LdcInsnNode) {
                val constant = insn.cst
                if (constant is Handle) return "MethodHandle constant"
                if (constant is Type && constant.sort == Type.METHOD) return "MethodType constant"
            }
        }
        return null
    }

    fun merge(mixin: MixinClass, target: ClassNode, overwritten: MutableMap<String, String>): Result {
        val problems = mutableListOf<String>()
        val where = "[${mixin.modId}] ${mixin.className} → ${target.name}"
        val self = mixin.node.name

        if (target.name != mixin.targetInternal) {
            problems += "$where —— the target declared by the mixin does not match the current class (target is ${mixin.targetInternal})"
            return Result(0, 0, 0, 0, 0, problems)
        }
        if (target.access and Opcodes.ACC_INTERFACE != 0) {
            problems += "$where —— the target ${target.name} is an interface: merging fields/methods into an interface is not supported"
            return Result(0, 0, 0, 0, 0, problems)
        }
        if (mixin.node.access and Opcodes.ACC_INTERFACE != 0) {
            // Interface mixin: the mixin is an interface, so it has no fields/method bodies to merge in;
            // its abstract methods are only **signature declarations**, and the engine synthesizes concrete
            // method bodies in the target class from them
            return mergeAccessors(mixin, target, where, problems)
        }

        // ---------- Pass 1: name assignment ----------
        // Fields and methods each have their own namespace, so the two rename tables are maintained separately
        val fieldRename = mutableMapOf<String, String>()
        val methodRename = mutableMapOf<String, String>()
        var shadows = 0
        var renamed = 0

        val mergedFields = mutableListOf<FieldNode>()
        for (field in mixin.node.fields) {
            val shadow = field.visibleAnnotations?.find { it.desc == SHADOW_DESC }
            if (shadow != null) {
                val hit = resolveShadow(field.name, shadow, field.desc, target.fields.map { it.name to it.desc })
                if (hit == null) {
                    problems += "$where —— the @Shadow field could not find a member with the same name and descriptor in the target class:" +
                        "${field.name} ${field.desc}${shadowHint(shadow)}" +
                        " (@Shadow declares that the target class already has its member; writing one that does not exist in the target is surely a mistake)"
                    continue
                }
                shadows++
                if (hit != field.name) fieldRename[field.name] = hit
                continue // not merged in
            }
            val finalName = uniqueFieldName(field.name, field.desc, target, mergedFields)
            if (finalName != field.name) {
                fieldRename[field.name] = finalName
                renamed++
            }
            mergedFields += FieldNode(field.access, finalName, field.desc, field.signature, field.value)
                .also { it.visibleAnnotations = field.visibleAnnotations }
        }

        val mergedMethods = mutableListOf<MethodNode>()

        /**
         * A mixin constructor with a body (source of the merge segment).
         *
         * Only one is allowed: the merge segment has nothing to do with the constructor's own descriptor
         * (it does not read params), and with multiple sources the ordering would have to fall back to
         * declaration order -- an implicit rule that is hard to diagnose when things go wrong, so it is
         * better to report a problem.
         */
        val constructorSources = mutableListOf<MethodNode>()
        var overwrites = 0
        for (method in mixin.node.methods) {
            when {
                method.name == "<init>" -> {
                    val shadow = method.visibleAnnotations?.find { it.desc == SHADOW_DESC }
                    when {
                        method.visibleAnnotations?.any { it.desc == OVERWRITE_DESC } == true ->
                            problems += "$where —— constructor does not support @Overwrite: ${method.name}${method.desc}" +
                                " (wholesale replacement would replace the target's delegate call too)." +
                                " To add code to the constructor," +
                                " write it in the mixin constructor instead (code after the delegate call gets merged in)"

                        shadow != null -> {
                            // `@Shadow <init>` = a declaration that "the target class already has this
                            // constructor"; it is not merged in itself
                            val hit = resolveShadow(
                                method.name, shadow, method.desc,
                                target.methods.map { it.name to it.desc },
                            )
                            if (hit == null) {
                                problems += "$where —— the @Shadow constructor could not find a member with the same descriptor in the target class:" +
                                    "${method.name}${method.desc}"
                            } else {
                                shadows++
                            }
                        }

                        !isTrivialConstructor(method) -> constructorSources += method
                    }
                }

                method.name == "<clinit>" -> Unit // spliced in pass 4

                method.visibleAnnotations?.any { it.desc == OVERWRITE_DESC } == true -> {
                    if (method.instructions.size() == 0) {
                        problems += "$where —— @Overwrite ${method.name}${method.desc} has no method body"
                        continue
                    }
                    if (target.methods.none { it.name == method.name && it.desc == method.desc }) {
                        problems += "$where —— @Overwrite ${method.name}${method.desc} has no corresponding method in the target class" +
                            " (the descriptor must match the target method exactly)"
                        continue
                    }
                    val key = overwriteKey(target.name, method.name, method.desc)
                    val owner = overwritten[key]
                    if (owner != null) {
                        problems += "$where —— @Overwrite conflict: ${method.name}${method.desc} was already overwritten by $owner" +
                            " (two mixins fighting over the same method cannot silently pick one)"
                        continue
                    }
                    overwritten[key] = mixin.className
                    overwrites++
                }

                method.visibleAnnotations?.any { it.desc == SHADOW_DESC } == true -> {
                    val shadow = method.visibleAnnotations.first { it.desc == SHADOW_DESC }
                    val hit = resolveShadow(method.name, shadow, method.desc, target.methods.map { it.name to it.desc })
                    if (hit == null) {
                        problems += "$where —— the @Shadow method could not find a member with the same name and descriptor in the target class:" +
                            "${method.name}${method.desc}${shadowHint(shadow)}"
                        continue
                    }
                    shadows++
                    if (hit != method.name) methodRename[method.name] = hit
                }

                method.instructions.size() == 0 -> problems +=
                    "$where —— method ${method.name}${method.desc} has no method body and no @Shadow:" +
                        " merging has nothing to merge in; if it already exists in the target class, mark it @Shadow"

                else -> {
                    val pinned = "${method.name}${method.desc}" in mixin.handlerMethods
                    if (pinned) {
                        // The handler name is **hard-referenced** by injection rules: it cannot be renamed, so
                        // a collision can only be reported as a problem
                        if (target.methods.any { it.name == method.name && it.desc == method.desc }) {
                            problems += "$where —— injection handler ${method.name}${method.desc} shares the name and descriptor" +
                                "with an existing target method: the handler name is referenced directly by the injection point and cannot be auto-renamed, please give it another name"
                        }
                        // private/protected would block the injection point's invokevirtual ⇒ lift to public
                        method.access = (method.access and (Opcodes.ACC_PRIVATE or Opcodes.ACC_PROTECTED).inv()) or
                            Opcodes.ACC_PUBLIC
                    } else {
                        val finalName = uniqueMethodName(method.name, method.desc, target, mergedMethods)
                        if (finalName != method.name) {
                            methodRename[method.name] = finalName
                            renamed++
                        }
                        method.name = finalName
                    }
                    mergedMethods += method
                }
            }
        }

        if (constructorSources.size > 1) {
            problems += "$where —— the mixin declares ${constructorSources.size} constructors with a method body:" +
                constructorSources.joinToString(", ") { "${it.name}${it.desc}" } +
                ". The ordering of the merged segments can only fall back to declaration order, OML does not guess —— keep only one," +
                " or write the initialization as a static field (goes through <clinit>)"
            constructorSources.clear()
        }

        // The merge segment's structural validation must be done **before re-pointing**: the `this(...)`
        // delegate is only recognizable while `INVOKESPECIAL <mixin>.<init>` has not yet been re-pointed to
        // the target class -- after re-pointing it has the same instruction shape as "the mixin itself doing
        // `new` on a target instance", and cannot be told apart.
        val ctorDelegates = mutableMapOf<MethodNode, Int>()
        for (source in constructorSources) {
            val delegate = inspectConstructor(source, mixin.node, where, problems)
            if (delegate >= 0) ctorDelegates[source] = delegate
        }

        // ---------- Pass 2: uniformly re-point internal references and stack frames ----------
        // Must be done **before** attaching these methods to the target class: the rename tables are built
        // as "original name → final name"
        for (method in mixin.node.methods) {
            reown(method, self, target.name, fieldRename, methodRename)
        }

        // ---------- Pass 3: landing ----------
        // Field names were decided in pass 1 (unique names); method names live in `methodRename`
        target.fields.addAll(mergedFields)
        mergedMethods.forEach { it.name = methodRename[it.name] ?: it.name }
        for (method in mergedMethods) {
            versionClash(method.instructions, target)?.let { clash ->
                problems += "$where —— the merged-in ${method.name}${method.desc} uses $clash," +
                    " but the target class ${target.name}'s class-file version is ${target.version}" +
                    " (Java ${target.version - 44}): write this code in a form the target version can express" +
                    " (e.g. leave string concatenation to a class that is not merged in)"
            }
        }
        target.methods.addAll(mergedMethods)

        // ---------- Pass 4: `<clinit>` splicing (static-field initializers) ----------
        mixin.node.methods.firstOrNull { it.name == "<clinit>" && it.desc == "()V" }
            ?.let { appendClinit(target, it, where, problems) }

        // ---------- Pass 5: `@Overwrite` replaces method bodies ----------
        for (method in mixin.node.methods) {
            if (method.visibleAnnotations?.any { it.desc == OVERWRITE_DESC } != true) continue
            val targetMethod = target.methods.firstOrNull { it.name == method.name && it.desc == method.desc }
                ?: continue // problem already reported above
            if (overwritten[overwriteKey(target.name, method.name, method.desc)] != mixin.className) {
                continue // conflict already reported
            }
            // Replace only the method body: access flags / annotations / exception table are kept -- those
            // belong to "this class's own method"
            targetMethod.instructions = method.instructions
            targetMethod.tryCatchBlocks = method.tryCatchBlocks
            targetMethod.maxStack = method.maxStack
            targetMethod.maxLocals = method.maxLocals
        }

        // ---------- Pass 6: constructor merging (instance initializers) ----------
        // It happens here because the merge segment must be taken **after re-pointing**: the
        // `PUTFIELD <mixin>.<field>` inside it already points at the target class by now and has been renamed
        // per the rename tables.
        var constructors = 0
        for ([source, delegate] in ctorDelegates) {
            constructors += insertConstructor(source, delegate, target, where, problems)
        }

        return Result(
            mergedFields.size, mergedMethods.size, overwrites, shadows, renamed, problems,
            constructors = constructors,
        )
    }

    // ---------- interface mixin: `@Accessor` / `@Invoker` synthesis ----------

    /**
     * Merging an interface mixin = synthesizing method bodies in the target class from the abstract
     * methods' signatures. OML supports only the interface form: an interface method naturally has no
     * body, so synthesis maps one-to-one onto validating the declaration (`@Accessor` getter reads the
     * target field, setter writes it, `@Invoker` invokes the target method — all zero reflection, placed
     * as straight-line code in the target class).
     *
     * Instance members only: an interface's abstract method cannot express a synthesized static body
     * (a Java interface static method must have a body), so a static target member reports a problem.
     */
    private fun mergeAccessors(
        mixin: MixinClass,
        target: ClassNode,
        where: String,
        problems: MutableList<String>,
    ): Result {
        var accessors = 0
        var invokers = 0

        // Things that should not appear in an interface mixin: fields, default methods, `<clinit>`, misplaced
        // injection/merge annotations
        for (field in mixin.node.fields) {
            problems += "$where —— an interface mixin cannot declare fields: ${field.name} ${field.desc}" +
                " (an accessor/invoker is only a method-signature declaration, there is no state to merge in)"
        }

        for (method in mixin.node.methods) {
            val annotations = method.visibleAnnotations.orEmpty()
            val accessor = annotations.find { it.desc == ACCESSOR_DESC }
            val invoker = annotations.find { it.desc == INVOKER_DESC }
            val whereM = "$where —— ${method.name}${method.desc}"

            if (accessor == null && invoker == null) {
                problems += "$whereM —— an interface mixin's methods must carry @Accessor or @Invoker" +
                    " (not declaring one would synthesize nothing and stuff an unaccounted-for method into the target class)"
                continue
            }
            if (method.name == "<init>" || method.name == "<clinit>") {
                problems += "$whereM —— a constructor/static initializer cannot be an accessor/invoker"
                continue
            }
            if (method.access and Opcodes.ACC_STATIC != 0) {
                problems += "$whereM —— an interface's static method must have a body and cannot be an abstract declaration (use an instance method instead)"
                continue
            }
            if (method.access and Opcodes.ACC_ABSTRACT == 0) {
                problems += "$whereM —— a default method with a body in an interface is not supported"
                continue
            }
            if (method.instructions.size() != 0 && method.access and Opcodes.ACC_ABSTRACT == 0) {
                problems += "$whereM —— the method body will not be merged (an interface mixin only declares signatures)"
                continue
            }

            val argTypes = Type.getArgumentTypes(method.desc)
            val retType = Type.getReturnType(method.desc)
            val targetMethods = target.methods.map { it.name to it.desc }

            if (accessor != null) {
                // The target class already has a method with this signature ⇒ the accessor is redundant
                // (usually the field name was written as a method name)
                if (targetMethods.any { it.first == method.name && it.second == method.desc }) {
                    problems += "$whereM —— the target class already has a method with this signature, this @Accessor is redundant"
                    continue
                }
                val fieldName = inferMemberName(
                    method.name, accessor,
                    prefixes = if (argTypes.isEmpty()) listOf("get", "is") else listOf("set"),
                )
                val targetField = target.fields.firstOrNull {
                    it.name == fieldName && it.desc == (if (argTypes.isEmpty()) retType else argTypes[0]).descriptor
                }
                if (targetField == null) {
                    problems += "$whereM —— the @Accessor could not find the field" +
                        " $fieldName : ${(if (argTypes.isEmpty()) retType else argTypes[0]).descriptor}" +
                        " (when value is left empty the field name is inferred from the get/is/set prefix)"
                    continue
                }
                if (targetField.access and Opcodes.ACC_STATIC != 0) {
                    problems += "$whereM —— target field $fieldName is a static field, accessor only supports instance fields" +
                        " (an interface's abstract method cannot express a synthesized static body)"
                    continue
                }
                val shape = when {
                    argTypes.isEmpty() && retType.sort != Type.VOID -> "getter"
                    argTypes.size == 1 && retType == Type.VOID_TYPE -> "setter"
                    else -> null
                }
                if (shape == null) {
                    problems += "$whereM —— an @Accessor can only be shaped as a getter (0 args returning the field type) or" +
                        " a setter (1 arg returning void)"
                    continue
                }
                // The synthesized body is a **concrete** method: the ACC_ABSTRACT brought by the interface declaration
                // must be dropped (otherwise the class could not be instantiated)
                val concrete = (method.access and Opcodes.ACC_ABSTRACT.inv()) or Opcodes.ACC_SYNTHETIC
                val body = if (shape == "getter") {
                    buildMethod(concrete, method.name, method.desc, 1, retType.size) {
                        add(VarInsnNode(Opcodes.ALOAD, 0))
                        add(FieldInsnNode(Opcodes.GETFIELD, target.name, targetField.name, targetField.desc))
                        add(InsnNode(retType.getOpcode(Opcodes.IRETURN)))
                    }
                } else {
                    val t = argTypes[0]
                    buildMethod(concrete, method.name, method.desc, 1 + t.size, 1 + t.size) {
                        add(VarInsnNode(Opcodes.ALOAD, 0))
                        add(VarInsnNode(t.getOpcode(Opcodes.ILOAD), 1))
                        add(FieldInsnNode(Opcodes.PUTFIELD, target.name, targetField.name, targetField.desc))
                        add(InsnNode(Opcodes.RETURN))
                    }
                }
                target.methods.add(body)
                accessors++
                continue
            }

            // ---------- @Invoker ----------
            val invokerAnn = invoker ?: continue // "both empty" was already continued above
            if (targetMethods.any { it.first == method.name && it.second == method.desc }) {
                problems += "$whereM —— the target class already has a method with this signature, this @Invoker is redundant"
                continue
            }
            val targetName = inferMemberName(method.name, invokerAnn, prefixes = listOf("call", "invoke"))
            val targetMethod = target.methods.firstOrNull {
                it.name == targetName &&
                    Type.getArgumentTypes(it.desc).contentEquals(argTypes) &&
                    Type.getReturnType(it.desc) == retType
            }
            if (targetMethod == null) {
                problems += "$whereM —— the @Invoker could not find the method $targetName" +
                    " (args ${argTypes.joinToString("") { it.descriptor }} returns ${retType.descriptor};" +
                    " the parameter and return types must match the target method exactly)"
                continue
            }
            if (targetMethod.access and Opcodes.ACC_STATIC != 0) {
                problems += "$whereM —— target method $targetName is a static method, invoker only supports instance methods" +
                    " (an interface's abstract method cannot express a synthesized static body)"
                continue
            }
            val concrete = (method.access and Opcodes.ACC_ABSTRACT.inv()) or Opcodes.ACC_SYNTHETIC
            val body = buildMethod(
                concrete, method.name, method.desc,
                1 + argTypes.sumOf { it.size }, 1 + argTypes.sumOf { it.size } + retType.size,
            ) {
                add(VarInsnNode(Opcodes.ALOAD, 0))
                var slot = 1
                for (t in argTypes) {
                    add(VarInsnNode(t.getOpcode(Opcodes.ILOAD), slot))
                    slot += t.size
                }
                // A private target method must go through INVOKESPECIAL (INVOKEVIRTUAL on a private method gives a VerifyError)
                val opcode = if (targetMethod.access and Opcodes.ACC_PRIVATE != 0) Opcodes.INVOKESPECIAL
                else Opcodes.INVOKEVIRTUAL
                add(MethodInsnNode(opcode, target.name, targetMethod.name, targetMethod.desc, false))
                add(InsnNode(retType.getOpcode(Opcodes.IRETURN)))
            }
            target.methods.add(body)
            invokers++
        }

        return Result(0, 0, 0, 0, 0, problems, accessors, invokers)
    }

    /** Nothing is self-referenced inside the interface, but the interface name must not linger in the synthesized body either -- this just collects the declared method into a MethodNode. */
    private fun buildMethod(
        access: Int,
        name: String,
        desc: String,
        maxLocals: Int,
        maxStack: Int,
        body: InsnListBuilder.() -> Unit,
    ): MethodNode {
        val node = MethodNode(Opcodes.ASM9, access, name, desc, null, null)
        val list = InsnListBuilder()
        list.body()
        node.instructions.add(list.list)
        node.maxLocals = maxLocals
        node.maxStack = maxStack
        return node
    }

    /** Minimal instruction collector: lets a synthesized body be written as straight-line "add one by one" code. */
    private class InsnListBuilder {
        val list = InsnList()
        fun add(insn: AbstractInsnNode) {
            list.add(insn)
        }
    }

    /**
     * Target member name: an explicit `value` wins; when left empty, strip the conventional prefix and
     * lowercase the first letter (`getProxy` → `proxy`, matching Mixin's prefix convention).
     */
    private fun inferMemberName(methodName: String, annotation: AnnotationNode, prefixes: List<String>): String {
        val explicit = annotationValue(annotation, "value") as? String
        if (!explicit.isNullOrEmpty()) return explicit
        for (prefix in prefixes) {
            if (methodName.length > prefix.length &&
                methodName.startsWith(prefix) &&
                methodName[prefix.length].isUpperCase()
            ) {
                return methodName[prefix.length].lowercaseChar() + methodName.substring(prefix.length + 1)
            }
        }
        return methodName
    }

    // ---------- self-reference rewriting (including stack frames) ----------

    /**
     * Rewrite every reference in [method] that "points at [self]" to point at [target], and apply the
     * rename tables to member names.
     *
     * Four kinds of targets: field instructions, method-call instructions, type instructions
     * (`NEW`/`CHECKCAST`/…, for the "construct my own instance" case), and [FrameNode] -- **frames must be
     * rewritten too**: object types in frames are internal names, and leaving them would make frames not
     * match the instructions, giving a `VerifyError` when the class is defined.
     */
    private fun reown(
        method: MethodNode,
        self: String,
        target: String,
        fieldRename: Map<String, String>,
        methodRename: Map<String, String>,
    ) {
        for (insn in method.instructions.toArray()) {
            when (insn) {
                is FieldInsnNode -> if (insn.owner == self) {
                    insn.owner = target
                    fieldRename[insn.name]?.let { insn.name = it }
                }

                is MethodInsnNode -> if (insn.owner == self) {
                    insn.owner = target
                    // Constructors/class initializers do not take part in renaming (they have fixed names)
                    if (insn.name != "<init>" && insn.name != "<clinit>") {
                        methodRename[insn.name]?.let { insn.name = it }
                    }
                }

                is TypeInsnNode -> if (insn.desc == self) insn.desc = target

                is FrameNode -> {
                    insn.local = insn.local?.map { if (it == self) target else it }
                    insn.stack = insn.stack?.map { if (it == self) target else it }
                }

                else -> Unit
            }
        }
        // catchType may also name the mixin itself (a mixin catching an exception it itself throws -- rare but legal)
        method.tryCatchBlocks?.forEach { if (it.type == self) it.type = target }
    }

    // ---------- constructor merging (instance initializers) ----------

    /**
     * Validate the mixin constructor, cut out the merge segment (everything after the `super()` delegate call,
     * before the trailing `RETURN`), and return the delegate call's index (`-1` = cannot be merged). It must
     * land in the **target's** constructor: a `final` instance field can only be assigned in the declaring
     * class's `<init>` — a hard JVM rule no reflection can bypass — so `@Shadow` final-field and `@Unique`
     * instance-field initializers must physically live there.
     * Restrictions (each reported as a problem, never silently dropped — see [isMergeableCtorInsn]): no jumps
     * or `switch`, no local writes, reads only slot 0 (`this` is the only slot meaning the same on both sides),
     * and no `RETURN`. Must be called **before re-pointing**: the `this(...)` delegate is only recognizable
     * while `INVOKESPECIAL <mixin>.<init>` has not yet been re-pointed to the target class.
     */
    private fun inspectConstructor(
        mixinCtor: MethodNode,
        mixinNode: ClassNode,
        where: String,
        problems: MutableList<String>,
    ): Int {
        val at = "$where —— ${mixinCtor.name}${mixinCtor.desc}"
        val insns = mixinCtor.instructions.toArray()

        val thisDelegation = insns.firstOrNull {
            it is MethodInsnNode && it.opcode == Opcodes.INVOKESPECIAL &&
                it.name == "<init>" && it.owner == mixinNode.name
        }
        if (thisDelegation != null) {
            problems += "$at delegates to another constructor with `this(...)`: OML only supports mixin constructors that delegate with `super()`" +
                " (the merged segment would run twice)"
            return -1
        }

        val delegate = insns.indexOfFirst {
            it is MethodInsnNode && it.opcode == Opcodes.INVOKESPECIAL &&
                it.name == "<init>" && it.owner == mixinNode.superName
        }
        if (delegate < 0) {
            problems += "$at could not find the delegate call to the superclass constructor" +
                " (`INVOKESPECIAL ${mixinNode.superName}.<init>…`) —— cannot cut out the merge segment"
            return -1
        }

        val tail = insns.indexOfLast { it.opcode == Opcodes.RETURN }
        if (tail < delegate) {
            problems += "$at could not find the trailing RETURN"
            return -1
        }

        insns.toList().subList(delegate + 1, tail).firstOrNull { !isMergeableCtorInsn(it) }?.let {
            problems += "$at's merge segment contains an instruction that cannot be merged: ${describeCtorInsn(it)}"
            return -1
        }
        return delegate
    }

    /**
     * Insert the merge segment into the target class's constructors.
     *
     * Merged into **every** target constructor whose delegate call is `super()` — the segment reads no
     * params, so the source constructor's descriptor is irrelevant, and any constructor that can
     * create an object must run the initialization. Constructors delegating with `this(...)` are
     * **skipped**: they reach the delegated one, and merging in would run the segment twice. The
     * segment is many-to-one, so a fresh clone goes per target constructor; labels, line numbers and
     * frames are dropped (no jump in the segment targets them), and it is stack-neutral, so inserting
     * at an empty-stack position cannot disturb the target's stack.
     * @param delegate the delegate-call index established by [inspectConstructor]
     * @return the number of target constructors actually merged into
     */
    private fun insertConstructor(
        mixinCtor: MethodNode,
        delegate: Int,
        target: ClassNode,
        where: String,
        problems: MutableList<String>,
    ): Int {
        val at = "$where —— ${mixinCtor.name}${mixinCtor.desc}"
        val insns = mixinCtor.instructions.toArray()
        val body = insns.toList().subList(delegate + 1, insns.indexOfLast { it.opcode == Opcodes.RETURN })
            .filter { it.opcode >= 0 }
        if (body.isEmpty()) return 0

        val injectable = target.methods.filter { it.name == "<init>" && delegatesToSuper(it, target) }
        if (injectable.isEmpty()) {
            problems += "$at has no target constructor to merge into: all of the target class's constructors delegate via `this(...)`," +
                " or it has no constructors at all"
            return 0
        }

        for (ctor in injectable) {
            val list = InsnList()
            body.forEach { list.add(it.clone(emptyMap())) }
            ctor.instructions.insert(initialiserPoint(ctor, target), list)
        }
        return injectable.size
    }

    /** Whether the target constructor delegates with `super()` (only this kind can accept the merge segment). */
    private fun delegatesToSuper(ctor: MethodNode, target: ClassNode): Boolean {
        val delegate = ctor.instructions.toArray().firstOrNull {
            it is MethodInsnNode && it.opcode == Opcodes.INVOKESPECIAL && it.name == "<init>"
        } as MethodInsnNode?
        return delegate != null && delegate.owner == target.superName
    }

    /**
     * The landing point of the merge segment: the position where the target class's own field initializers have
     * all run. A target constructor's layout is "delegate call → own field initializers → constructor body"
     * with no mechanically distinguishable marker between them. The segment often writes the target's own
     * fields (`@Shadow` final fields do), so inserting right after the delegate call would let the target's own
     * initializer overwrite them — hence: after the last `PUTFIELD` writing a target instance field. That
     * `PUTFIELD` may sit in a conditional branch, though; the criterion is that no jump before the candidate
     * point crosses over it, otherwise fall back to right after the delegate call (conservative position, but
     * guaranteed to run on every path). An exception before the candidate point means the object was never
     * fully constructed anyway, so exception handlers need no special care.
     */
    private fun initialiserPoint(ctor: MethodNode, target: ClassNode): AbstractInsnNode {
        val insns = ctor.instructions.toArray()
        val delegate = insns.indexOfFirst {
            it is MethodInsnNode && it.opcode == Opcodes.INVOKESPECIAL && it.name == "<init>" &&
                (it.owner == target.name || it.owner == target.superName)
        }
        if (delegate < 0) return ctor.instructions.first

        var point = delegate
        for (i in delegate + 1 until insns.size) {
            val insn = insns[i]
            if (insn is FieldInsnNode && insn.opcode == Opcodes.PUTFIELD && insn.owner == target.name) point = i
        }
        return if (point > delegate && !crossesForward(insns, point)) insns[point] else insns[delegate]
    }

    /** Whether a jump before index [point] carries control flow to **after** [point] (in which case it is not on every path). */
    private fun crossesForward(insns: Array<AbstractInsnNode>, point: Int): Boolean {
        for (i in 0 until point) {
            val targets: List<LabelNode> = when (val insn = insns[i]) {
                is JumpInsnNode -> listOf(insn.label)
                is TableSwitchInsnNode -> insn.labels + insn.dflt
                is LookupSwitchInsnNode -> insn.labels + insn.dflt
                else -> emptyList()
            }
            if (targets.any { insns.indexOf(it) > point }) return true
        }
        return false
    }

    /**
     * Whether an instruction may go into the merge segment.
     *
     * `ALOAD 0` (`this`) is the only usable local access: slot 0 is `this` in the target constructor too,
     * while the other slots are decided by the target's own descriptor, so their meaning does not line up
     * between the two sides. Labels/line numbers/stack frames serve only as carriers and are stripped by the
     * caller (see [mergeConstructor]).
     */
    private fun isMergeableCtorInsn(insn: AbstractInsnNode): Boolean {
        if (insn is LabelNode || insn is LineNumberNode || insn is FrameNode) return true
        val op = insn.opcode
        return when (op) {
            Opcodes.ALOAD -> (insn as VarInsnNode).`var` == 0
            in Opcodes.ILOAD..Opcodes.ALOAD -> false
            in Opcodes.ISTORE..Opcodes.ASTORE -> false
            in Opcodes.IFEQ..Opcodes.RET -> false
            Opcodes.TABLESWITCH, Opcodes.LOOKUPSWITCH -> false
            in Opcodes.IRETURN..Opcodes.RETURN -> false
            Opcodes.ATHROW -> false
            else -> true
        }
    }

    /** An instruction rejected by [isMergeableCtorInsn]: give the reason instead of only reporting "not supported". */
    private fun describeCtorInsn(insn: AbstractInsnNode): String = when (insn.opcode) {
        in Opcodes.ILOAD..Opcodes.ALOAD ->
            "reads local variable slot ${(insn as VarInsnNode).`var`}: only slot 0's `this` has the same meaning in the target constructor"

        in Opcodes.ISTORE..Opcodes.ASTORE ->
            "writes local variable slot ${(insn as VarInsnNode).`var`}: the target constructor's slot layout is unrelated to the mixin constructor"

        in Opcodes.IFEQ..Opcodes.RET, Opcodes.TABLESWITCH, Opcodes.LOOKUPSWITCH ->
            "jump/branch: write-back uses the frame-preserving mode, and the stack frame a branch target needs would not line up in the target constructor"

        in Opcodes.IRETURN..Opcodes.RETURN -> "RETURN: a constructor body has only one at the end"
        Opcodes.ATHROW -> "ATHROW"
        else -> "opcode 0x${Integer.toHexString(insn.opcode)}"
    }

    // ---------- <clinit> splicing ----------

    /**
     * Splice [mixinClinit]'s method body onto the **end** (before the `RETURN`) of the target `<clinit>`;
     * when the target has none, the mixin's copy is adopted as is.
     *
     * It must go at the end: static-field initialization order is semantically meaningful (a later
     * initializer can read a value assigned earlier), and inserting ahead of the target's initializers
     * would change that order.
     */
    private fun appendClinit(
        target: ClassNode,
        mixinClinit: MethodNode,
        where: String,
        problems: MutableList<String>,
    ) {
        versionClash(mixinClinit.instructions, target)?.let { clash ->
            problems += "$where —— the merged-in <clinit> uses $clash, while the target class ${target.name}'s class-file version is" +
                "${target.version} (Java ${target.version - 44}): write the static initialization in a form the target version can express"
        }
        val existing = target.methods.firstOrNull { it.name == "<clinit>" && it.desc == "()V" }
        if (existing == null) {
            target.methods.add(mixinClinit)
            return
        }
        val tail = existing.instructions.toArray().lastOrNull { it.opcode == Opcodes.RETURN }
        if (tail == null) {
            problems += "$where —— could not find a RETURN in the target class's <clinit>, so the mixin's static initialization cannot be spliced in" +
                " (if it cannot be spliced in, @Unique static field initializers would be lost; better to report an error than to stay silent)"
            return
        }
        val lastReturn = mixinClinit.instructions.toArray().lastOrNull { it.opcode == Opcodes.RETURN }
        for (insn in mixinClinit.instructions.toArray()) {
            if (insn === lastReturn) continue
            existing.instructions.insertBefore(tail, insn)
        }
    }

    // ---------- utility helpers ----------

    /**
     * Name resolution for `@Shadow`: first the own name, then the explicit `value` / `aliases` written in the
     * annotation.
     *
     * @param desc the expected descriptor -- **must match exactly** (a wrong type means it was written
     *   incorrectly, not something to paper over)
     * @return the name actually used in the target class; `null` = no matching member in the target class
     */
    private fun resolveShadow(
        ownName: String,
        shadow: AnnotationNode,
        desc: String,
        members: List<Pair<String, String>>,
    ): String? {
        val explicit = (annotationValue(shadow, "value") as? String)?.takeIf { it.isNotEmpty() }
        val aliases = when (val raw = annotationValue(shadow, "aliases")) {
            is List<*> -> raw.filterIsInstance<String>()
            is Array<*> -> raw.filterIsInstance<String>()
            else -> emptyList()
        }
        val wanted = listOfNotNull(ownName, explicit) + aliases
        return members.firstOrNull { it.first in wanted && it.second == desc }?.first
    }

    /** Diagnostic text: list the aliases as well, otherwise a "not found" looks like the aliases were omitted. */
    private fun shadowHint(shadow: AnnotationNode): String {
        val aliases = when (val raw = annotationValue(shadow, "aliases")) {
            is List<*> -> raw.filterIsInstance<String>()
            is Array<*> -> raw.filterIsInstance<String>()
            else -> emptyList()
        }
        return if (aliases.isEmpty()) "" else " (alternate names: ${aliases.joinToString(", ")})"
    }

    /** The field's final name: when it shares a name and descriptor with an existing target field, pick a non-conflicting one. */
    private fun uniqueFieldName(
        name: String,
        desc: String,
        target: ClassNode,
        pending: List<FieldNode>,
    ): String {
        val taken = { candidate: String ->
            target.fields.any { it.name == candidate } || pending.any { it.name == candidate }
        }
        if (!taken(name)) return name
        var n = 0
        while (taken($$"$$name$oml$$${n}")) n++
        return $$"$$name$oml$$$n"
    }

    /** The method's final name: when it shares a name and descriptor with an existing target method (or with one pending in this merge), pick a different one. */
    private fun uniqueMethodName(
        name: String,
        desc: String,
        target: ClassNode,
        pending: List<MethodNode>,
    ): String {
        val taken = { candidate: String ->
            target.methods.any { it.name == candidate && it.desc == desc } ||
                pending.any { it.name == candidate && it.desc == desc }
        }
        if (!taken(name)) return name
        var n = 0
        while (taken($$"$$name$oml$$$n")) n++
        return $$"$$name$oml$$$n"
    }

    /**
     * Whether `<init>` is just `super()` / `this()` + `RETURN`.
     *
     * Such constructors (the implicit default constructor, and secondary constructors that only delegate via
     * `this(...)`) carry no instance initialization to merge, so they do not count as a constructor-merge
     * source.
     */
    private fun isTrivialConstructor(method: MethodNode): Boolean {
        val real = method.instructions.toArray().filter {
            it.opcode >= 0 && it !is FrameNode && it !is LabelNode
        }
        val delegate = real.indexOfFirst {
            it is MethodInsnNode && it.name == "<init>" && it.opcode == Opcodes.INVOKESPECIAL
        }
        if (delegate < 0) return false
        val after = real.drop(delegate + 1)
        return after.size == 1 && after[0].opcode == Opcodes.RETURN
    }

    private fun annotationValue(node: AnnotationNode, name: String): Any? {
        val values = node.values ?: return null
        var i = 0
        while (i + 1 < values.size) {
            if (values[i] == name) return values[i + 1]
            i += 2
        }
        return null
    }
}
