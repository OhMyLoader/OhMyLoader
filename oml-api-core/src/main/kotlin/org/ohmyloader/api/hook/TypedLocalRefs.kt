package org.ohmyloader.api.hook

/*
 * Primitive-specialized LocalRef variants (aligned with MixinExtras' LocalIntRef family). Declaring an
 * `@Local` parameter as one of these keeps the read, the handler's access and the write-back unboxed;
 * the engine creates the matching implementation directly from the primitive local.
 */

interface LocalBooleanRef {
    var value: Boolean
}

class LocalBooleanRefImpl(initial: Boolean) : LocalBooleanRef {
    override var value: Boolean = initial
}

interface LocalByteRef {
    var value: Byte
}

class LocalByteRefImpl(initial: Byte) : LocalByteRef {
    override var value: Byte = initial
}

interface LocalCharRef {
    var value: Char
}

class LocalCharRefImpl(initial: Char) : LocalCharRef {
    override var value: Char = initial
}

interface LocalShortRef {
    var value: Short
}

class LocalShortRefImpl(initial: Short) : LocalShortRef {
    override var value: Short = initial
}

interface LocalIntRef {
    var value: Int
}

class LocalIntRefImpl(initial: Int) : LocalIntRef {
    override var value: Int = initial
}

interface LocalLongRef {
    var value: Long
}

class LocalLongRefImpl(initial: Long) : LocalLongRef {
    override var value: Long = initial
}

interface LocalFloatRef {
    var value: Float
}

class LocalFloatRefImpl(initial: Float) : LocalFloatRef {
    override var value: Float = initial
}

interface LocalDoubleRef {
    var value: Double
}

class LocalDoubleRefImpl(initial: Double) : LocalDoubleRef {
    override var value: Double = initial
}