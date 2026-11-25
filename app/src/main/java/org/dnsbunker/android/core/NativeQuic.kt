package org.dnsbunker.android.core

import android.net.Network
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap

object NativeQuic {
    val available: Boolean = runCatching { System.loadLibrary("dnsbunker_quic"); true }.getOrDefault(false)
    @JvmStatic external fun nativeCreate(fd: Int, ip: String, peer: String, roots: String): Long
    @JvmStatic external fun nativeQuery(handle: Long, kind: Int, query: ByteArray, timeoutMillis: Int): ByteArray
    @JvmStatic external fun nativeQueryTimed(handle:Long,kind:Int,query:ByteArray,timeoutMillis:Int):ByteArray
    @JvmStatic external fun nativeClose(handle: Long)
    val roots: String by lazy {
        val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        buildString {
            val aliases = store.aliases()
            while (aliases.hasMoreElements()) {
                val alias = aliases.nextElement()
                if (!alias.startsWith("system:")) continue
                val certificate = store.getCertificate(alias) ?: continue
                append("-----BEGIN CERTIFICATE-----\n")
                append(Base64.encodeToString(certificate.encoded, Base64.NO_WRAP)).append('\n')
                append("-----END CERTIFICATE-----\n")
            }
        }.also { if (it.isBlank()) throw IOException("No Android system trust anchors") } + Trust.BUNDLED_ROOTS
    }
}

class QuicBackend(private val service: VpnService, private val network: Network) : Closeable {
    private data class Session(val socket: DatagramSocket, val handle: Long)
    private val sessions = ConcurrentHashMap<String, Session>()
    @Volatile private var closed = false
    private val preferred=ConcurrentHashMap<String,String>()
    private fun session(ip: String, ipIdentity: Boolean): Session {
        if (closed || !NativeQuic.available) throw IOException("Native QUIC unavailable")
        sessions["$ip/$ipIdentity"]?.let { return it }
        val roots = NativeQuic.roots
        return synchronized(this) { createSession(ip, ipIdentity, roots) }
    }
    private fun createSession(ip: String, ipIdentity: Boolean, roots: String): Session {
        if (closed) throw IOException("Native QUIC unavailable")
        val key = "$ip/$ipIdentity"
        sessions[key]?.let { return it }
        val socket = DatagramSocket(null)
        try {
            socket.bind(InetSocketAddress(InetAddress.getByName(if (':' in ip) "::" else "0.0.0.0"), 0))
            if (!service.protect(socket)) throw IOException("Unable to protect QUIC socket")
            Compat.bind(network, socket)
            val fd = ParcelFileDescriptor.fromDatagramSocket(socket).detachFd()
            val handle = NativeQuic.nativeCreate(fd, ip, if (ipIdentity) ip else "dnsbunker.org", roots)
            if (handle == 0L) throw IOException("Unable to initialize verified QUIC")
            return Session(socket, handle).also { sessions[key] = it }
        } catch (error: Exception) { socket.close(); throw error }
    }
    suspend fun query(kind: Int, ipIdentity: Boolean, query: ByteArray, timeout: Int = 1800): ByteArray = exchange(kind,ipIdentity,query,timeout).answer
    suspend fun exchange(kind:Int,ipIdentity:Boolean,query:ByteArray,timeout:Int=1800):DnsExchange = withContext(Dispatchers.IO) {
        var last: IOException? = null
        val preference="$kind/$ipIdentity"
        val addresses=DnsEndpoint.addresses.mapNotNull { it.hostAddress }.sortedBy {if(it==preferred[preference])0 else 1}
        for (ip in addresses) {
            try {
                val data = NativeQuic.nativeQueryTimed(session(ip, ipIdentity).handle, kind, query, timeout)
                if(data.size<29)throw IOException("QUIC timing envelope")
                val meta=java.nio.ByteBuffer.wrap(data);val cold=meta.get().toInt()!=0;val setup=meta.long;val elapsed=meta.long
                val answer=data.copyOfRange(17,data.size)
                if (!DnsWire.validResponse(query, answer)) throw IOException("Invalid QUIC DNS response")
                preferred[preference]=ip
                return@withContext DnsExchange(answer,QueryTiming(cold,setup,elapsed))
            } catch (error: IOException) { last = error }
        }
        throw last ?: IOException("QUIC unavailable")
    }
    @Synchronized override fun close() {
        closed = true
        sessions.values.forEach { NativeQuic.nativeClose(it.handle); it.socket.close() }
        sessions.clear()
    }
}

class QuicDnsTransport(private val backend: QuicBackend, private val kind: Int, private val ipIdentity: Boolean) : DnsTransport {
    override val name = (if (kind == 1) "DoH/3" else "DoQ") + if (ipIdentity) " · IP" else " · SNI"
    override suspend fun query(message: ByteArray) = backend.query(kind, ipIdentity, message)
    override suspend fun exchange(message:ByteArray)=backend.exchange(kind,ipIdentity,message)
}
