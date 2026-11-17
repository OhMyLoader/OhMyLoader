package org.ohmyloader.api.game.event

import org.ohmyloader.api.event.Event
import org.ohmyloader.api.event.EventDefinition

/**
 * A soft reboot has finished putting this session's player back into the world: the save was opened
 * from the handover snapshot and the player's position, view angle and game mode are what the
 * retiring session recorded. Fired at the restore itself — one or two ticks before the red
 * transition lifts — so a mod's refresh lands before the player sees the world again.
 *
 * <p>[outcome] is the restore's one-line result — `restored=1`, or a `skipped=`/`failed=` reason a
 * handler may still want to read: even a failed restore leaves **new** world and player objects
 * behind, so a mod's in-memory caches and entity references are stale either way and this is the one
 * moment to rebuild them. [summary] is the snapshot the session was restoring, log-safe.
 *
 * <p>Fired on the client thread, exactly once per session, and only where a handover happened: a
 * session that launched normally (no snapshot) never fires it. It replaces what the masked
 * [org.ohmyloader.api.game.event.Events.PLAYER_JOINED]/[org.ohmyloader.api.game.event.Events.PLAYER_LEFT]
 * pair would otherwise have said during the window — the vanilla join and leave announcements and
 * their OML event dispatches are suppressed for the length of the reboot, so downstream mods see one
 * `RESUMED` instead of a leave-then-join pair that was never real.
 */
class OMLSessionResumedEvent(
    val outcome: String,
    val summary: String,
) : Event()

/**
 * Session-level lifecycle for in-process soft reboots — the events a mod needs *instead of* the
 * leave/join pair the underlying disconnect-reconnect would have produced. See
 * [OMLSessionResumedEvent] for the contract.
 */
object OMLSessionEvents {
    /**
     * The player is back in the world after a soft reboot: caches and local references that
     * survived the session swap are safe to refresh here.
     */
    val RESUMED = EventDefinition<OMLSessionResumedEvent>("oml_session_resumed")
}
