package org.dnsbunker.android.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom

object DnsWire {
    private const val MAX_SIZE = 65535
    private val random = SecureRandom()

    private fun u16(data: ByteArray, at: Int): Int {
        require(at >= 0 && at + 1 < data.size) { "short DNS integer" }
        return ((data[at].toInt() and 255) shl 8) or (data[at + 1].toInt() and 255)
    }

    private fun putU16(data: ByteArray, at: Int, value: Int) {
        data[at] = (value ushr 8).toByte()
        data[at + 1] = value.toByte()
    }

    private fun skipName(data: ByteArray, start: Int): Int {
        var pos=start
        var end:Int?=null
        var expanded=1
        var steps=0
        while(true) {
            require(pos in data.indices && steps++<256) { "invalid DNS name" }
            val length=data[pos].toInt() and 255
            if(length==0)return end ?: (pos+1)
            if((length and 0xc0)==0xc0) {
                require(pos+1<data.size)
                val target=((length and 0x3f) shl 8) or (data[pos+1].toInt() and 255)
                require(target in 12 until pos) { "invalid compression pointer" }
                if(end==null)end=pos+2
                pos=target
            } else {
                require(length in 1..63 && pos+length+1<=data.size)
                expanded+=length+1;require(expanded<=255) { "expanded DNS name too long" }
                pos+=length+1
            }
        }
    }

    private data class OptLocation(val lengthOffset: Int, val dataEnd: Int, val existingLength: Int, val hasPadding: Boolean)

    private data class Question(val name: String, val type: Int, val dnsClass: Int)

    private fun parseQuestion(message: ByteArray): Question {
        require(message.size >= 17 && u16(message, 4) == 1) { "exactly one DNS question required" }
        val name = firstQuestionName(message)
        val nameEnd = skipName(message, 12)
        require(nameEnd + 4 <= message.size) { "truncated DNS question" }
        return Question(name, u16(message, nameEnd), u16(message, nameEnd + 2))
    }

    fun validQuery(message: ByteArray): Boolean = runCatching {
        require(message.size in 17..MAX_SIZE)
        require((message[2].toInt() and 0xf8) == 0)
        require(u16(message, 6) == 0 && u16(message, 8) == 0)
        parseQuestion(message)
        wellFormedAnswer(message)
    }.getOrDefault(false)

    fun responseCode(message:ByteArray):Int = runCatching {
        require(message.size>=12)
        var cursor=12
        repeat(u16(message,4)) {cursor=skipName(message,cursor)+4;require(cursor<=message.size)}
        val answers=u16(message,6)+u16(message,8)
        var extended=0;var opt=false
        repeat(answers+u16(message,10)) { index ->
            val owner=cursor;cursor=skipName(message,cursor);require(cursor+10<=message.size)
            if(index>=answers && u16(message,cursor)==41) {
                require(!opt && message[owner]==0.toByte());opt=true
                extended=u16(message,cursor+4) ushr 8
            }
            cursor+=10+u16(message,cursor+8);require(cursor<=message.size)
        }
        (extended shl 4) or (message[3].toInt() and 15)
    }.getOrDefault(-1)

    fun healthyResponse(query: ByteArray, response: ByteArray): Boolean = runCatching {
        if (!validResponse(query, response) || (response[2].toInt() and 2) != 0 ||
            responseCode(response) != 0) return@runCatching false
        var cursor = skipName(response, 12) + 4
        var found = false
        repeat(u16(response, 6)) {
            cursor = skipName(response, cursor)
            if (u16(response, cursor) == 1 && u16(response, cursor + 2) == 1 &&
                u16(response, cursor + 8) == 4) found = true
            cursor += 10 + u16(response, cursor + 8)
        }
        found
    }.getOrDefault(false)

    fun pad(query: ByteArray): ByteArray {
        require(query.size >= 12 && query.size <= MAX_SIZE) { "invalid DNS query size" }
        val questions = u16(query, 4)
        val answers = u16(query, 6)
        val authority = u16(query, 8)
        val additional = u16(query, 10)
        require(questions in 1..16 && answers + authority + additional <= 256) { "unexpected DNS layout" }
        var at = 12
        repeat(questions) {
            at = skipName(query, at)
            require(at + 4 <= query.size) { "short DNS question" }
            at += 4
        }
        var opt: OptLocation? = null
        repeat(answers + authority + additional) { index ->
            at = skipName(query, at)
            require(at + 10 <= query.size) { "short DNS RR" }
            val type = u16(query, at)
            val rdLength = u16(query, at + 8)
            val rdata = at + 10
            val end = rdata + rdLength
            require(end <= query.size) { "bad RDLENGTH" }
            if (index >= answers + authority && type == 41) {
                require(opt == null) { "multiple OPT records" }
                var cursor = rdata
                var hasPadding = false
                while (cursor < end) {
                    require(cursor + 4 <= end) { "short EDNS option" }
                    val length = u16(query, cursor + 2)
                    require(cursor + 4 + length <= end) { "invalid EDNS option" }
                    if (u16(query, cursor) == 12) hasPadding = true
                    cursor += 4 + length
                }
                opt = OptLocation(at + 8, end, rdLength, hasPadding)
            }
            at = end
        }
        require(at == query.size) { "trailing DNS bytes" }
        if (opt?.hasPadding == true) return query

        val optHeader = if (opt == null) 11 else 0
        val base = query.size + optHeader + 4
        val zeroBytes = (128 - (base % 128)) % 128
        val total = base + zeroBytes
        require(total <= MAX_SIZE) { "DNS padding limit" }
        val option = ByteArray(4 + zeroBytes)
        putU16(option, 0, 12)
        putU16(option, 2, zeroBytes)

        if (opt == null) {
            val result = query.copyOf(total)
            var cursor = query.size
            result[cursor++] = 0
            putU16(result, cursor, 41)
            cursor += 2
            putU16(result, cursor, 1232)
            cursor += 2
            cursor += 4
            putU16(result, cursor, option.size)
            cursor += 2
            option.copyInto(result, cursor)
            putU16(result, 10, additional + 1)
            return result
        }
        val location = requireNotNull(opt)
        val result = ByteArray(total)
        query.copyInto(result, 0, 0, location.dataEnd)
        option.copyInto(result, location.dataEnd)
        query.copyInto(result, location.dataEnd + option.size, location.dataEnd)
        putU16(result, location.lengthOffset, location.existingLength + option.size)
        return result
    }

    enum class BlockCategory { NONE, PRO, TIF, OTHER }
    private fun nameAt(data:ByteArray,start:Int):Pair<String,Int> {
        val end=skipName(data,start)
        var at=start;val labels=mutableListOf<String>()
        repeat(256) {
            val length=data[at].toInt() and 255
            if(length==0)return labels.joinToString(".").lowercase(java.util.Locale.ROOT) to end
            if(length and 0xc0==0xc0)at=((length and 63) shl 8) or (data[at+1].toInt() and 255)
            else {labels+=data.copyOfRange(at+1,at+1+length).toString(Charsets.US_ASCII);at+=1+length}
        }
        throw IllegalArgumentException("DNS pointer loop")
    }
    fun blockCategory(response:ByteArray):BlockCategory = runCatching {
        var pro=false;var tif=false
        if(responseCode(response)==3) {
            var at=12
            repeat(u16(response,4)){at=skipName(response,at)+4}
            val count=u16(response,6)+u16(response,8)+u16(response,10)
            require(count<=4096)
            fun marker(name:String){if(name=="pro.rpz.dnsbunker.org")pro=true;if(name=="tif.rpz.dnsbunker.org")tif=true}
            repeat(count) {
                val owner=nameAt(response,at);at=owner.second
                require(at+10<=response.size)
                val type=u16(response,at);val data=at+10;val end=data+u16(response,at+8);require(end<=response.size)
                val internetClass=u16(response,at+2)==1
                if(type==6&&internetClass){val primary=nameAt(response,data);val mailbox=nameAt(response,primary.second);if(mailbox.second+20==end)marker(owner.first)}
                if(type==5&&internetClass){val target=nameAt(response,data);if(target.second==end)marker(target.first)}
                at=end
            }
        }
        when{tif->BlockCategory.TIF;pro->BlockCategory.PRO;hasBlockingEde(response)->BlockCategory.OTHER;else->BlockCategory.NONE}
    }.getOrDefault(BlockCategory.NONE)

    fun hasBlockingEde(response: ByteArray): Boolean {
        if (response.size < 12) return false
        return try {
            var at = 12
            repeat(u16(response, 4)) { at = skipName(response, at) + 4 }
            val an = u16(response, 6)
            val ns = u16(response, 8)
            val ar = u16(response, 10)
            repeat(an + ns + ar) { index ->
                at = skipName(response, at)
                if (at + 10 > response.size) return false
                val type = u16(response, at)
                val rdLength = u16(response, at + 8)
                val end = at + 10 + rdLength
                if (end > response.size) return false
                if (index >= an + ns && type == 41) {
                    var cur = at + 10
                    while (cur + 4 <= end) {
                        val code = u16(response, cur)
                        val length = u16(response, cur + 2)
                        if (cur + 4 + length > end) return false
                        if (code == 15 && length >= 2 && u16(response, cur + 4) == 15) return true
                        cur += 4 + length
                    }
                }
                at = end
            }
            false
        } catch (_: IllegalArgumentException) { false }
    }

    fun validResponse(question: ByteArray, answer: ByteArray): Boolean =
        question.size >= 12 && answer.size >= 12 &&
            question[0] == answer[0] && question[1] == answer[1] &&
            (answer[2].toInt() and 0x80) != 0 &&
            (answer[2].toInt() and 0x78) == (question[2].toInt() and 0x78) &&
            runCatching {
                wellFormedAnswer(answer) && parseQuestion(question) == parseQuestion(answer)
            }.getOrDefault(false)

    private fun wellFormedAnswer(message: ByteArray): Boolean {
        if (message.size !in 17..MAX_SIZE || u16(message, 4) != 1) return false
        var cursor = skipName(message, 12) + 4
        require(cursor <= message.size)
        val count = u16(message, 6) + u16(message, 8) + u16(message, 10)
        if (count > 4096) return false
        repeat(count) {
            cursor = skipName(message, cursor)
            require(cursor + 10 <= message.size)
            cursor += 10 + u16(message, cursor + 8)
            require(cursor <= message.size)
        }
        return cursor == message.size
    }

    private fun advertisedUdpSize(query: ByteArray): Int {
        return try {
            if (query.size < 12 || u16(query, 4) != 1) return 512
            var at = 12
            repeat(u16(query, 4)) { at = skipName(query, at) + 4 }
            val answers = u16(query, 6)
            val authority = u16(query, 8)
            val additional = u16(query, 10)
            require(answers + authority + additional <= 256)
            repeat(answers + authority + additional) { index ->
                at = skipName(query, at)
                require(at + 10 <= query.size)
                val type = u16(query, at)
                val udpSize = u16(query, at + 2)
                val dataLength = u16(query, at + 8)
                at += 10 + dataLength
                require(at <= query.size)
                if (index >= answers + authority && type == 41) return udpSize.coerceIn(512, 1232)
            }
            512
        } catch (_: IllegalArgumentException) {
            512
        }
    }

    fun udpSafeResponse(query: ByteArray, response: ByteArray, maxDnsPayload: Int = 1232): ByteArray {
        require(maxDnsPayload in 512..1232)
        val limit = minOf(maxDnsPayload, advertisedUdpSize(query))
        if (response.size <= limit) return response
        val questionEnd = skipName(query, 12) + 4
        require(questionEnd <= query.size && questionEnd <= limit)
        val tc = query.copyOf(questionEnd)
        tc[2] = (response[2].toInt() or 0x02).toByte()
        tc[3] = response[3]
        putU16(tc, 6, 0)
        putU16(tc, 8, 0)
        putU16(tc, 10, 0)
        return tc
    }

    fun readBounded(input: InputStream, maxBytes: Int = MAX_SIZE): ByteArray {
        require(maxBytes in 12..MAX_SIZE)
        val buffer = ByteArray(8192)
        val output = ByteArrayOutputStream()
        while (true) {
            val amount = input.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - output.size()))
            if (amount < 0) break
            if (amount == 0) continue
            output.write(buffer, 0, amount)
            if (output.size() > maxBytes) throw IOException("Oversized DNS response")
        }
        return output.toByteArray()
    }

    fun firstQuestionName(query: ByteArray): String {
        require(query.size >= 17 && u16(query, 4) == 1) { "unsupported DNS question" }
        val labels = mutableListOf<String>()
        var cursor = 12
        while (true) {
            require(cursor < query.size && labels.size < 128)
            val length = query[cursor++].toInt() and 255
            if (length == 0) break
            require(length in 1..63 && cursor + length <= query.size)
            require(query.sliceArray(cursor until cursor + length).all {
                val c = it.toInt() and 255
                c in 33..126 && c != 46 && c != 92
            }) { "ambiguous DNS label" }
            require(cursor + length - 12 <= 253) { "DNS name too long" }
            labels += query.copyOfRange(cursor, cursor + length).toString(Charsets.US_ASCII)
            cursor += length
        }
        require(cursor + 4 <= query.size)
        return labels.joinToString(".").lowercase(java.util.Locale.ROOT)
    }

    fun servfail(query: ByteArray): ByteArray {
        require(query.size >= 12)
        var cursor = 12
        repeat(u16(query, 4)) {
            cursor = skipName(query, cursor)
            require(cursor + 4 <= query.size)
            cursor += 4
        }
        val result = query.copyOf(cursor)
        result[2] = ((query[2].toInt() and 0x79) or 0x80).toByte()
        result[3] = 0x82.toByte()
        putU16(result, 6, 0)
        putU16(result, 8, 0)
        putU16(result, 10, 0)
        return result
    }

    fun healthQuery(): ByteArray {
        val id = random.nextInt(65536)
        return byteArrayOf(
            (id ushr 8).toByte(), id.toByte(), 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
            7, 101, 120, 97, 109, 112, 108, 101, 3, 99, 111, 109, 0, 0, 1, 0, 1,
        )
    }
}
