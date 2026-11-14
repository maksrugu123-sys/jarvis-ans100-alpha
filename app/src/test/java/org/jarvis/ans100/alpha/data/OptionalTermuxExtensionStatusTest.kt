package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Test

class OptionalTermuxExtensionStatusTest {
    @Test
    fun missingTermuxAlwaysResolvesToStandaloneMode() {
        assertEquals(
            OptionalTermuxExtensionStatus.NOT_INSTALLED,
            resolveOptionalTermuxExtensionStatus(
                installed = false,
                hasToken = true,
                connected = true,
            ),
        )
    }

    @Test
    fun installedTermuxWithoutTokenNeedsPairing() {
        assertEquals(
            OptionalTermuxExtensionStatus.NEEDS_TOKEN,
            resolveOptionalTermuxExtensionStatus(
                installed = true,
                hasToken = false,
                connected = false,
            ),
        )
    }

    @Test
    fun savedTokenMeansExtensionCanBeConnectedButIsNotYetConnected() {
        assertEquals(
            OptionalTermuxExtensionStatus.READY_TO_CONNECT,
            resolveOptionalTermuxExtensionStatus(
                installed = true,
                hasToken = true,
                connected = false,
            ),
        )
    }

    @Test
    fun missingCompanionCannotBeUsedForSignalProcessing() {
        assertEquals(
            false,
            shouldUseTermuxSignal(
                installed = false,
                connected = true,
                hasToken = true,
                endpointAvailable = true,
            ),
        )
    }

    @Test
    fun disconnectedOrUnpairedCompanionCannotBeUsedForSignalProcessing() {
        assertEquals(
            false,
            shouldUseTermuxSignal(
                installed = true,
                connected = false,
                hasToken = true,
                endpointAvailable = null,
            ),
        )
        assertEquals(
            false,
            shouldUseTermuxSignal(
                installed = true,
                connected = true,
                hasToken = false,
                endpointAvailable = null,
            ),
        )
    }

    @Test
    fun failedOptionalSignalEndpointFallsBackToInProcessRuntime() {
        assertEquals(
            false,
            shouldUseTermuxSignal(
                installed = true,
                connected = true,
                hasToken = true,
                endpointAvailable = false,
            ),
        )
    }

    @Test
    fun readyOptionalSignalEndpointMayBeUsed() {
        assertEquals(
            true,
            shouldUseTermuxSignal(
                installed = true,
                connected = true,
                hasToken = true,
                endpointAvailable = null,
            ),
        )
        assertEquals(
            true,
            shouldUseTermuxSignal(
                installed = true,
                connected = true,
                hasToken = true,
                endpointAvailable = true,
            ),
        )
    }

    @Test
    fun onlyInstalledAndAuthenticatedConnectionIsReportedConnected() {
        assertEquals(
            OptionalTermuxExtensionStatus.CONNECTED,
            resolveOptionalTermuxExtensionStatus(
                installed = true,
                hasToken = true,
                connected = true,
            ),
        )
    }
}
