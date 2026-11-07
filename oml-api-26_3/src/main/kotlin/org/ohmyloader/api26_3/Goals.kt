package org.ohmyloader.api26_3

import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.goal.Goal
import java.util.EnumSet

/**
 * What a behavior claims while it runs, so the game can make mutually exclusive behaviors take turns:
 * a running goal holding [OMLGoalFlag.MOVE] stops every other movement behavior of the mob until it
 * ends. The names are the game's own; a goal that claims nothing coordinates nothing.
 */
enum class OMLGoalFlag { MOVE, LOOK, JUMP, TARGET }

/**
 * A mod-declared mob behavior: the classic goal layer of the game's `Mob` AI. Handlers answer
 * [canUse] ("start now?") and the game runs [start] → [tick] → [stop] while [canContinueToUse]
 * holds — a goal that stops being usable hands its claimed [flags] back and lets other behaviors
 * run. This layer covers every ordinary mob; brain-driven mobs (villagers, piglins) are not
 * reachable through it — use the native-type escape hatch for those.
 *
 * Threading contract: called on the owning level's tick thread, inside the game's own AI pass —
 * like any event handler, do not block.
 */
interface OMLGoal {
    /** Whether this behavior wants to run now. Polled every tick while the mob is alive. */
    fun canUse(): Boolean

    /** Whether the running behavior may keep the mob. Defaults to [canUse]. */
    fun canContinueToUse(): Boolean = canUse()

    /** The behavior took the mob. */
    fun start() {}

    /** The behavior released the mob: it stopped being usable, was outranked, or the mob is going away. */
    fun stop() {}

    /** One tick of the running behavior. Runs every tick the behavior holds the mob. */
    fun tick() {}

    /** The control flags this behavior claims while it runs; nothing claimed by default. */
    val flags: Set<OMLGoalFlag> get() = emptySet()
}

/**
 * The game-side half of an [OMLGoal]: a `Goal` whose lifecycle is the interface's. The flags are
 * mapped once at construction — [OMLGoal.flags] is a declaration, not a negotiation — and the goal
 * ticks every tick it runs, which is what a mod author expects and one less vanilla cadence to know.
 */
internal class OMLGoalBridge(private val goal: OMLGoal) : Goal() {

    private val mappedFlags: EnumSet<Goal.Flag> = EnumSet.noneOf(Goal.Flag::class.java).apply {
        goal.flags.forEach { add(Goal.Flag.valueOf(it.name)) }
    }

    override fun canUse(): Boolean = goal.canUse()

    override fun canContinueToUse(): Boolean = goal.canContinueToUse()

    override fun start() = goal.start()

    override fun stop() = goal.stop()

    override fun tick() = goal.tick()

    override fun getFlags(): EnumSet<Goal.Flag> = mappedFlags

    override fun requiresUpdateEveryTick(): Boolean = true
}

/**
 * Adds [goal] to this mob's behavior selector at [priority] — lower numbers run first, and a running
 * goal keeps the mob until it stops being usable or a goal that outranks it needs one of its claimed
 * flags. The mob is this entity itself: the AI layer exists on `Mob` only, so a living entity that is
 * not one (armor stand) has no place to put a goal.
 *
 * The same [OMLGoal] instance can be given to several mobs — the bridge is per call, the handler
 * object is the mod's to share.
 */
fun Mob.addGoal(priority: Int, goal: OMLGoal) {
    goalSelector.addGoal(priority, OMLGoalBridge(goal))
}
