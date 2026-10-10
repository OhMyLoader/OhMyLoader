package org.ohmyloader.content

import org.ohmyloader.api.content.Furnace
import org.ohmyloader.api.content.OMLItemDeclaration
import kotlin.test.*

/**
 * The version-independent half of the two-phase content contract: collection order, declaration
 * payloads, and the "not materialized yet" guard on the handles. The materialization half is
 * version-specific and lives with the adapters (its 26.3 counterpart is exercised in game).
 */
class AbstractContentRegistryTest {

    /** Records which declarations arrived and returns fixed platform handles. */
    private class RecordingRegistry : AbstractContentRegistry() {
        val blocks = mutableListOf<BlockDecl>()
        val items = mutableListOf<ItemDecl>()
        var didMaterialize = false

        /** Queue sizes observed at materialization time (before the central drain clears them). */
        var blockQueueSizeAtMaterialize = -1
        var itemQueueSizeAtMaterialize = -1

        override fun doMaterialize() {
            didMaterialize = true
            blockQueueSizeAtMaterialize = collected.size
            itemQueueSizeAtMaterialize = collectedItems.size
            for (decl in collected) {
                blocks += decl
                materialized["${decl.namespace}:${decl.id}"] =
                    org.ohmyloader.api.content.OMLBlock("${decl.namespace}:${decl.id}") { "block-platform" }
            }
            for (decl in collectedItems) {
                items += decl
                materializedItems["${decl.namespace}:${decl.id}"] =
                    org.ohmyloader.api.content.OMLItem("${decl.namespace}:${decl.id}") { "item-platform" }
            }
        }
    }

    @Test
    fun `shaped crafting materializes into a datapack recipe json`() {
        val registry = RecordingRegistry()
        val facade = registry.forNamespace("mymod")

        facade.declareShapedCrafting(
            result = "ruby_block",
            pattern = listOf("RR", "RR"),
            key = mapOf('R' to "ruby"),
        )

        assertEquals(listOf("mymod_ruby_block"), registry.recipeIdsFor("mymod"))
        val json = registry.recipeJsonFor("mymod", "mymod_ruby_block")
            ?: fail("the crafting recipe must be served as datapack json")
        assertTrue("\"type\":\"minecraft:crafting_shaped\"" in json)
        assertTrue("\"pattern\":[\"RR\",\"RR\"]" in json)
        assertTrue("\"R\":\"mymod:ruby\"" in json, "bare ingredient ids must be qualified (26.3: plain-string keys)")
        assertTrue("\"result\":{\"id\":\"mymod:ruby_block\"}" in json)
    }

    @Test
    fun `shapeless crafting qualifies bare ingredient ids and keeps order`() {
        val registry = RecordingRegistry()
        val facade = registry.forNamespace("mymod")

        facade.declareShapelessCrafting(
            result = "minecraft:ruby",
            ingredients = listOf("ruby", "minecraft:stick"),
            count = 2,
        )

        // the result was namespaced (minecraft:ruby), so the datapack key follows it
        val json = registry.recipeJsonFor("mymod", "minecraft_ruby")
            ?: fail("the crafting recipe must be served as datapack json")
        assertTrue("\"type\":\"minecraft:crafting_shapeless\"" in json)
        assertTrue("\"ingredients\":[\"mymod:ruby\",\"minecraft:stick\"]" in json)
        assertTrue("\"count\":2" in json)
    }

    @Test
    fun `two recipes with the same result get distinct datapack keys`() {
        val registry = RecordingRegistry()
        val facade = registry.forNamespace("mymod")

        facade.declareShapelessCrafting("ruby", listOf("ruby"))
        facade.declareShapelessCrafting("ruby", listOf("minecraft:stick"))

        val keys = registry.recipeIdsFor("mymod")
        assertEquals(listOf("mymod_ruby", "mymod_ruby_2"), keys)
        assertTrue(
            registry.recipeJsonFor("mymod", "mymod_ruby_2")!!.contains("stick"),
            "the second recipe must keep its own ingredient list",
        )
    }

    @Test
    fun `malformed shaped patterns fail the declaration`() {
        val registry = RecordingRegistry()
        val facade = registry.forNamespace("mymod")

        assertFailsWith<IllegalStateException> {
            facade.declareShapedCrafting("x", listOf("RR", "R"), mapOf('R' to "ruby"))
        }.let { assertTrue("1-3 cells wide" in it.message!!) }

        assertFailsWith<IllegalStateException> {
            facade.declareShapedCrafting("x", listOf("RX"), mapOf('R' to "ruby"))
        }.let { assertTrue("'X' has no key entry" in it.message!!) }

        assertFailsWith<IllegalStateException> {
            facade.declareShapedCrafting("x", listOf("RRRR"), mapOf('R' to "ruby"))
        }.let { assertTrue("1-3 cells wide" in it.message!!) }

        assertFailsWith<IllegalStateException> {
            facade.declareShapelessCrafting("x", emptyList())
        }.let { assertTrue("1-9 ingredients" in it.message!!) }

        // nothing was queued by the failed declarations
        assertEquals(emptyList(), registry.recipeIdsFor("mymod"))
    }

    @Test
    fun `declarations are collected in order with their specs`() {
        val registry = RecordingRegistry()
        val facade = registry.forNamespace("mymod")

        val block = facade.declareBlock("fancy_block") {
            destroyTime = 1.5f
            requiresCorrectToolForDrops = true
        }
        val item = facade.declareItem("fancy_sword") {
            maxDamage = 250
            attackDamage = 5.0
            minesAndDrops("minecraft:stone", 4.0f)
        }

        registry.materializeAll()

        assertTrue(registry.didMaterialize)
        assertEquals(listOf("mymod:fancy_block"), registry.blocks.map { "${it.namespace}:${it.id}" })
        assertEquals(listOf("mymod:fancy_sword"), registry.items.map { "${it.namespace}:${it.id}" })

        // Specs survive the trip untouched
        val blockDecl = registry.blocks.single()
        assertEquals(1.5f, blockDecl.spec.destroyTime)
        assertTrue(blockDecl.spec.requiresCorrectToolForDrops)
        val itemDecl = registry.items.single()
        assertEquals(250, itemDecl.spec.maxDamage)
        assertEquals(5.0, itemDecl.spec.attackDamage)
        assertEquals(OMLItemDeclaration.ToolRuleKind.MINES_AND_DROPS, itemDecl.spec.toolRules.single().kind)

        // Handles resolve to the materialized platform object
        assertEquals("block-platform", block.platform)
        assertEquals("item-platform", item.platform)
    }

    @Test
    fun `behavior hooks ride the declaration to materialization`() {
        val registry = RecordingRegistry()
        val facade = registry.forNamespace("mymod")
        var stepped = 0
        var hit = 0

        facade.declareBlock("trap") {
            onStepOn { stepped++ }
            onHit { hit++ }
        }

        registry.materializeAll()
        val spec = registry.blocks.single().spec
        assertEquals(1, spec.stepOnHandlers.size)
        assertEquals(1, spec.hitHandlers.size)

        // the stored handlers are the mod's own lambdas, invoked with the adapter-built event
        spec.stepOnHandlers.single()(omlStepOnEvent())
        spec.hitHandlers.single()(omlBlockHitEvent())
        assertEquals(1, stepped)
        assertEquals(1, hit)
    }

    private fun omlStepOnEvent() =
        org.ohmyloader.api.content.OMLStepOnEvent(1, 2, 3, isClient = false, { "level" }, { "entity" })

    private fun omlBlockHitEvent() =
        org.ohmyloader.api.content.OMLBlockHitEvent(1, 2, 3, isClient = false, { "level" }, { "player" })

    @Test
    fun `ore declarations produce the vanilla-shaped datapack files`() {
        val registry = RecordingRegistry()
        val facade = registry.forNamespace("mymod")

        facade.declareBlock("tin_ore") {
            generateAsOre {
                veinSize = 8
                perChunk = 6
                minY = 16
                maxY = 64
            }
        }

        val decls = registry.oreGenDecls
        assertEquals(1, decls.size)
        val [ns, key, feature, placed] = listOf(
            decls[0].namespace, decls[0].key, decls[0].featureJson, decls[0].placedJson,
        )
        assertEquals("ore_tin_ore", key)
        assertTrue("\"type\":\"minecraft:ore\"" in feature)
        assertTrue("\"state\":\"mymod:tin_ore\"" in feature, "the vein must replace with the declared block")
        assertTrue("stone_ore_replaceables" in feature)
        assertTrue("\"feature\":\"mymod:ore_tin_ore\"" in placed)
        assertTrue("\"count\":6" in placed)
        assertTrue("\"absolute\":16" in placed && "\"absolute\":64" in placed)
        // the per-namespace listing the pack's directory enumeration serves
        assertEquals(listOf("ore_tin_ore"), registry.oreGenKeysFor("mymod"))
        assertEquals(null, registry.oreFeatureJsonFor("mymod", "ore_absent"))
    }

    @Test
    fun `block entity declaration rides to materialization with its tick handler`() {
        val registry = RecordingRegistry()
        val facade = registry.forNamespace("mymod")
        var ticks = 0

        facade.declareBlock("machine") {
            blockEntity {
                tick { ticks++ }
            }
        }
        assertFailsWith<IllegalStateException> {
            facade.declareBlock("machine2") { blockEntity { }; blockEntity { } }
        }.let { assertTrue("only be declared once" in it.message!!) }

        registry.materializeAll()
        val spec = registry.blocks.single().spec.blockEntityDeclaration
            ?: fail("the block entity declaration must survive the trip")
        spec.tickHandlers.single()(omlTickEvent())
        assertEquals(1, ticks)
    }

    private fun omlTickEvent() = org.ohmyloader.api.content.OMLBlockTickEvent(1, 2, 3, NoopBlockData) { "level" }

    private object NoopBlockData : org.ohmyloader.api.content.OMLBlockData {
        override fun getInt(key: String, default: Int) = default
        override fun putInt(key: String, value: Int) {}
        override fun getLong(key: String, default: Long) = default
        override fun putLong(key: String, value: Long) {}
        override fun getFloat(key: String, default: Float) = default
        override fun putFloat(key: String, value: Float) {}
        override fun getDouble(key: String, default: Double) = default
        override fun putDouble(key: String, value: Double) {}
        override fun getBoolean(key: String, default: Boolean) = default
        override fun putBoolean(key: String, value: Boolean) {}
        override fun getString(key: String): String? = null
        override fun putString(key: String, value: String) {}
        override fun hasKey(key: String) = false
        override fun remove(key: String) = false
        override fun keys(): Set<String> = emptySet()
    }

    @Test
    fun `declarations reach the subclass even when it materializes nothing`() {
        // The queue sizes at materialization time prove declarations reach the subclass before the
        // central drain; the drain itself ("consumed centrally") must not depend on the subclass.
        val registry = RecordingRegistry()
        registry.forNamespace("mymod").declareBlock("plain") { }
        registry.materializeAll()
        assertEquals(1, registry.blockQueueSizeAtMaterialize)
    }

    @Test
    fun `handles refuse to resolve before materialization`() {
        val registry = RecordingRegistry()
        val handle = registry.forNamespace("mymod").declareItem("early_item")

        val error = assertFailsWith<IllegalStateException> { handle.platform }
        assertEquals("item mymod:early_item has not been materialized", error.message)
    }

    @Test
    fun `furnace variants of one input get distinct recipe keys instead of shadowing each other`() {
        val registry = RecordingRegistry()
        val facade = registry.forNamespace("mymod")

        facade.declareSmelting(
            input = "raw_ruby",
            result = "ruby",
            furnace = Furnace.SMELTING,
            experience = 0.7,
            cookingTime = 200,
        )
        facade.declareSmelting(
            input = "raw_ruby",
            result = "ruby",
            furnace = Furnace.BLASTING,
            experience = 0.7,
            cookingTime = 100,
        )

        assertEquals(listOf("mymod_raw_ruby", "mymod_raw_ruby_2"), registry.recipeIdsFor("mymod"))
        val smelting = registry.recipeJsonFor("mymod", "mymod_raw_ruby")
            ?: fail("the first recipe must be served as datapack json")
        val blasting = registry.recipeJsonFor("mymod", "mymod_raw_ruby_2")
            ?: fail("the second recipe must not be swallowed by the key collision")
        assertTrue("\"type\":\"minecraft:smelting\"" in smelting)
        assertTrue("\"type\":\"minecraft:blasting\"" in blasting)
    }

    @Test
    fun `a second block-drop declaration for one block keeps the first instead of an orphan table`() {
        val registry = RecordingRegistry()
        val facade = registry.forNamespace("mymod")

        facade.declareBlockDrop(block = "ruby_ore", drop = "raw_ruby")
        facade.declareBlockDrop(block = "ruby_ore", drop = "ruby")

        assertEquals(listOf("mymod_ruby_ore"), registry.lootIdsFor("mymod"))
        val json = registry.lootJsonFor("mymod", "mymod_ruby_ore") ?: fail("the loot table must be served")
        assertTrue("\"mymod:raw_ruby\"" in json, "the first declaration wins")
    }

    @Test
    fun `a damage type is served as the datapack file the game's own codec reads`() {
        val registry = RecordingRegistry()
        val facade = registry.forNamespace("mymod")

        facade.declareDamageType(
            id = "melt",
            messageId = "mymod.melts",
            effects = "burning",
            exhaustion = 0.3f,
            deathMessageType = "intentional_game_design",
        )
        facade.declareDamageType(id = "plain", messageId = "mymod.plain")

        assertEquals(listOf("melt", "plain"), registry.damageTypeIdsFor("mymod"))
        val json = registry.damageTypeJsonFor("mymod", "melt") ?: fail("the damage type must be served")
        // scaling/effects/death_message_type are plain enum names, not ids — namespacing them fails the codec
        assertTrue("\"message_id\":\"mymod.melts\"" in json, json)
        assertTrue("\"scaling\":\"when_caused_by_living_non_player\"" in json, json)
        assertTrue("\"effects\":\"burning\"" in json, json)
        assertTrue("\"death_message_type\":\"intentional_game_design\"" in json, json)
        assertTrue("\"exhaustion\":0.3" in json, json)
        val defaults = registry.damageTypeJsonFor("mymod", "plain") ?: fail("the second file must be served")
        assertTrue(!defaults.contains("effects"), "an unset field is omitted, not defaulted: $defaults")
        assertTrue(!defaults.contains("death_message_type"), defaults)
    }

    @Test
    fun `an unknown damage enum name or a duplicate id is refused instead of reaching the datapack`() {
        val registry = RecordingRegistry()
        val facade = registry.forNamespace("mymod")

        facade.declareDamageType(id = "good", messageId = "m")
        facade.declareDamageType(id = "good", messageId = "dup")
        facade.declareDamageType(id = "bad_scaling", messageId = "m", scaling = "sometimes")
        facade.declareDamageType(id = "bad_effects", messageId = "m", effects = "sparkle")
        facade.declareDamageType(id = "bad_death", messageId = "m", deathMessageType = "dramatic")
        facade.declareDamageType(id = "Bad Name", messageId = "m")

        assertEquals(listOf("good"), registry.damageTypeIdsFor("mymod"))
        assertEquals(null, registry.damageTypeJsonFor("mymod", "bad_scaling"))
    }

    @Test
    fun `a clock and a timeline are served as the datapack files the game's own codecs read`() {
        val registry = RecordingRegistry()
        val facade = registry.forNamespace("mymod")

        facade.declareClock("tide")
        facade.declareTimeline("surge") {
            clock = "tide"
            periodTicks = 24000
            marker("low_tide", 0)
            marker("high_tide", 12000, inCommands = true)
        }
        facade.declareTimeline("quiet") { clock = "minecraft:overworld" }

        assertEquals(listOf("tide"), registry.clockIdsFor("mymod"))
        assertEquals("{}", registry.clockJsonFor("mymod", "tide"), "a clock declares nothing but its id")
        assertEquals(null, registry.clockJsonFor("mymod", "absent"))

        assertEquals(listOf("surge", "quiet"), registry.timelineIdsFor("mymod"))
        val surge = registry.timelineJsonFor("mymod", "surge") ?: fail("the timeline must be served")
        assertTrue("\"clock\":\"mymod:tide\"" in surge, surge)
        assertTrue("\"period_ticks\":24000" in surge, surge)
        assertTrue("\"mymod:low_tide\":0" in surge, "a marker outside /time set is the bare-number form: $surge")
        assertTrue("\"mymod:high_tide\":{\"show_in_commands\":true,\"ticks\":12000}" in surge, surge)
        val quiet = registry.timelineJsonFor("mymod", "quiet") ?: fail("the second file must be served")
        assertTrue("\"clock\":\"minecraft:overworld\"" in quiet, quiet)
        assertTrue("time_markers" !in quiet, "an unset field is omitted, not defaulted: $quiet")
        assertTrue("period_ticks" !in quiet, quiet)
    }

    @Test
    fun `a timeline the game would reject is refused at declaration`() {
        val registry = RecordingRegistry()
        val facade = registry.forNamespace("mymod")

        facade.declareClock("tide")
        facade.declareTimeline("good") {
            clock = "tide"
            periodTicks = 1000
            marker("early", 0)
            marker("late", 999)
        }
        facade.declareTimeline("no_clock") { marker("m", 0) }
        facade.declareTimeline("undeclared_clock") { clock = "nowhere" }
        facade.declareTimeline("bad_clock") { clock = "Bad Name" }
        facade.declareTimeline("zero_period") { clock = "tide"; periodTicks = 0; marker("m", 0) }
        facade.declareTimeline("past_period") { clock = "tide"; periodTicks = 1000; marker("m", 1000) }
        facade.declareTimeline("negative_tick") { clock = "tide"; marker("m", -1) }
        facade.declareTimeline("marker_twice") { clock = "tide"; marker("m", 0); marker("m", 10) }
        facade.declareTimeline("bad_marker") { clock = "tide"; marker("Bad Marker", 0) }
        // the game gives one marker to one timeline per clock, so a second claim never registers either file
        facade.declareTimeline("claims_early") { clock = "tide"; marker("early", 500) }
        facade.declareTimeline("good") { clock = "tide" }
        facade.declareClock("tide")
        facade.declareClock("Bad Name")

        assertEquals(listOf("good"), registry.timelineIdsFor("mymod"))
        assertEquals(listOf("tide"), registry.clockIdsFor("mymod"))
        assertEquals(null, registry.timelineJsonFor("mymod", "claims_early"))
    }

    @Test
    fun `a dimension type and its dimension are served as the files the game's codecs read`() {
        val registry = RecordingRegistry()
        val facade = registry.forNamespace("mymod")

        facade.declareClock("tide")
        facade.declareDimensionType("deep") {
            skyLight = false
            ceiling = true
            minY = 0
            height = 128
            logicalHeight = 128
            coordinateScale = 2.0
            ambientLight = 0.3f
            skybox = "none"
            fixedTime = true
            clock = "tide"
            timelines += "surge"
        }
        facade.declareDimension("deep_home") {
            type = "deep"
            layer("minecraft:bedrock", 1)
            layer("mymod:ruby_block", 3)
            biome = "minecraft:plains"
        }
        facade.declareDimension("noise_home") {
            type = "minecraft:overworld"
            generator = "noise"
            noiseSettings = "minecraft:overworld"
            biome = "minecraft:plains"
        }
        facade.declareDimensionType("plain")

        assertEquals(listOf("deep", "plain"), registry.dimensionTypeIdsFor("mymod"))
        val json = registry.dimensionTypeJsonFor("mymod", "deep") ?: fail("the type must be served")
        // the eleven fields the codec marks required are always there; the four it defaults are not guessed
        assertTrue("\"has_skylight\":false" in json, json)
        assertTrue("\"has_ceiling\":true" in json, json)
        assertTrue("\"min_y\":0" in json, json)
        assertTrue("\"height\":128" in json, json)
        assertTrue("\"logical_height\":128" in json, json)
        assertTrue("\"coordinate_scale\":2.0" in json, json)
        assertTrue("\"ambient_light\":0.3" in json, json)
        assertTrue("\"monster_spawn_light_level\":7" in json, json)
        assertTrue("\"infiniburn\":\"#minecraft:infiniburn_overworld\"" in json, json)
        assertTrue("\"skybox\":\"none\"" in json, json)
        assertTrue("\"has_fixed_time\":true" in json, json)
        assertTrue("\"default_clock\":\"mymod:tide\"" in json, json)
        assertTrue("\"timelines\":[\"mymod:surge\"]" in json, json)
        val defaults = registry.dimensionTypeJsonFor("mymod", "plain") ?: fail("the second type must be served")
        assertTrue("skybox" !in defaults, "an unset field is omitted, not defaulted: $defaults")
        assertTrue("default_clock" !in defaults, defaults)
        assertTrue("\"has_skylight\":true" in defaults, defaults)

        assertEquals(listOf("deep_home", "noise_home"), registry.dimensionIdsFor("mymod"))
        val flat = registry.dimensionJsonFor("mymod", "deep_home") ?: fail("the stem must be served")
        assertTrue("\"type\":\"mymod:deep\"" in flat, flat)
        assertTrue("\"type\":\"minecraft:flat\"" in flat, flat)
        assertTrue("\"block\":\"minecraft:bedrock\",\"height\":1" in flat, flat)
        assertTrue("\"block\":\"mymod:ruby_block\",\"height\":3" in flat, flat)
        assertTrue("\"biome\":\"minecraft:plains\"" in flat, flat)
        assertTrue("\"features\":false" in flat, flat)
        val noise = registry.dimensionJsonFor("mymod", "noise_home") ?: fail("the second stem must be served")
        assertTrue("\"type\":\"minecraft:noise\"" in noise, noise)
        assertTrue("\"settings\":\"minecraft:overworld\"" in noise, noise)
        assertTrue("\"type\":\"minecraft:fixed\",\"biome\":\"minecraft:plains\"" in noise, noise)
    }

    @Test
    fun `a dimension the game's codec would reject is refused at declaration`() {
        val registry = RecordingRegistry()
        val facade = registry.forNamespace("mymod")

        facade.declareClock("tide")
        facade.declareDimensionType("good") {
            minY = 0
            height = 128
        }
        facade.declareDimensionType("scale_out") { coordinateScale = 0.0 }
        facade.declareDimensionType("min_y_out") { minY = -2033 }
        facade.declareDimensionType("height_out") { height = 15 }
        facade.declareDimensionType("logical_out") { logicalHeight = 4065 }
        facade.declareDimensionType("light_out") { ambientLight = 1.5f }
        facade.declareDimensionType("monster_out") { monsterSpawnLightLevel = 16 }
        facade.declareDimensionType("no_tag") { infiniburn = "minecraft:infiniburn_overworld" }
        facade.declareDimensionType("bad_skybox") { skybox = "twilight" }
        facade.declareDimensionType("undeclared_clock") { clock = "nowhere" }
        facade.declareDimensionType("good") { height = 64 }

        assertEquals(listOf("good"), registry.dimensionTypeIdsFor("mymod"))

        facade.declareDimension("home") {
            type = "good"
            layer("minecraft:bedrock", 1)
        }
        facade.declareDimension("no_type") { layer("minecraft:bedrock", 1) }
        facade.declareDimension("bad_generator") { type = "good"; generator = "cellular" }
        facade.declareDimension("undeclared_type") { type = "nowhere" }
        facade.declareDimension("noise_incomplete") { type = "good"; generator = "noise" }
        facade.declareDimension("empty_layer") { type = "good"; layer("minecraft:bedrock", 0) }
        facade.declareDimension("home") { type = "good" }

        assertEquals(listOf("home"), registry.dimensionIdsFor("mymod"))
        val json = registry.dimensionJsonFor("mymod", "home") ?: fail("the served stem must exist")
        assertTrue("\"height\":1" in json, "the first declaration wins: $json")
    }

    /**
     * The tag file's identity is the **tag's** namespace plus the registry's datapack folder, which is what
     * lets a mod's pack write `data/minecraft/tags/damage_type/is_fire.json` — the file the game reads to put
     * this pack's damage type into the group that fire immunity obeys.
     */
    @Test
    fun `a tag is served as the file the game's tag loader reads`() {
        val registry = RecordingRegistry()
        val mine = registry.forNamespace("mymod")
        val other = registry.forNamespace("other")

        mine.declareTag("minecraft:damage_type", "minecraft:is_fire") {
            element("melt")
            tag("minecraft:is_projectile")
            element("minecraft:bad_name", required = false)
        }
        // the same tag, a different pack: one file per tag, so both packs' members have to land in it
        other.declareTag("minecraft:damage_type", "minecraft:is_fire") { element("another_type") }
        mine.declareTag("minecraft:item", "probe_group") { element("minecraft:diamond") }
        mine.declareTag("minecraft:worldgen/biome", "melts_in") { element("minecraft:plains") }
        mine.declareTag("minecraft:entity_type", "replaced_here") {
            replace = true
            element("minecraft:cow")
        }

        assertEquals(
            listOf("is_fire"),
            registry.tagIdsFor("minecraft", "damage_type"),
            "adding to a vanilla tag writes into the vanilla namespace",
        )
        val fire = registry.tagJsonAt("minecraft", "tags/damage_type/is_fire.json")
            ?: fail("the merged tag file must be served")
        assertTrue(
            "\"values\":[\"mymod:melt\",\"#minecraft:is_projectile\"," +
                "{\"id\":\"minecraft:bad_name\",\"required\":false},\"other:another_type\"]" in fire,
            "members merge across packs in declaration order, deduplicated by the game's own shape: $fire",
        )
        assertTrue("replace" !in fire, fire)
        assertEquals(listOf("probe_group"), registry.tagIdsFor("mymod", "item"))
        assertEquals(
            "{\"values\":[\"minecraft:diamond\"]}",
            registry.tagJsonAt("mymod", "tags/item/probe_group.json"),
        )
        assertEquals(
            "{\"values\":[\"minecraft:plains\"]}",
            registry.tagJsonAt("mymod", "tags/worldgen/biome/melts_in.json"),
            "a registry path with a slash is one folder, not two",
        )
        assertTrue(
            "replace\":true" in (registry.tagJsonAt("mymod", "tags/entity_type/replaced_here.json") ?: fail("x")),
        )
        assertEquals(
            listOf(
                "item/probe_group.json",
                "worldgen/biome/melts_in.json",
                "entity_type/replaced_here.json",
            ),
            registry.tagPathsFor("mymod"),
            "the bare `tags` root lists every file this pack filled under its own namespace",
        )
        assertEquals(null, registry.tagJsonAt("mymod", "tags/item/no_group.json"))
    }

    @Test
    fun `a tag the game cannot read is refused at declaration`() {
        val registry = RecordingRegistry()
        val mine = registry.forNamespace("mymod")
        val other = registry.forNamespace("other")

        mine.declareTag("minecraft:damage_type", "good") { element("melt") }
        mine.declareTag("Bad Name", "wrong_registry") { element("melt") }
        mine.declareTag("minecraft:damage_type", "Bad Tag") { element("melt") }
        mine.declareTag("minecraft:damage_type", "empty//path") { element("melt") }
        mine.declareTag("minecraft:damage_type", "no_members") { }
        mine.declareTag("minecraft:damage_type", "bad_member") { element("Bad Member") }
        mine.declareTag("minecraft:damage_type", "clears") { replace = true }
        // one file carries one answer: "join what is there" cannot share it with "this is all of it"
        other.declareTag("minecraft:damage_type", "mymod:good") { replace = true }
        other.declareTag("minecraft:damage_type", "mymod:good") { element("second") }

        assertEquals(
            listOf("damage_type/good.json", "damage_type/clears.json"),
            registry.tagPathsFor("mymod"),
            "only the well-formed declarations survive",
        )
        listOf("wrong_registry", "Bad Tag", "empty//path", "no_members", "bad_member").forEach { refused ->
            assertEquals(
                null,
                registry.tagJsonAt("mymod", "tags/damage_type/$refused.json"),
                "'$refused' must not reach the pack",
            )
        }
        assertEquals(
            "{\"values\":[\"mymod:melt\",\"other:second\"]}",
            registry.tagJsonAt("mymod", "tags/damage_type/good.json"),
            "the contradictory replacement was refused and the compatible one merged",
        )
        assertEquals(
            "{\"values\":[],\"replace\":true}",
            registry.tagJsonAt("mymod", "tags/damage_type/clears.json"),
            "an empty replacing tag is the game's own way to empty a tag",
        )
    }

    @Test
    fun `a reload drops only the pack it re-collects from a merged tag`() {
        val registry = RecordingRegistry()
        registry.forNamespace("mymod")
            .declareTag("minecraft:damage_type", "minecraft:is_fire") { element("code_type") }
        registry.forNamespace("pack", reloadable = true)
            .declareTag("minecraft:damage_type", "minecraft:is_fire") { element("toml_type") }
        val merged = registry.tagJsonAt("minecraft", "tags/damage_type/is_fire.json") ?: fail("merged")
        assertTrue("pack:toml_type" in merged && "mymod:code_type" in merged, merged)

        registry.clearDataDeclarations()

        val json = registry.tagJsonAt("minecraft", "tags/damage_type/is_fire.json")
            ?: fail("the code track's section keeps the file alive")
        assertEquals("{\"values\":[\"mymod:code_type\"]}", json)
        assertEquals(listOf("is_fire"), registry.tagIdsFor("minecraft", "damage_type"))

        registry.clearDataDeclarations()
        assertEquals(
            "{\"values\":[\"mymod:code_type\"]}",
            registry.tagJsonAt("minecraft", "tags/damage_type/is_fire.json"),
            "clearing twice costs the code track nothing",
        )
    }
}
