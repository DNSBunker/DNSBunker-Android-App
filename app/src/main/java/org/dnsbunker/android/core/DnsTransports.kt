package org.dnsbunker.android.core

import android.net.Network
import android.os.Build
import android.net.VpnService
import org.dnsbunker.android.R
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.*
import java.net.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.WeakHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import javax.net.SocketFactory
import javax.net.ssl.*

object DnsEndpoint {
    const val HOST = "dnsbunker.org"
    val fallback: List<InetAddress> = listOf(InetAddress.getByName("185.250.250.61"), InetAddress.getByName("2a0a:51c1:a:ea::"))
    @Volatile var addresses: List<InetAddress> = fallback
        private set
    @Volatile private var prefs: android.content.SharedPreferences? = null
    @Volatile private var lastBootstrap = 0L
    @Volatile var lastRefresh = 0L
        private set

    fun init(context: android.content.Context) {
        if (prefs != null) return
        val store = context.applicationContext.getSharedPreferences("dnsbunker_endpoint", android.content.Context.MODE_PRIVATE)
        prefs = store
        lastRefresh = store.getLong("refreshed", 0)
        val saved = store.getString("addresses", null)?.split(',')?.mapNotNull { literal(it) }.orEmpty()
        if (saved.isNotEmpty()) addresses = select(saved + fallback)
    }

    fun literal(text: String): InetAddress? {
        val value = text.trim()
        if (value.isEmpty() || !value.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == '.' || it == ':' }) return null
        return runCatching { InetAddress.getByName(value) }.getOrNull()?.takeIf(::usable)
    }

    fun usable(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address
        val a = bytes[0].toInt() and 255
        val b = bytes[1].toInt() and 255
        return if (bytes.size == 4) {
            !(a == 0 || a == 10 || a == 127 || a >= 224 || (a == 100 && b in 64..127) || (a == 169 && b == 254) ||
                (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 198 && b in 18..19) ||
                (a == 192 && b == 0 && (bytes[2].toInt() and 255) in setOf(0, 2)))
        } else {
            a and 0xe0 == 0x20 && !(a == 0x20 && b == 0x01 && (bytes[2].toInt() and 255) == 0x0d && (bytes[3].toInt() and 255) == 0xb8)
        }
    }

    fun select(candidates: List<InetAddress>): List<InetAddress> {
        val unique = candidates.filter(::usable).distinctBy { it.hostAddress }
        val v4 = unique.filterIsInstance<Inet4Address>().take(2)
        val v6 = unique.filterIsInstance<Inet6Address>().take(2)
        return buildList { for (i in 0 until 2) { v4.getOrNull(i)?.let(::add); v6.getOrNull(i)?.let(::add) } }
    }

    fun trusted(found: List<InetAddress>): Boolean {
        val fresh = found.filter(::usable)
        if (fresh.isEmpty()) return false
        val next = select(fresh + fallback.filter { f -> fresh.none { it.javaClass == f.javaClass } })
        lastRefresh = System.currentTimeMillis()
        prefs?.edit()?.putString("addresses", fresh.joinToString(",") { it.hostAddress ?: "" })?.putLong("refreshed", lastRefresh)?.apply()
        if (next.map { it.hostAddress } == addresses.map { it.hostAddress }) return false
        addresses = next
        return true
    }

    fun bootstrap(network: Network): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastBootstrap < 10 * 60_000L) return false
        lastBootstrap = now
        val found = runCatching { network.getAllByName(HOST).toList() }.getOrDefault(emptyList()).filter(::usable)
        if (found.isEmpty()) return false
        val next = select(found + addresses + fallback)
        if (next.map { it.hostAddress } == addresses.map { it.hostAddress }) return false
        addresses = next
        return true
    }

    fun url(address: InetAddress) = "https://" + if (address is Inet6Address) "[${address.hostAddress}]/dns-query" else "${address.hostAddress}/dns-query"
}

class ProtectedSockets(private val service: VpnService, private val underlay: Network?) {
    fun newSocket(localAddress: InetAddress? = null, localPort: Int = 0): Socket = Socket().also { socket ->
        try {
            val network = underlay ?: throw IOException("No physical network")
            socket.bind(InetSocketAddress(localAddress, localPort))
            if (!service.protect(socket)) throw IOException("VpnService.protect failed")
            Compat.bind(network, socket)
        } catch (error: Exception) { socket.close(); throw error }
    }
    private fun connected(host: InetSocketAddress, local: InetAddress? = null, port: Int = 0): Socket {
        val socket = newSocket(local, port)
        try { socket.connect(host, 2000); return socket } catch (e: Exception) { socket.close(); throw e }
    }
    fun factory(): SocketFactory = object : SocketFactory() {
        override fun createSocket() = newSocket()
        private fun endpoint(host: String, port: Int): InetSocketAddress {
            val address = DnsEndpoint.addresses.firstOrNull { it.hostAddress == host }
                ?: throw UnknownHostException("Only literal DNSBunker addresses permitted")
            return InetSocketAddress(address, port)
        }
        override fun createSocket(host: String, port: Int) = connected(endpoint(host, port))
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int) = connected(endpoint(host, port), local, localPort)
        override fun createSocket(host: InetAddress, port: Int) = connected(InetSocketAddress(host, port))
        override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int) = connected(InetSocketAddress(host, port), local, localPort)
    }
}
internal fun tlsVersions(): Array<TlsVersion> =
    if (Build.VERSION.SDK_INT >= 29) arrayOf(TlsVersion.TLS_1_3) else arrayOf(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)

data class QueryTiming(val cold:Boolean, val setupMillis:Long, val elapsedMillis:Long)
data class DnsExchange(val answer:ByteArray,val timing:QueryTiming)

interface DnsTransport : Closeable {
    val name: String
    suspend fun query(message: ByteArray): ByteArray
    suspend fun exchange(message:ByteArray):DnsExchange {
        val start=System.nanoTime();val answer=query(message)
        return DnsExchange(answer,QueryTiming(false,0,(System.nanoTime()-start)/1_000_000))
    }
    override fun close() {}
}
class DnsOverHttps(sockets: ProtectedSockets, private val ipIdentity: Boolean = false) : DnsTransport {
    override val name = "DoH/2 · " + if (ipIdentity) "IP" else "SNI"
    private val lifecycle=Any()
    @Volatile private var closed=false
    private val activeCalls: MutableSet<Call> = java.util.Collections.newSetFromMap(ConcurrentHashMap<Call, Boolean>())
    private data class ConnectionAge(val born:Long,val setup:Long)
    private class Timing {val start=System.nanoTime();var connectStart=0L;var cold=false;var setup=0L}
    private val connections=WeakHashMap<Connection,ConnectionAge>()
    private val events=object:EventListener.Factory {
        override fun create(call:Call):EventListener=object:EventListener() {
            private val timing=call.request().tag(Timing::class.java)!!
            override fun connectStart(call:Call,inetSocketAddress:InetSocketAddress,proxy:Proxy){timing.connectStart=System.nanoTime();timing.cold=true}
            override fun connectEnd(call:Call,inetSocketAddress:InetSocketAddress,proxy:Proxy,protocol:Protocol?){timing.setup=(System.nanoTime()-timing.connectStart)/1_000_000}
            override fun connectionAcquired(call:Call,connection:Connection){
                synchronized(connections){
                    val age=connections.getOrPut(connection){ConnectionAge(System.nanoTime(),timing.setup)}
                    if(age.born>=timing.start){timing.cold=true;timing.setup=maxOf(timing.setup,age.setup)}
                }
            }
        }
    }
    private val http = OkHttpClient.Builder().eventListenerFactory(events).dispatcher(Dispatcher().apply{maxRequests=64;maxRequestsPerHost=32}).socketFactory(sockets.factory())
        .connectionSpecs(listOf(ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS).tlsVersions(*tlsVersions()).build()))
        .apply { if (Trust.needsBundledRoots) sslSocketFactory(Trust.socketFactory, Trust.trustManager) }
        .proxy(Proxy.NO_PROXY)
        .hostnameVerifier { host, session -> Trust.verifyPeer(host, session) }
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                if (hostname != DnsEndpoint.HOST) throw UnknownHostException("Unapproved bootstrap")
                return DnsEndpoint.addresses
            }
        }).connectTimeout(2,TimeUnit.SECONDS).readTimeout(3,TimeUnit.SECONDS).callTimeout(4,TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private suspend fun queryUrl(url:String,message:ByteArray):DnsExchange = suspendCancellableCoroutine { continuation ->
        val timing=Timing()
        val request=Request.Builder().tag(Timing::class.java,timing).url(url).header("Accept","application/dns-message")
            .post(message.toRequestBody("application/dns-message".toMediaType())).build()
        val call=http.newCall(request)
        synchronized(lifecycle) {
            if(closed) {continuation.resumeWithException(IOException("DoH closed"));return@suspendCancellableCoroutine}
            activeCalls.add(call)
        }
        continuation.invokeOnCancellation {call.cancel();activeCalls.remove(call)}
        call.enqueue(object:Callback {
            override fun onFailure(call:Call,e:IOException) {activeCalls.remove(call);continuation.resumeWithException(e)}
            override fun onResponse(call:Call,response:Response) {
                val result=runCatching {
                    response.use {
                        if(response.code!=200||response.protocol!=Protocol.HTTP_2)throw IOException("DoH/2 response rejected")
                        val body=response.body?:throw IOException("Missing DoH body")
                        if(body.contentType()?.toString()?.substringBefore(';')!="application/dns-message"||body.contentLength()>65535)throw IOException("DoH media or size")
                        val answer=DnsWire.readBounded(body.byteStream())
                        if(!DnsWire.validResponse(message,answer))throw IOException("Invalid DoH answer")
                        DnsExchange(answer,QueryTiming(timing.cold,timing.setup,(System.nanoTime()-timing.start)/1_000_000))
                    }
                }
                activeCalls.remove(call)
                continuation.resumeWith(result)

            }
        })
    }
    override suspend fun query(message: ByteArray): ByteArray = exchange(message).answer
    override suspend fun exchange(message:ByteArray):DnsExchange {
        val urls=if(ipIdentity)DnsEndpoint.addresses.map(DnsEndpoint::url)else listOf("https://dnsbunker.org/dns-query")
        return firstSuccess(urls.map {url->suspend {queryUrl(url,message)}})
    }
    override fun close() {
        synchronized(lifecycle){closed=true;activeCalls.forEach {it.cancel()};activeCalls.clear()}
        http.dispatcher.cancelAll();http.connectionPool.evictAll();http.dispatcher.executorService.shutdown()
    }

}
class DnsOverTls(private val sockets: ProtectedSockets, private val ipIdentity: Boolean = false) : DnsTransport {
    override val name = "DoT · " + if (ipIdentity) "IP" else "SNI"
    private val mutex = Mutex()
    @Volatile private var connection: SSLSocket? = null
    private var born=0L
    private var setupMillis=0L
    @Volatile private var closed=false
    @Synchronized private fun register(socket:SSLSocket):SSLSocket {
        if(closed){socket.close();throw IOException("DoT closed")}
        connection=socket;return socket
    }
    @Synchronized private fun discard() {runCatching {connection?.close()};connection=null}
    private fun connect(): SSLSocket {
        if(closed)throw IOException("DoT closed")
        val start=System.nanoTime()
        var last: IOException? = null
        for (address in DnsEndpoint.addresses) {
            val raw = sockets.newSocket()
            try {
                raw.connect(InetSocketAddress(address,853),2000);raw.soTimeout = 3000
                val peer = if (ipIdentity) address.hostAddress!! else DnsEndpoint.HOST
                val tls = Trust.socketFactory.createSocket(raw,peer,853,true) as SSLSocket
                try {
                    tls.enabledProtocols = if (Build.VERSION.SDK_INT >= 29) arrayOf("TLSv1.3")
                        else tls.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
                    if (Build.VERSION.SDK_INT >= 24) {
                        tls.sslParameters = tls.sslParameters.apply {
                            endpointIdentificationAlgorithm = "HTTPS"
                            serverNames = if (ipIdentity) emptyList() else listOf(SNIHostName(DnsEndpoint.HOST))
                        }
                    } else if (!ipIdentity) {
                        runCatching { tls.javaClass.getMethod("setHostname", String::class.java).invoke(tls, peer) }
                    }
                    tls.startHandshake()
                    if (!Trust.verifyPeer(peer, tls.session))
                        throw SSLPeerUnverifiedException("DoT certificate does not match $peer")
                    born=System.nanoTime();setupMillis=(born-start)/1_000_000;return tls
                } catch (e: Exception) { tls.close();throw e }
            } catch (e: IOException) { raw.close();last = e }
        }
        throw last ?: IOException("DoT unavailable")
    }
    override suspend fun query(message: ByteArray): ByteArray = exchange(message).answer
    override suspend fun exchange(message:ByteArray):DnsExchange = withContext(Dispatchers.IO) {
        val start=System.nanoTime()
        mutex.withLock {
            repeat(2) { attempt ->
                if(closed)throw IOException("DoT closed")
                try {
                    val socket = connection?.takeUnless { it.isClosed } ?: register(connect())
                    DataOutputStream(socket.outputStream).apply { writeShort(message.size);write(message);flush() }
                    val input = DataInputStream(socket.inputStream)
                    val n = input.readUnsignedShort();if(n !in 12..65535) throw IOException("DoT size")
                    val answer = ByteArray(n);input.readFully(answer)
                    if (!DnsWire.validResponse(message,answer)) throw IOException("DoT answer")
                    return@withLock DnsExchange(answer,QueryTiming(born>=start,if(born>=start)setupMillis else 0,(System.nanoTime()-start)/1_000_000))
                } catch (e: IOException) { discard();if(closed||attempt == 1) throw e }
            }
            throw IOException("DoT unavailable")
        }
    }
    @Synchronized override fun close() {closed=true;discard()}
}

data class ProtocolHealth(val name: String, val ready: Boolean, val latencyMillis: Long? = null,val setupMillis:Long?=null)
class DnsClient(private val service: VpnService, private val network: Network?, private val settings: DnsSettings,
    private val allowLocalDns: Boolean = false, transportOverride: List<DnsTransport>? = null,
    networkSuffixes: List<String> = emptyList(),
    private val budgetMillis: Long = 6_000, private val attemptMillis: Long = 3_500) : Closeable {
    val networkHandle:Long?=network?.let(Compat::id)
    private val sockets = ProtectedSockets(service,network)
    private val backend = network?.let { QuicBackend(service,it) }
    private val transports: List<DnsTransport> = transportOverride ?: buildList {
        if (backend != null && NativeQuic.available) {
            add(QuicDnsTransport(backend,1,true));add(QuicDnsTransport(backend,0,true))
            add(QuicDnsTransport(backend,1,false));add(QuicDnsTransport(backend,0,false))
        }
        add(DnsOverHttps(sockets,true));add(DnsOverHttps(sockets,false))
        if (settings.fallbackDoT) { add(DnsOverTls(sockets,true));add(DnsOverTls(sockets,false)) }
    }
    private val localZones = DnsPrivacyPolicy.localSuffixes(settings.splitDnsSuffixes, networkSuffixes)
    private val policy = TransportPolicy()
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    @Volatile private var closed = false
    @Volatile var lastTransport = "—"
        private set
    fun startBackgroundProbes() {
        if (closed) return
        transports.filter { (it.name.startsWith("DoQ") || it.name.startsWith("DoH/3")) && !policy.state(it.name).ready && policy.canTry(it.name) }.forEach { transport ->
            scope.launch { probeTransport(transport.name) }
        }
    }
    fun transportNames() = transports.map { it.name }
    suspend fun probeTransport(name: String): Boolean {
        val transport = transports.firstOrNull { it.name == name } ?: return false
        if (closed) return false
        val query = DnsWire.pad(DnsWire.healthQuery())
        var setup:Long?=null;var warm:Long?=null
        val ready = try {
            val first=transport.exchange(query)
            if(first.timing.cold)setup=first.timing.setupMillis
            if(!DnsWire.healthyResponse(query,first.answer))false else {
                val second=transport.exchange(query)
                if(!second.timing.cold)warm=second.timing.elapsedMillis
                DnsWire.healthyResponse(query,second.answer)
            }
        } catch(c:CancellationException){throw c}catch(_:Exception){false}
        if (!closed) {
            if (ready) policy.success(name,warm?:Long.MAX_VALUE) else policy.failure(name)
            AppState.update { old -> if(closed) old else old.copy(protocolHealth = old.protocolHealth.filterNot { it.name == name } + ProtocolHealth(name,ready,warm.takeIf { ready },setup)) }
        }
        return ready
    }
    suspend fun resolve(originalMessage: ByteArray, recordMetrics: Boolean = true): ByteArray {
        if (closed) throw IOException("DNS client closed")
        require(DnsWire.validQuery(originalMessage)) { "Invalid DNS query" }
        val start = System.nanoTime()
        if (DnsPrivacyPolicy.isLocal(DnsWire.firstQuestionName(originalMessage),localZones)) {
            if (!settings.splitDnsEnabled || !allowLocalDns || network == null) throw IOException("Local DNS not permitted")
            val answer = LocalSplitDns(service,network).resolve(originalMessage)
            if(recordMetrics) AppState.recordQuery((System.nanoTime()-start)/1_000_000,false)
            return answer
        }
        val outbound = if(settings.ednsPaddingEnabled) DnsWire.pad(originalMessage) else originalMessage
        val ordered = policy.order(transportNames())
        var last: Exception? = null
        var clockError = false
        for (name in ordered) {
            if(closed)throw IOException("DNS client closed")
            val remaining = budgetMillis - (System.nanoTime()-start)/1_000_000
            if (remaining < 250) break
            val transport = transports.first { it.name == name }
            try {
                val exchange = withTimeoutOrNull(minOf(remaining, attemptMillis)) { transport.exchange(outbound) } ?: throw IOException("DNS attempt timed out")
                val answer=exchange.answer
                if (closed) throw IOException("Network changed")
                val ms = (System.nanoTime()-start)/1_000_000
                policy.success(name,ms);lastTransport = name
                if (AppState.snapshot.value.clockWrong) AppState.update { it.copy(clockWrong = false) }
                if(recordMetrics) {
                    AppState.update { it.copy(transport=name) }
                    val category=DnsWire.blockCategory(answer)
                    AppState.recordQuery(ms,category!=DnsWire.BlockCategory.NONE,category,exchange.timing)
                    if (BlockedLog.enabled && category != DnsWire.BlockCategory.NONE)
                        BlockedLog.record(DnsWire.firstQuestionName(originalMessage), category)
                }
                return answer
            } catch(c: CancellationException) { throw c }
            catch(e: Exception) { last = e;policy.failure(name);if (ClockCheck.isTimeError(e)) clockError = true }
        }
        if (clockError && android.provider.Settings.Global.getInt(service.contentResolver, android.provider.Settings.Global.AUTO_TIME, 1) == 0)
            AppState.update { it.copy(clockWrong = true) }
        if(recordMetrics) AppState.recordFailure(service.getString(R.string.health_failed))
        throw IOException("No verified encrypted transport",last)
    }
    suspend fun refreshEndpoints(): Boolean {
        val found = listOf(1, 28).flatMap { type ->
            try { DnsWire.answerAddresses(resolve(DnsWire.addressQuery(DnsEndpoint.HOST, type), false)) }
            catch (c: CancellationException) { throw c } catch (_: Exception) { emptyList() }
        }
        return DnsEndpoint.trusted(found)
    }
    suspend fun probe(): Boolean = try {
        val q = DnsWire.healthQuery();DnsWire.healthyResponse(q,resolve(q,false))
    } catch(c: CancellationException) { throw c } catch (_: Exception) { false }
    override fun close() { closed=true;scope.cancel();transports.forEach { runCatching { it.close() } };backend?.close() }
}
