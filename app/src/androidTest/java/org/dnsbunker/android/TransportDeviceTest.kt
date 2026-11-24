package org.dnsbunker.android

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import org.dnsbunker.android.vpn.DnsVpnService
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.dnsbunker.android.core.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransportDeviceTest : LiveDeviceFixture() {
    private class AttachedService(context: Context) : VpnService() {init { attachBaseContext(context) }}
    private fun check(name:String) = runBlocking {
        val client=DnsClient(AttachedService(context),underlay(),DnsSettings())
        try{assertTrue("Protocol must exist: $name",name in client.transportNames());assertTrue("Protocol failed: $name",client.probeTransport(name));val health=AppState.snapshot.value.protocolHealth.first{it.name==name};assertNotNull("Warm timing required: $name",health.latencyMillis);println("TIMING $name warm=${health.latencyMillis} setup=${health.setupMillis}")}
        finally{client.close()}
    }
    @Test fun doh2DirectIP()=check("DoH/2 · IP")
    @Test fun doh2HostnameSNI()=check("DoH/2 · SNI")
    @Test fun doh3DirectIP()=check("DoH/3 · IP")
    @Test fun doh3HostnameSNI()=check("DoH/3 · SNI")
    @Test fun doqDirectIP()=check("DoQ · IP")
    @Test fun doqHostnameSNI()=check("DoQ · SNI")
    @Test fun dotDirectIP()=check("DoT · IP")
    @Test fun dotHostnameSNI()=check("DoT · SNI")
}
