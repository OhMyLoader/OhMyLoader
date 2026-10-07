package org.ohmyloader.api.world

/**
 * A block together with its property values, addressed by the names the game gives them (`facing`,
 * `powered`, `age`). Values are strings for the same reason ids are: the property classes and their
 * value types are version-internal, while these names are what the game writes into blockstate JSON.
 */
interface OMLBlockState {
    /** The underlying game `BlockState`. */
    val platform: Any

    /** Registry id of the block this state belongs to, e.g. `minecraft:chest`. */
    val blockId: String

    /** Every property the block defines, with the value this state carries. */
    val properties: Map<String, String>

    /** The value of [name], or null when this block has no such property. */
    fun value(name: String): String?

    /**
     * This state with [name] set to [value]; null when the property is not one this block has or [value]
     * is not one of its legal values. The game's own state table is what rejects the combination, so a
     * name that is legal but produces an impossible state answers null too.
     */
    fun with(name: String, value: String): OMLBlockState?
}
