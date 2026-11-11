package com.fand1l.radioguard.radio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioTetheringTest {

    @Test
    fun `no interfaces means no hotspot`() {
        assertFalse(RadioReader.hasRadioTethering(emptyList()))
    }

    @Test
    fun `wired tethering is not a radio`() {
        assertFalse(RadioReader.hasRadioTethering(listOf("rndis0", "ncm0", "usb0", "eth0")))
    }

    @Test
    fun `wifi and bluetooth tethering are radios`() {
        assertTrue(RadioReader.hasRadioTethering(listOf("wlan1")))
        assertTrue(RadioReader.hasRadioTethering(listOf("rndis0", "ap_br_wlan2")))
        assertTrue(RadioReader.hasRadioTethering(listOf("bt-pan")))
    }

    @Test
    fun `unknown interface is treated as a radio`() {
        assertTrue(RadioReader.hasRadioTethering(listOf("something0")))
    }
}
