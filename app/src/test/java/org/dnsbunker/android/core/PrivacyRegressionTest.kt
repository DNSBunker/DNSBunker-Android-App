package org.dnsbunker.android.core

import org.junit.Assert.*
import org.junit.Test

class PrivacyRegressionTest {
    @Test fun `private forward and reverse namespaces never use public DNS`() {
        listOf("printer.lan", "nas.home.arpa", "host.local", "localhost", "router", "a.onion",
            "1.2.168.192.in-addr.arpa", "1.16.172.in-addr.arpa", "1.31.172.in-addr.arpa",
            "1.0.0.10.in-addr.arpa", "1.254.169.in-addr.arpa", "1.64.100.in-addr.arpa",
            "1.0.0.127.in-addr.arpa", "1.d.f.ip6.arpa", "1.8.e.f.ip6.arpa")
            .forEach { assertTrue(it, DnsPrivacyPolicy.isLocal(it)) }
        listOf("example.com", "notlocal.com", "local.example.com", "1.8.8.8.in-addr.arpa",
            "1.32.172.in-addr.arpa", "1.63.100.in-addr.arpa", "1.e.f.ip6.arpa")
            .forEach { assertFalse(it, DnsPrivacyPolicy.isLocal(it)) }
        assertTrue(DnsPrivacyPolicy.isLocal("nas.CORP.EXAMPLE", "corp.example"))
        assertFalse(DnsPrivacyPolicy.isLocal("notcorp.example", "corp.example"))
    }
    @Test fun `unknown SSIDs and lockdown cannot disable protection`() {
        val rules = setOf("Home", "<unknown ssid>", "")
        listOf(null, "", "<unknown ssid>", "0x", "Other").forEach {
            assertFalse(WifiPolicy.exempt(it, rules, false))
            assertFalse(WifiPolicy.allowLocal(it, rules, true))
        }
        assertTrue(WifiPolicy.exempt("\"Home\"", rules, false))
        assertFalse(WifiPolicy.exempt("Home", rules, true))
        assertFalse(WifiPolicy.exempt("home", rules, false))
        assertFalse(WifiPolicy.allowLocal("Home", rules, false))
        assertTrue(WifiPolicy.allowLocal("Home", rules, true))
    }
    @Test fun `watchdog rejects SERVFAIL NXDOMAIN empty and truncated answers`() {
        val query = DnsWire.healthQuery()
        val failure = DnsWire.servfail(query)
        assertFalse(DnsWire.healthyResponse(query, failure))
        assertFalse(DnsWire.healthyResponse(query, failure.copyOf().apply { this[3] = 0x83.toByte() }))
        val empty = failure.copyOf().apply { this[3] = 0x80.toByte() }
        assertFalse(DnsWire.healthyResponse(query, empty))
        val valid = empty + byteArrayOf(0xc0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 93, 184.toByte(), 216.toByte(), 34)
        valid[7] = 1
        assertTrue(DnsWire.healthyResponse(query, valid))
        assertFalse(DnsWire.healthyResponse(query, valid.copyOf().apply { this[2] = 0x83.toByte() }))
        assertFalse(DnsWire.healthyResponse(query, valid.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }))
        assertFalse(DnsWire.healthyResponse(query, valid.copyOf(valid.size - 1)))
    }
    @Test fun `incoming responses opcodes malformed counts and ambiguous labels are rejected`() {
        val query = DnsWire.healthQuery()
        assertTrue(DnsWire.validQuery(query))
        assertTrue(DnsWire.validQuery(DnsWire.pad(query)))
        assertFalse(DnsWire.validQuery(DnsWire.servfail(query)))
        for (index in listOf(4, 6, 8)) {
            assertFalse(DnsWire.validQuery(query.copyOf().apply { this[index] = 1 }))
        }
        for (value in listOf(8, 0x80)) assertFalse(DnsWire.validQuery(query.copyOf().apply { this[2] = value.toByte() }))
        for (value in listOf(0x80, 46, 92, 0)) assertFalse(DnsWire.validQuery(query.copyOf().apply { this[13] = value.toByte() }))
        assertFalse(DnsWire.validQuery(query + byteArrayOf(0)))
    }
    @Test fun `deterministic malformed wire corpus never crashes public validation`() {
        val random = java.util.Random(20261123)
        repeat(10000) {
            val data = ByteArray(random.nextInt(600)); random.nextBytes(data)
            DnsWire.validQuery(data)
            DnsWire.validResponse(DnsWire.healthQuery(), data)
            DnsWire.healthyResponse(DnsWire.healthQuery(), data)
            DnsWire.hasBlockingEde(data)
        }
    }
}
