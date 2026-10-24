package org.ohmyloader.api

/**
 * The one logging door every OML module writes through — deliberately not a framework: the loader
 * shares stdout with the game's own logger, so the surface stays three functions and a tag, and
 * every line carries exactly one recognizable prefix.
 *
 * Shapes: [info] stdout `[<tag>] <message>`; [warn] stderr `[<tag>] WARN: …`; [error] stderr
 * `[<tag>] ERROR: …` plus the stack trace. Tags are free-form and mostly not `OML`-prefixed
 * (`Content`, `Network`, `Commands` — the module doing the talking).
 *
 * What the gates actually check is the E2E verdict line (`[OML-E2E] …`), not these lines: WARN and
 * ERROR are part of normal operation (a skipped biome merge, the scheduler's deliberate-throw probe),
 * so "no error line anywhere" is not a gate property and must not be written as one. Diagnostics go
 * through here so humans — and the `-Doml.log.file` mirror — see one consistent shape.
 */
object OmlLog {

    /** Normal progress narration (startup steps, hook hits, materialization summaries). */
    fun info(tag: String, message: String) {
        println("[$tag] $message")
    }

    /** Something the user is allowed to be in but should read (a skipped mod, a missing directory). */
    fun warn(tag: String, message: String) {
        System.err.println("[$tag] WARN: $message")
    }

    /** A failure OML recovers from or aborts on; the stack trace belongs to the line, always. */
    fun error(tag: String, message: String, t: Throwable? = null) {
        System.err.println("[$tag] ERROR: $message")
        t?.printStackTrace()
    }
}
