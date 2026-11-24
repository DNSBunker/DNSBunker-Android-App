package org.dnsbunker.android

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.dnsbunker.android.core.*
import org.dnsbunker.android.vpn.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {
    @Test fun installedManifestKeepsVpnPermissionAndPrivacyFlags() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val pkg = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SERVICES)
        val vpn = pkg.services!!.single { it.name == DnsVpnService::class.java.name }
        assertEquals("android.permission.BIND_VPN_SERVICE", vpn.permission)
        assertEquals(0, pkg.applicationInfo!!.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP)
        assertFalse(android.security.NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted)
    }
    @Test fun ipv4AndIpv6PacketsKeepTheirChecksumsOnAndroidRuntime() {
        val query = DnsWire.healthQuery()
        for (version in listOf(4, 6)) {
            val n = if (version == 4) 4 else 16
            val source = ByteArray(n).apply { this[n - 1] = 1 }
            val dest = ByteArray(n).apply { this[n - 1] = 2 }
            val packet = DnsUdpPacket.response(DnsUdpRequest(version, source, dest, 53, query), query)
            assertArrayEquals(query, DnsUdpPacket.parse(packet, packet.size)!!.dnsMessage)
        }
    }
    @Test fun watchdogAndLocalNamespaceProtectionRunOnDevice() {
        val query = DnsWire.healthQuery()
        assertFalse(DnsWire.healthyResponse(query, DnsWire.servfail(query)))
        assertTrue(DnsPrivacyPolicy.isLocal("nas.lan"))
        assertFalse(WifiPolicy.exempt(null, setOf("Home"), false))
    }
}
