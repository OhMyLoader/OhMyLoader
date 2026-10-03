package org.ohmyloader.api

/**
 * The one logging door every OML module writes through — deliberately not a framework (5.3 in
 * docs/开发计划.md): the loader shares stdout with the game's own logger, so the surface stays
 * three functions and a tag, and every line carries exactly one recognizable prefix.
 *
 * Line shapes (the contract CI's log judgment relies on):
 *
 *  - [info]  → stdout: `[<tag>] <message>`
 *  - [warn]  → stderr: `[<tag>] WARN: <message>`
 *  - [error] → stderr: `[<tag>] ERROR: <message>`, followed by the throwable's stack trace
 *
 * "No OML error line in the log" is therefore the regex `\[OML[A-Za-z-]*\] (WARN|ERROR):` — any
 * new diagnostic must go through here or that judgment silently stops covering it.
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
