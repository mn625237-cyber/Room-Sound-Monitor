package com.roomsoundmonitor.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventHistoryPolicyTest {
    @Test
    fun confirmedAlertIsPersistedEvenWithinDeduplicationWindow() {
        assertTrue(
            shouldPersistEvent(
                isAlert = true,
                nameChanged = false,
                elapsedSinceLastLog = 1_000L
            )
        )
    }

    @Test
    fun unchangedOrdinaryEventIsDeduplicatedWithinFiveSeconds() {
        assertFalse(
            shouldPersistEvent(
                isAlert = false,
                nameChanged = false,
                elapsedSinceLastLog = 4_999L
            )
        )
    }

    @Test
    fun changedOrExpiredOrdinaryEventIsPersisted() {
        assertTrue(shouldPersistEvent(false, nameChanged = true, elapsedSinceLastLog = 0L))
        assertTrue(shouldPersistEvent(false, nameChanged = false, elapsedSinceLastLog = 5_000L))
    }
}
