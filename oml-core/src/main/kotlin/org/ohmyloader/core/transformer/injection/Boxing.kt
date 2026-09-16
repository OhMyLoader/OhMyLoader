package org.ohmyloader.core.transformer.injection

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/** A JVM member reference (owner internal name + name + descriptor). */
internal data class MemberRef(val owner: String, val name: String, val desc: String)

/**
 * The **single** place where boxing / unboxing / local-variable access opcodes are mapped.
 *
 * Both the injection engine (the `InsnList` path) and the runtime bridge generator (the `MethodVisitor` path)
 * need these; maintaining two copies would inevitably drift, so they are centralized here and shared by both.
 */
internal object Boxing {

    /**
     * Local-variable load opcode.
     *
     * On the JVM stack `boolean`/`byte`/`char`/`short` are all carried as int, so they use `ILOAD`;
     * arrays and references both use `ALOAD`; `long`/`double` take two slots and have dedicated opcodes.
     */
    fun loadOpcode(type: Type): Int = when (type.sort) {
        Type.LONG -> Opcodes.LLOAD
        Type.DOUBLE -> Opcodes.DLOAD
        Type.FLOAT -> Opcodes.FLOAD
        Type.INT, Type.SHORT, Type.BYTE, Type.CHAR, Type.BOOLEAN -> Opcodes.ILOAD
        else -> Opcodes.ALOAD
    }

    /** Local-variable store opcode (one-to-one with [loadOpcode]). */
    fun storeOpcode(type: Type): Int = when (type.sort) {
        Type.LONG -> Opcodes.LSTORE
        Type.DOUBLE -> Opcodes.DSTORE
        Type.FLOAT -> Opcodes.FSTORE
        Type.INT, Type.SHORT, Type.BYTE, Type.CHAR, Type.BOOLEAN -> Opcodes.ISTORE
        else -> Opcodes.ASTORE
    }

    /**
     * Boxing call `Box.valueOf(T)`; returns null for reference types (already in the object domain).
     */
    fun box(type: Type): MemberRef? = when (type.sort) {
        Type.BOOLEAN -> MemberRef("java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;")
        Type.BYTE -> MemberRef("java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;")
        Type.CHAR -> MemberRef("java/lang/Character", "valueOf", "(C)Ljava/lang/Character;")
        Type.SHORT -> MemberRef("java/lang/Short", "valueOf", "(S)Ljava/lang/Short;")
        Type.INT -> MemberRef("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
        Type.FLOAT -> MemberRef("java/lang/Float", "valueOf", "(F)Ljava/lang/Float;")
        Type.LONG -> MemberRef("java/lang/Long", "valueOf", "(J)Ljava/lang/Long;")
        Type.DOUBLE -> MemberRef("java/lang/Double", "valueOf", "(D)Ljava/lang/Double;")
        else -> null
    }

    /**
     * Unboxing call `((Box) v).xValue()`; returns null for reference types — the caller only needs to emit `CHECKCAST`.
     */
    fun unbox(type: Type): MemberRef? = when (type.sort) {
        Type.BOOLEAN -> MemberRef("java/lang/Boolean", "booleanValue", "()Z")
        Type.BYTE -> MemberRef("java/lang/Byte", "byteValue", "()B")
        Type.CHAR -> MemberRef("java/lang/Character", "charValue", "()C")
        Type.SHORT -> MemberRef("java/lang/Short", "shortValue", "()S")
        Type.INT -> MemberRef("java/lang/Integer", "intValue", "()I")
        Type.FLOAT -> MemberRef("java/lang/Float", "floatValue", "()F")
        Type.LONG -> MemberRef("java/lang/Long", "longValue", "()J")
        Type.DOUBLE -> MemberRef("java/lang/Double", "doubleValue", "()D")
        else -> null
    }
}
