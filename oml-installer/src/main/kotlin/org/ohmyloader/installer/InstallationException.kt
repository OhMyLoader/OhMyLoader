package org.ohmyloader.installer

/**
 * Every failure that a *user* can act on, or that must be shown to them as a dialog.
 *
 * Thrown instead of calling `exitProcess`: `exitProcess` kills the JVM from wherever it is called, so
 * in the GUI a failed install would terminate the process without a dialog, usually before the Swing
 * log area had even repainted — and it is not a `Throwable` a caller can catch. Throwing makes the
 * failure a value that travels up to a place that has a UI, and lets each front end decide how to
 * present it: the GUI shows a modal dialog and keeps the console log, the CLI prints it and exits
 * non-zero, the Gradle task fails the build.
 *
 * [message] must be written for the person running the installer (actionable, in Chinese), not for us.
 */
class InstallationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
