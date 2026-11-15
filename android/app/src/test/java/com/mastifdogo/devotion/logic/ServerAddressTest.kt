package com.mastifdogo.devotion.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerAddressTest {

    @Test
    fun normalize_addsSchemeAndDefaultPort() {
        assertEquals("http://192.168.1.50:8787", ServerAddress.normalize("192.168.1.50"))
        assertEquals("http://192.168.1.50:8787", ServerAddress.normalize("  192.168.1.50/  "))
        assertEquals("http://nas.local:8787", ServerAddress.normalize("nas.local"))
        assertEquals("http://nas.local:8787", ServerAddress.normalize("http://nas.local"))
        assertEquals("http://nas.local:8787", ServerAddress.normalize("HTTP://nas.local/"))
    }

    @Test
    fun normalize_keepsExplicitPortPathAndHttps() {
        assertEquals("http://192.168.1.50:9000", ServerAddress.normalize("192.168.1.50:9000"))
        assertEquals("http://192.168.1.50:8787", ServerAddress.normalize("http://192.168.1.50:8787/"))
        assertEquals("http://host:8787/devotion", ServerAddress.normalize("host/devotion/"))
        assertEquals("https://devotion.example.com", ServerAddress.normalize("https://devotion.example.com"))
        assertEquals("https://devotion.example.com:8443", ServerAddress.normalize("https://devotion.example.com:8443"))
        assertEquals("http://[fe80::1]:8787", ServerAddress.normalize("[fe80::1]"))
        assertEquals("http://[fe80::1]:9000", ServerAddress.normalize("[fe80::1]:9000"))
    }

    @Test
    fun normalize_emptyStaysEmpty() {
        assertEquals("", ServerAddress.normalize(""))
        assertEquals("", ServerAddress.normalize("   "))
        assertEquals("", ServerAddress.normalize("http://"))
    }

    @Test
    fun key_isStableAcrossSpellingsOfTheSameServer() {
        assertEquals("http___192_168_1_50_8787", ServerAddress.key("192.168.1.50"))
        assertEquals(ServerAddress.key("192.168.1.50"), ServerAddress.key("http://192.168.1.50:8787/"))
        assertEquals(ServerAddress.key("NAS.local"), ServerAddress.key("nas.local"))
        assertEquals("none", ServerAddress.key(""))
    }
}
