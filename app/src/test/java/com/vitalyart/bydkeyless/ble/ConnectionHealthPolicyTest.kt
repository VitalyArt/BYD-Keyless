package com.vitalyart.bydkeyless.ble

import com.vitalyart.bydkeyless.model.BleConnectionState
import com.vitalyart.bydkeyless.model.VehicleProfile
import org.junit.Assert.*
import org.junit.Test

class ConnectionHealthPolicyTest {
    @Test fun profileMetadataCanBeRefreshedWithoutChangingBleCredentials() {
        val profile = VehicleProfile("Model", "VIN", "00:00:00:00:00:AA", "key", 1L, null, setOf("1006"))
        val refreshed = profile.copy(
            modelName = "Refreshed model",
            macAddress = profile.macAddress?.lowercase(),
            keyValidTo = Long.MAX_VALUE,
            capabilities = setOf("1005", "1006"),
        )
        assertTrue(ConnectionHealthPolicy.hasSameCredentials(profile, refreshed))
        assertFalse(ConnectionHealthPolicy.hasSameCredentials(profile, refreshed.copy(vin = "OTHER")))
        assertFalse(ConnectionHealthPolicy.hasSameCredentials(profile, refreshed.copy(macAddress = "00:00:00:00:00:BB")))
        assertFalse(ConnectionHealthPolicy.hasSameCredentials(profile, refreshed.copy(digitalKey = "new")))
        assertFalse(ConnectionHealthPolicy.hasSameCredentials(profile, refreshed.copy(keyNumber = 2L)))
        assertFalse(ConnectionHealthPolicy.hasSameCredentials(profile, refreshed.copy(vinRssi = "new")))
    }
    @Test fun readyConnectionBecomesStaleWithoutBleActivity() {
        assertFalse(ConnectionHealthPolicy.isReadyConnectionStale(BleConnectionState.READY, 11_999, 0))
        assertTrue(ConnectionHealthPolicy.isReadyConnectionStale(BleConnectionState.READY, 12_000, 0))
        assertFalse(ConnectionHealthPolicy.isReadyConnectionStale(BleConnectionState.CONNECTING, 50_000, 0))
        assertFalse(ConnectionHealthPolicy.isReadyConnectionStale(BleConnectionState.READY, 50_000, Long.MIN_VALUE))
    }

    @Test fun reconnectBackoffIsBounded() {
        assertEquals(2_000L, ConnectionHealthPolicy.reconnectDelayMillis(1))
        assertEquals(4_000L, ConnectionHealthPolicy.reconnectDelayMillis(2))
        assertEquals(16_000L, ConnectionHealthPolicy.reconnectDelayMillis(4))
        assertEquals(30_000L, ConnectionHealthPolicy.reconnectDelayMillis(20))
    }
}
