package org.dnsbunker.android.vpn

data class DnsTcpSegment(
    val ipVersion: Int,
    val source: ByteArray,
    val destination: ByteArray,
    val sourcePort: Int,
    val destinationPort: Int,
    val sequence: Long,
    val acknowledgement: Long,
    val flags: Int,
    val window: Int,
    val payload: ByteArray,
)

object DnsTcpPacket {
    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10
    private const val MAX_IP_SIZE = 65535

    private fun u8(b: Byte) = b.toInt() and 255
    private fun u16(bytes: ByteArray, at: Int) = (u8(bytes[at]) shl 8) or u8(bytes[at + 1])
    private fun u32(bytes: ByteArray, at: Int) = (
        (u8(bytes[at]).toLong() shl 24) or (u8(bytes[at + 1]).toLong() shl 16) or
            (u8(bytes[at + 2]).toLong() shl 8) or u8(bytes[at + 3]).toLong()
        )

    private fun put16(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = (value ushr 8).toByte(); bytes[at + 1] = value.toByte()
    }
    private fun put32(bytes: ByteArray, at: Int, value: Long) {
        bytes[at] = (value ushr 24).toByte(); bytes[at + 1] = (value ushr 16).toByte()
        bytes[at + 2] = (value ushr 8).toByte(); bytes[at + 3] = value.toByte()
    }

    private fun words(bytes: ByteArray, offset: Int, count: Int): Long {
        var total = 0L
        var i = offset
        while (i < offset + count) {
            val first = u8(bytes[i++])
            val second = if (i < offset + count) u8(bytes[i++]) else 0
            total += (first shl 8) or second
        }
        return total
    }

    private fun fold(value: Long): Long {
        var sum = value
        while ((sum ushr 16) != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return sum
    }

    private fun pseudo(version: Int, source: ByteArray, destination: ByteArray, tcpLength: Int): ByteArray {
        val out = ByteArray(if (version == 4) 12 else 40)
        if (version == 4) {
            source.copyInto(out, 0); destination.copyInto(out, 4)
            out[9] = 6; put16(out, 10, tcpLength)
        } else {
            source.copyInto(out, 0); destination.copyInto(out, 16)
            put32(out, 32, tcpLength.toLong()); out[39] = 6
        }
        return out
    }

    fun parse(frame: ByteArray, count: Int): DnsTcpSegment? {
        if (count !in 40..frame.size) return null
        val version = u8(frame[0]) ushr 4
        val ipLength: Int
        val packetLength: Int
        val source: ByteArray
        val destination: ByteArray
        when (version) {
            4 -> {
                ipLength = (u8(frame[0]) and 0x0f) * 4
                if (ipLength < 20 || ipLength + 20 > count || u8(frame[9]) != 6) return null
                packetLength = u16(frame, 2)
                if (packetLength < ipLength + 20 || packetLength > count) return null
                if ((u16(frame, 6) and 0x3fff) != 0) return null
                if (fold(words(frame, 0, ipLength)) != 0xffffL) return null
                source = frame.copyOfRange(12, 16); destination = frame.copyOfRange(16, 20)
            }
            6 -> {
                ipLength = 40
                if (count < 60 || u8(frame[6]) != 6) return null
                packetLength = 40 + u16(frame, 4)
                if (packetLength < 60 || packetLength > count) return null
                source = frame.copyOfRange(8, 24); destination = frame.copyOfRange(24, 40)
            }
            else -> return null
        }
        val tcpLength = packetLength - ipLength
        val headerLength = (u8(frame[ipLength + 12]) ushr 4) * 4
        if (headerLength < 20 || headerLength > tcpLength) return null
        val pseudoHeader = pseudo(version, source, destination, tcpLength)
        if (fold(words(pseudoHeader, 0, pseudoHeader.size) + words(frame, ipLength, tcpLength)) != 0xffffL) return null
        return DnsTcpSegment(
            ipVersion = version,
            source = source,
            destination = destination,
            sourcePort = u16(frame, ipLength),
            destinationPort = u16(frame, ipLength + 2),
            sequence = u32(frame, ipLength + 4),
            acknowledgement = u32(frame, ipLength + 8),
            flags = u8(frame[ipLength + 13]),
            window = u16(frame, ipLength + 14),
            payload = frame.copyOfRange(ipLength + headerLength, packetLength),
        )
    }

    fun reply(request: DnsTcpSegment, sequence: Long, ack: Long, flags: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        require(request.ipVersion == 4 || request.ipVersion == 6)
        require(request.destinationPort == 53)
        require(payload.size <= 536) { "Exceeds conservative TCP MSS" }
        val ipHeader = if (request.ipVersion == 4) 20 else 40
        val tcpSize = 20 + payload.size
        val bytes = ByteArray(ipHeader + tcpSize)
        if (request.ipVersion == 4) {
            bytes[0] = 0x45
            put16(bytes, 2, bytes.size)
            bytes[8] = 64; bytes[9] = 6
            request.destination.copyInto(bytes, 12); request.source.copyInto(bytes, 16)
            put16(bytes, 10, (fold(words(bytes, 0, 20)).inv() and 0xffff).toInt())
        } else {
            bytes[0] = 0x60
            put16(bytes, 4, tcpSize)
            bytes[6] = 6; bytes[7] = 64
            request.destination.copyInto(bytes, 8); request.source.copyInto(bytes, 24)
        }
        put16(bytes, ipHeader, 53)
        put16(bytes, ipHeader + 2, request.sourcePort)
        put32(bytes, ipHeader + 4, sequence)
        put32(bytes, ipHeader + 8, ack)
        bytes[ipHeader + 12] = 0x50
        bytes[ipHeader + 13] = flags.toByte()
        put16(bytes, ipHeader + 14, 65535)
        payload.copyInto(bytes, ipHeader + 20)
        val pseudoHeader = pseudo(request.ipVersion, request.destination, request.source, tcpSize)
        val checksum = fold(words(pseudoHeader, 0, pseudoHeader.size) + words(bytes, ipHeader, tcpSize))
        put16(bytes, ipHeader + 16, (checksum.inv() and 0xffff).toInt())
        return bytes
    }
}
