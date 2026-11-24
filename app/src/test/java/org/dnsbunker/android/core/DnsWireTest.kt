package org.dnsbunker.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class DnsWireTest {
    @Test fun `health query is a valid single-question DNS message`() {
        val query = DnsWire.healthQuery()
        assertEquals("example.com", DnsWire.firstQuestionName(query))
        assertEquals(1, query[5].toInt())
    }

    @Test fun `padding uses 128-byte blocks and is idempotent`() {
        val original = DnsWire.healthQuery()
        val padded = DnsWire.pad(original)
        assertEquals(0, padded.size % 128)
        assertTrue(padded.size > original.size)
        assertTrue(original.copyOf(2).contentEquals(padded.copyOf(2)))
        assertTrue(padded.contentEquals(DnsWire.pad(padded)))
    }

    @Test fun `servfail preserves question without leaking details`() {
        val query = DnsWire.healthQuery()
        val response = DnsWire.servfail(query)
        assertTrue(DnsWire.validResponse(query, response))
        assertEquals(2, response[3].toInt() and 15)
        assertEquals("example.com", DnsWire.firstQuestionName(response))
    }

    @Test fun `nxdomain is not automatically a blocked event`() {
        val query = DnsWire.healthQuery()
        val nx = query.copyOf()
        nx[2] = 0x81.toByte()
        nx[3] = 0x83.toByte()
        assertFalse(DnsWire.hasBlockingEde(nx))
    }

    @Test fun `answers must match the exact query type and question`() {
        val query = DnsWire.healthQuery()
        val answer = DnsWire.servfail(query)
        assertTrue(DnsWire.validResponse(query, answer))
        val differentType = answer.copyOf()
        differentType[differentType.size - 3] = 28
        assertFalse(DnsWire.validResponse(query, differentType))
        val truncatedRecord = answer.copyOf().apply { this[7] = 1 }
        assertFalse(DnsWire.validResponse(query, truncatedRecord))
        assertFalse(DnsWire.validResponse(query, answer + byteArrayOf(0)))
    }

    @Test fun `large UDP response is truncated without EDNS`() {
        val query = DnsWire.healthQuery()
        val big = DnsWire.servfail(query).copyOf(800)
        val safe = DnsWire.udpSafeResponse(query, big)
        assertEquals(query.size, safe.size)
        assertTrue((safe[2].toInt() and 2) != 0)
        assertEquals(2, safe[3].toInt() and 15)
        assertTrue(DnsWire.validResponse(query, safe))
    }

    @Test fun `EDNS0 request allows nonfragmenting UDP responses`() {
        val query = DnsWire.pad(DnsWire.healthQuery())
        val response = DnsWire.servfail(query).copyOf(900)
        assertTrue(DnsWire.udpSafeResponse(query, response).contentEquals(response))
        val oversized = DnsWire.udpSafeResponse(query, response.copyOf(1300))
        assertTrue((oversized[2].toInt() and 2) != 0)
    }

    @Test(expected = IOException::class)
    fun `HTTPS DNS body is bounded even without content length`() {
        DnsWire.readBounded(ByteArrayInputStream(ByteArray(700)), maxBytes = 512)
    }
}
