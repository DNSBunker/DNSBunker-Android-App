package org.dnsbunker.android.vpn

data class DnsUdpRequest(
    val ipVersion: Int,
    val source: ByteArray,
    val destination: ByteArray,
    val sourcePort: Int,
    val dnsMessage: ByteArray,
)

object DnsUdpPacket {
    private fun unsigned(b: Byte) = b.toInt() and 0xff
    private fun u16(bytes: ByteArray, position: Int) = unsigned(bytes[position]) * 256 + unsigned(bytes[position + 1])
    private fun putU16(bytes: ByteArray, position: Int, value: Int) {
        bytes[position] = (value ushr 8).toByte()
        bytes[position + 1] = value.toByte()
    }

    fun parse(frame: ByteArray, received: Int): DnsUdpRequest? {
        if (received < 28 || received > frame.size) return null
        val version = unsigned(frame[0]) ushr 4
        val headerSize: Int
        val source: ByteArray
        val destination: ByteArray
        val packetSize: Int
        if (version == 4) {
            headerSize = (unsigned(frame[0]) and 15) * 4
            if (headerSize < 20 || received < headerSize + 8 || unsigned(frame[9]) != 17) return null
            packetSize = u16(frame, 2)
            if (packetSize < headerSize + 8 || packetSize > received) return null
            if ((u16(frame, 6) and 0x3fff) != 0) return null
            if (foldedSum(wordSum(frame, 0, headerSize)) != 0xffffL) return null
            source = frame.copyOfRange(12, 16)
            destination = frame.copyOfRange(16, 20)
        } else if (version == 6) {
            headerSize = 40
            if (received < 48 || unsigned(frame[6]) != 17) return null
            packetSize = 40 + u16(frame, 4)
            if (packetSize < 48 || packetSize > received) return null
            source = frame.copyOfRange(8, 24)
            destination = frame.copyOfRange(24, 40)
        } else return null

        val udpLength = u16(frame, headerSize + 4)
        if (udpLength < 8 || headerSize + udpLength != packetSize) return null
        val udpChecksum = u16(frame, headerSize + 6)
        if (version == 6 && udpChecksum == 0) return null
        if (udpChecksum != 0) {
            val pseudo = ByteArray(if (version == 4) 12 else 40)
            if (version == 4) {
                source.copyInto(pseudo, 0)
                destination.copyInto(pseudo, 4)
                pseudo[9] = 17
                putU16(pseudo, 10, udpLength)
            } else {
                source.copyInto(pseudo, 0)
                destination.copyInto(pseudo, 16)
                putU16(pseudo, 34, udpLength)
                pseudo[39] = 17
            }
            val sum = wordSum(pseudo, 0, pseudo.size) + wordSum(frame, headerSize, udpLength)
            if (foldedSum(sum) != 0xffffL) return null
        }
        val destinationPort = u16(frame, headerSize + 2)
        if (destinationPort != 53) return null
        val dns = frame.copyOfRange(headerSize + 8, headerSize + udpLength)
        if (dns.size < 12) return null
        return DnsUdpRequest(version, source, destination, u16(frame, headerSize), dns)
    }

    fun response(request: DnsUdpRequest, answer: ByteArray): ByteArray {
        require(answer.size in 12..65507)
        val ipLength = if (request.ipVersion == 4) 20 else 40
        val udpLength = answer.size + 8
        val buffer = ByteArray(ipLength + udpLength)
        if (request.ipVersion == 4) {
            buffer[0] = 0x45
            putU16(buffer, 2, buffer.size)
            buffer[8] = 64
            buffer[9] = 17
            request.destination.copyInto(buffer, 12)
            request.source.copyInto(buffer, 16)
            putU16(buffer, 10, checksum(buffer, 0, 20))
        } else {
            buffer[0] = 0x60
            putU16(buffer, 4, udpLength)
            buffer[6] = 17
            buffer[7] = 64
            request.destination.copyInto(buffer, 8)
            request.source.copyInto(buffer, 24)
        }
        putU16(buffer, ipLength, 53)
        putU16(buffer, ipLength + 2, request.sourcePort)
        putU16(buffer, ipLength + 4, udpLength)
        answer.copyInto(buffer, ipLength + 8)

        val pseudo = ByteArray(if (request.ipVersion == 4) 12 else 40)
        if (request.ipVersion == 4) {
            request.destination.copyInto(pseudo, 0)
            request.source.copyInto(pseudo, 4)
            pseudo[9] = 17
            putU16(pseudo, 10, udpLength)
        } else {
            request.destination.copyInto(pseudo, 0)
            request.source.copyInto(pseudo, 16)
            pseudo[32] = (udpLength ushr 24).toByte()
            pseudo[33] = (udpLength ushr 16).toByte()
            pseudo[34] = (udpLength ushr 8).toByte()
            pseudo[35] = udpLength.toByte()
            pseudo[39] = 17
        }
        val sum = wordSum(pseudo, 0, pseudo.size) + wordSum(buffer, ipLength, udpLength)
        val udpChecksum = (foldedSum(sum).inv() and 0xffff).toInt().let { if (it == 0) 0xffff else it }
        putU16(buffer, ipLength + 6, udpChecksum)
        return buffer
    }

    private fun wordSum(data: ByteArray, start: Int, length: Int): Long {
        var result = 0L
        var p = start
        val end = start + length
        while (p < end) {
            val high = unsigned(data[p++])
            val low = if (p < end) unsigned(data[p++]) else 0
            result += (high shl 8) or low
        }
        return result
    }

    private fun checksum(data: ByteArray, start: Int, length: Int): Int {
        return (foldedSum(wordSum(data, start, length)).inv() and 0xffff).toInt()
    }

    private fun foldedSum(input: Long): Long {
        var sum = input
        while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return sum
    }
}
