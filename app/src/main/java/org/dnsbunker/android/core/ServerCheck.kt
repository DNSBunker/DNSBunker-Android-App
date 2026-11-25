package org.dnsbunker.android.core

import android.net.Network
import android.net.VpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit

object ServerCheck {
    const val STATUS_URL = "https://dnsbunker.org/app-status.json"

    suspend fun run(service: VpnService, network: Network, online: Boolean): Pair<ServerStatus, String?> = withContext(Dispatchers.IO) {
        if (!online) return@withContext ServerStatus.NO_INTERNET to null
        val sockets = ProtectedSockets(service, network)
        val reachable = DnsEndpoint.addresses.any { address ->
            runCatching { sockets.newSocket().use { it.connect(InetSocketAddress(address, 443), 3000) }; true }.getOrDefault(false)
        }
        if (!reachable) return@withContext ServerStatus.UNREACHABLE to null
        val body = runCatching { fetch(sockets) }.getOrElse { return@withContext ServerStatus.UNREACHABLE to null }
        ServerStatus.DNS_FAILING to body?.let(::message)
    }

    private fun fetch(sockets: ProtectedSockets): String? {
        val client = OkHttpClient.Builder().socketFactory(sockets.factory()).proxy(Proxy.NO_PROXY)
            .connectionSpecs(listOf(ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS).tlsVersions(*tlsVersions()).build()))
            .apply { if (Trust.needsBundledRoots) sslSocketFactory(Trust.socketFactory, Trust.trustManager) }
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    if (hostname != DnsEndpoint.HOST) throw UnknownHostException("Unapproved host")
                    return DnsEndpoint.addresses
                }
            })
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).callTimeout(6, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()
        client.newCall(Request.Builder().url(STATUS_URL).header("Accept", "application/json").build()).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw java.io.IOException("Status ${response.code}")
            return response.body?.byteStream()?.let { DnsWire.readBounded(it, 8192).toString(Charsets.UTF_8) }
        }
    }

    fun message(json: String, language: String = Locale.getDefault().language): String? = runCatching {
        val messages = JSONObject(json).optJSONObject("message") ?: return@runCatching null
        val text = messages.optString(language).ifBlank { messages.optString("en") }
        text.replace(Regex("[\\p{Cntrl}&&[^\\n]]"), "").trim().take(500).ifBlank { null }
    }.getOrNull()
}
