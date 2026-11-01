package dev.owl.app.ble

import android.bluetooth.BluetoothDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BondingTest {
    @Test
    fun bondStateBroadcastOutcome() {
        assertNull("still pairing", BondStates.outcome(BluetoothDevice.BOND_BONDING))
        assertEquals(BondStates.Outcome.BONDED, BondStates.outcome(BluetoothDevice.BOND_BONDED))
        // The pendant refused (window closed) or the user cancelled the dialog.
        assertEquals(BondStates.Outcome.FAILED, BondStates.outcome(BluetoothDevice.BOND_NONE))
    }

    @Test
    fun authFailuresMeanNotBonded() {
        // ATT Insufficient Authentication / Encryption, Android's GATT_AUTH_FAIL after its own retry
        for (s in listOf(5, 15, 137)) assertTrue("status $s", BleTransport.isAuthFailure(s))
        for (s in listOf(0, 1, 3, 133, 257)) assertFalse("status $s", BleTransport.isAuthFailure(s))
        // HCI disconnect reasons: Authentication Failure (pendant's 60 s timeout), PIN or Key Missing
        assertTrue(BleTransport.isAuthDisconnect(5))
        assertTrue(BleTransport.isAuthDisconnect(6))
        assertFalse(BleTransport.isAuthDisconnect(8)) // supervision timeout = out of range
        assertFalse(BleTransport.isAuthDisconnect(19)) // remote user terminated
        // Android 16+ drops the link itself on encryption failure: 0x16 alone is not "not bonded"
        assertFalse(BleTransport.isAuthDisconnect(BleTransport.HCI_LOCAL_HOST_TERMINATED))
    }

    @Test
    fun encryptionChangeBroadcastMeansKeyRefused() {
        // Real controller: PIN or Key Missing. netsim: Authentication Failure. Encryption Mode Not Acceptable.
        for (s in listOf(0x06, 0x05, 0x25)) assertTrue("status $s", BleTransport.isKeyRefused(s, encryptionEnabled = false))
        // Success, or a transient failure (LL response timeout, connection timeout) is not a key problem
        assertFalse(BleTransport.isKeyRefused(0x00, encryptionEnabled = true))
        assertFalse(BleTransport.isKeyRefused(0x00, encryptionEnabled = false))
        assertFalse(BleTransport.isKeyRefused(0x22, encryptionEnabled = false))
        assertFalse(BleTransport.isKeyRefused(0x08, encryptionEnabled = false))
    }
}
