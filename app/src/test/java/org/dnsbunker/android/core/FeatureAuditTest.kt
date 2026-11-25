package org.dnsbunker.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class FeatureAuditTest {
    private fun ip(text: String) = InetAddress.getByName(text)

    @Test fun `only public unicast server addresses are accepted`() {
        listOf("185.250.250.61", "2a0a:51c1:a:ea::", "1.1.1.1").forEach { assertTrue(it, DnsEndpoint.usable(ip(it))) }
        listOf("10.0.0.1", "192.168.1.1", "172.16.0.1", "127.0.0.1", "100.64.0.1", "198.18.0.1", "169.254.1.1", "0.0.0.0",
            "224.0.0.1", "fd77:db4b::1", "fe80::1", "::1", "64:ff9b::b9fa:fa3d", "2001:db8::1").forEach {
            assertFalse(it, DnsEndpoint.usable(ip(it)))
        }
        assertNull(DnsEndpoint.literal("dnsbunker.org"))
        assertNull(DnsEndpoint.literal("10.0.0.1"))
        assertEquals("185.250.250.61", DnsEndpoint.literal(" 185.250.250.61 ")?.hostAddress)
    }

    @Test fun `address selection keeps at most two per family and interleaves them`() {
        val picked = DnsEndpoint.select(listOf(ip("1.1.1.1"), ip("1.0.0.1"), ip("9.9.9.9"), ip("2606:4700::1111"), ip("10.0.0.1"), ip("1.1.1.1")))
        assertEquals(listOf("1.1.1.1", "2606:4700:0:0:0:0:0:1111", "1.0.0.1"), picked.map { it.hostAddress })
    }

    @Test fun `server addresses are read back from encrypted A and AAAA answers`() {
        val query = DnsWire.addressQuery("dnsbunker.org", 1)
        assertTrue(DnsWire.validQuery(query))
        assertEquals("dnsbunker.org", DnsWire.firstQuestionName(query).trimEnd('.'))
        val answer = query.copyOf().also { it[2] = 0x81.toByte(); it[3] = 0x80.toByte(); it[7] = 2 } +
            byteArrayOf(0xc0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 185.toByte(), 250.toByte(), 250.toByte(), 61) +
            byteArrayOf(0xc0.toByte(), 12, 0, 28, 0, 1, 0, 0, 0, 60, 0, 16, 0x2a, 0x0a, 0x51, 0xc1.toByte(), 0, 0x0a, 0, 0xea.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)
        assertEquals(listOf("185.250.250.61", "2a0a:51c1:a:ea:0:0:0:0"), DnsWire.answerAddresses(answer).map { it.hostAddress })
        val nx = answer.copyOf().also { it[3] = 0x83.toByte() }
        assertTrue(DnsWire.answerAddresses(nx).isEmpty())
        assertTrue(DnsWire.answerAddresses(answer.copyOf(answer.size - 3)).isEmpty())
    }

    @Test fun `blocked domain log is opt in, deduplicated and bounded`() {
        BlockedLog.enabled = false
        BlockedLog.record("ads.example.", DnsWire.BlockCategory.PRO)
        assertTrue(BlockedLog.entries.value.isEmpty())
        BlockedLog.enabled = true
        BlockedLog.record("Ads.Example.", DnsWire.BlockCategory.PRO, 1)
        BlockedLog.record("bad.example", DnsWire.BlockCategory.TIF, 2)
        BlockedLog.record("ads.example", DnsWire.BlockCategory.PRO, 3)
        BlockedLog.record("fine.example", DnsWire.BlockCategory.NONE, 4)
        assertEquals(listOf("ads.example" to 2, "bad.example" to 1), BlockedLog.entries.value.map { it.domain to it.count })
        repeat(BlockedLog.LIMIT + 20) { BlockedLog.record("d$it.example", DnsWire.BlockCategory.PRO) }
        assertEquals(BlockedLog.LIMIT, BlockedLog.entries.value.size)
        BlockedLog.enabled = false
        assertTrue(BlockedLog.entries.value.isEmpty())
    }

    @Test fun `server status messages are localised, cleaned and limited`() {
        val json = """{"message":{"en":"Planned maintenance\u0007 tonight","de":"Wartung heute Nacht"}}"""
        assertEquals("Wartung heute Nacht", ServerCheck.message(json, "de"))
        assertEquals("Planned maintenance tonight", ServerCheck.message(json, "fr"))
        assertNull(ServerCheck.message("""{"message":{}}""", "de"))
        assertNull(ServerCheck.message("not json", "de"))
        assertEquals(500, ServerCheck.message("""{"message":{"en":"${"x".repeat(900)}"}}""", "en")?.length)
    }

    @Test fun `router search domains become local only when safe`() {
        assertEquals(listOf("fritz.box", "localdomain"), DnsPrivacyPolicy.networkSuffixes("fritz.box localdomain"))
        assertEquals(listOf("acme.corp"), DnsPrivacyPolicy.networkSuffixes("Acme.CORP."))
        assertTrue(DnsPrivacyPolicy.networkSuffixes("com").isEmpty())
        assertTrue(DnsPrivacyPolicy.networkSuffixes("bad_domain.lan a..lan").isEmpty())
        assertTrue(DnsPrivacyPolicy.networkSuffixes("google.com bank.example Home.Example.com.").isEmpty())
        val zones = DnsPrivacyPolicy.localSuffixes("home.arpa, Corp.Example.", listOf("acme.corp"))
        assertTrue(DnsPrivacyPolicy.isLocal("nas.acme.corp", zones))
        assertTrue(DnsPrivacyPolicy.isLocal("db.corp.example", zones))
        assertTrue(DnsPrivacyPolicy.isLocal("router.fritz.box", zones))
        assertFalse(DnsPrivacyPolicy.isLocal("www.google.com", zones))
        assertTrue(DnsPrivacyPolicy.isLocal("nas.fritz.box"))
        assertTrue(DnsPrivacyPolicy.isLocal("printer.home.example.com", "home.arpa,home.example.com"))
        assertFalse(DnsPrivacyPolicy.isLocal("www.example.com", "home.arpa,home.example.com"))
    }
}
