package com.roomsoundmonitor.app

/**
 * Decides whether a classified event needs a history record.
 * Confirmed alerts always win over ordinary history deduplication.
 */
internal fun shouldPersistEvent(
    isAlert: Boolean,
    nameChanged: Boolean,
    elapsedSinceLastLog: Long
): Boolean = isAlert || nameChanged || elapsedSinceLastLog >= 5_000L
