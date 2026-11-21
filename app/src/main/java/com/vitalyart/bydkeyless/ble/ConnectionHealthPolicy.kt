package com.vitalyart.bydkeyless.ble

import com.vitalyart.bydkeyless.model.BleConnectionState
import com.vitalyart.bydkeyless.model.VehicleProfile
import kotlin.math.min

object ConnectionHealthPolicy {
    const val READY_STALE_TIMEOUT_MS = 12_000L
    const val MAX_RECONNECT_DELAY_MS = 30_000L

    internal fun hasSameCredentials(current: VehicleProfile?, incoming: VehicleProfile): Boolean =
        current != null &&
            current.vin == incoming.vin &&
            current.macAddress.equals(incoming.macAddress, ignoreCase = true) &&
            current.digitalKey == incoming.digitalKey &&
            current.keyNumber == incoming.keyNumber &&
            current.vinRssi == incoming.vinRssi

    fun isReadyConnectionStale(state: BleConnectionState, now: Long, lastActivityAt: Long): Boolean =
        state == BleConnectionState.READY && lastActivityAt != Long.MIN_VALUE &&
            now - lastActivityAt >= READY_STALE_TIMEOUT_MS

    fun reconnectDelayMillis(attempt: Int): Long {
        val exponent = (attempt - 1).coerceIn(0, 4)
        return min(2_000L shl exponent, MAX_RECONNECT_DELAY_MS)
    }
}
