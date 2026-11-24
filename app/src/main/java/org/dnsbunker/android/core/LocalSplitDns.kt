package org.dnsbunker.android.core

import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

class LocalSplitDns(
    private val service: VpnService,
    private val network: Network,
) {
    private fun localAddress(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) return true
        if (address is Inet6Address) return (address.address[0].toInt() and 0xfe) == 0xfc
        if (address is Inet4Address) {
            val bytes = address.address
            val first = bytes[0].toInt() and 255
            val second = bytes[1].toInt() and 255
            return first == 10 || (first == 172 && second in 16..31) ||
                (first == 192 && second == 168) || (first == 169 && second == 254)
        }
        return false
    }

    suspend fun resolve(question: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        val connectivity = service.getSystemService(ConnectivityManager::class.java)
        val servers = connectivity.getLinkProperties(network)?.dnsServers.orEmpty()
            .filter(::localAddress)
        if (servers.isEmpty()) throw IOException("No trusted private network DNS server")
        var lastFailure: IOException? = null
        for (server in servers) {
            val socket = DatagramSocket(null)
            try {
                socket.bind(InetSocketAddress(0))
                if (!service.protect(socket)) throw IOException("Unable to protect split-DNS socket")
                network.bindSocket(socket)
                socket.soTimeout = 2000
                val packet = DatagramPacket(question, question.size, server, 53)
                socket.send(packet)
                val buffer = ByteArray(65535)
                val response = DatagramPacket(buffer, buffer.size)
                socket.receive(response)
                if (response.address != server || response.port != 53) throw IOException("Wrong local DNS source")
                val bytes = buffer.copyOf(response.length)
                if (!DnsWire.validResponse(question, bytes)) throw IOException("Invalid local DNS answer")
                return@withContext bytes
            } catch (error: IOException) {
                lastFailure = error
            } finally {
                socket.close()
            }
        }
        throw lastFailure ?: IOException("Local DNS failed")
    }
}
