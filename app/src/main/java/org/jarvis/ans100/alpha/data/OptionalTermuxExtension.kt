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
 * Termux is preferred only after a successful bridge connection and a working
 * signal endpoint. A false endpoint state routes the request to the in-app runtime.
 */
fun shouldUseTermuxSignal(
    installed: Boolean,
    connected: Boolean,
    hasToken: Boolean,
    endpointAvailable: Boolean?,
): Boolean = installed && connected && hasToken && endpointAvailable != false
