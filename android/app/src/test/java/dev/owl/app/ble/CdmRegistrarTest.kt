package dev.owl.app.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class CdmRegistrarTest {
    @Test
    fun fallbackNameUsesLowTwoAddressBytes() {
        // protocol §2: Owl-XXXX, XXXX = low 2 bytes of the device address
        assertEquals("Owl-3F2A", CdmRegistrar.fallbackName("C0:11:22:33:3F:2A"))
        assertEquals("Owl-3F2A", CdmRegistrar.fallbackName("c0:11:22:33:3f:2a"))
    }
}
