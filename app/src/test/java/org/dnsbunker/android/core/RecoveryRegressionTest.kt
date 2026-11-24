package org.dnsbunker.android.core

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.dnsbunker.android.vpn.DnsVpnService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RecoveryRegressionTest {
    private fun answer(q: ByteArray): ByteArray {
        val a = DnsWire.servfail(q).apply { this[3] = 0x80.toByte(); this[7] = 1 }
        return a + byteArrayOf(0xc0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 93, 184.toByte(), 216.toByte(), 34)
    }

    private class Fake(override val name: String, val result: suspend (ByteArray) -> ByteArray) : DnsTransport {
        var calls = 0
        override suspend fun query(message: ByteArray): ByteArray { calls++; return result(message) }
    }

    @Test fun `transports in cooldown never leave the device without any DNS attempt`() {
        var now = 0L
        val policy = TransportPolicy { now }
        val names = listOf("DoH/3 · IP", "DoH/2 · IP", "DoH/2 · SNI", "DoT · IP")
        repeat(6) { names.forEach(policy::failure) }
        assertTrue(names.none(policy::canTry))
        val fallback = policy.order(names)
        assertEquals(listOf("DoH/2 · IP", "DoH/2 · SNI"), fallback)
        policy.success("DoH/2 · SNI", 20)
        assertEquals(listOf("DoH/2 · SNI"), policy.order(names))
        now = 60_001
        assertTrue(policy.order(names).containsAll(listOf("DoH/2 · IP", "DoH/2 · SNI", "DoT · IP")))
    }

    @Test fun `resolution recovers immediately after an outage ends`() = runBlocking {
        val controller = Robolectric.buildService(DnsVpnService::class.java).create()
        var outage = true
        val https = Fake("DoH/2 · IP") { if (outage) throw IOException("offline") else answer(it) }
        val client = DnsClient(controller.get(), null, DnsSettings(), transportOverride = listOf(https))
        val q = DnsWire.healthQuery()
        try {
            repeat(5) { runCatching { client.resolve(q, false) } }
            outage = false
            assertTrue(DnsWire.healthyResponse(q, client.resolve(q, false)))
        } finally { client.close(); controller.destroy() }
    }

    @Test fun `a hanging transport is cut off so the fallback answers the same query`() = runBlocking {
        val controller = Robolectric.buildService(DnsVpnService::class.java).create()
        val stalled = Fake("DoH/2 · IP") { awaitCancellation() }
        val backup = Fake("DoT · IP") { answer(it) }
        val client = DnsClient(controller.get(), null, DnsSettings(), transportOverride = listOf(stalled, backup),
            budgetMillis = 1_200, attemptMillis = 400)
        val q = DnsWire.healthQuery()
        try {
            val started = System.nanoTime()
            assertTrue(DnsWire.healthyResponse(q, client.resolve(q, false)))
            assertTrue((System.nanoTime() - started) / 1_000_000 in 350..1_000)
            assertEquals(1, backup.calls)
        } finally { client.close(); controller.destroy() }
    }

    @Test fun `stalled transports are bounded by the overall budget`() = runBlocking {
        val controller = Robolectric.buildService(DnsVpnService::class.java).create()
        val client = DnsClient(controller.get(), null, DnsSettings(),
            transportOverride = listOf(Fake("DoH/2 · IP") { awaitCancellation() }, Fake("DoT · IP") { awaitCancellation() }),
            budgetMillis = 900, attemptMillis = 600)
        try {
            val started = System.nanoTime()
            try { client.resolve(DnsWire.healthQuery(), false); fail("stalled query must fail") } catch (_: IOException) { }
            assertTrue((System.nanoTime() - started) / 1_000_000 in 850..1_400)
        } finally { client.close(); controller.destroy() }
    }

    @Test fun `an Error in one address attempt cannot stall the race`() = runTest {
        assertEquals("v6", firstSuccess(listOf(suspend { throw StackOverflowError() }, suspend { "v6" })))
        try {
            firstSuccess<String>(listOf(suspend { throw StackOverflowError() }))
            fail("must propagate")
        } catch (_: StackOverflowError) { }
    }

    @Test fun `settings no longer persist unused domain history flags`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        context.getSharedPreferences("dnsbunker_preferences", 0).edit().putBoolean("domain_history", true).commit()
        UserSettings(context).update { it.copy(watchdogEnabled = false) }
        assertFalse(context.getSharedPreferences("dnsbunker_preferences", 0).contains("domain_history"))
    }

    @Test fun `broken Wi-Fi does not win over validated mobile data`() {
        val brokenWifi = UnderlayCandidate(1, wifi = true, validated = false, captive = false, internet = true, unrestricted = true)
        val mobile = UnderlayCandidate(2, wifi = false, validated = true, captive = false, internet = true, unrestricted = true)
        assertEquals(2L, UnderlayPolicy.choose(listOf(brokenWifi, mobile), current = 1))
        assertEquals(1L, UnderlayPolicy.choose(listOf(brokenWifi.copy(validated = true), mobile), current = 2))
        assertEquals(1L, UnderlayPolicy.choose(listOf(brokenWifi, mobile.copy(validated = false)), current = null))
    }

    @Test fun `update versions compare numerically`() {
        assertTrue(Updater.newer("0.3.0", "0.2.3"))
        assertTrue(Updater.newer("0.10.0", "0.9.9"))
        assertTrue(Updater.newer("1.0", "0.99.99"))
        assertFalse(Updater.newer("0.2.3", "0.2.3"))
        assertFalse(Updater.newer("0.2.2", "0.2.10"))
    }
}
