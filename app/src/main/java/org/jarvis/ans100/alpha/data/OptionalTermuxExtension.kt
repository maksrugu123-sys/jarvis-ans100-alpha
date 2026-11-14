package org.jarvis.ans100.alpha.data

/**
 * UI-facing state of the optional Termux companion. It never gates local app features.
 */
enum class OptionalTermuxExtensionStatus {
    NOT_INSTALLED,
    NEEDS_TOKEN,
    READY_TO_CONNECT,
    CONNECTED,
}

fun resolveOptionalTermuxExtensionStatus(
    installed: Boolean,
    hasToken: Boolean,
    connected: Boolean,
): OptionalTermuxExtensionStatus = when {
    !installed -> OptionalTermuxExtensionStatus.NOT_INSTALLED
    connected && hasToken -> OptionalTermuxExtensionStatus.CONNECTED
    hasToken -> OptionalTermuxExtensionStatus.READY_TO_CONNECT
    else -> OptionalTermuxExtensionStatus.NEEDS_TOKEN
}

/**
 * The Termux signal endpoint is an optimization only; null means not yet checked.
 */
fun shouldUseTermuxSignal(
    installed: Boolean,
    connected: Boolean,
    hasToken: Boolean,
    endpointAvailable: Boolean?,
): Boolean = installed && connected && hasToken && endpointAvailable != false

/**
 * The signal pre-pass is optional. A failure must not stop the user conversation.
 * Exception is contained at this narrow boundary; cancellation/errors outside the
 * ordinary Exception hierarchy are not swallowed.
 */
fun <T> runOptionalSignalPass(pass: () -> T): T? = try {
    pass()
} catch (_: Exception) {
    null
}
