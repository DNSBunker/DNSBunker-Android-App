package org.dnsbunker.android

import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.dnsbunker.android.core.*
import org.dnsbunker.android.vpn.DnsVpnService
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue

open class LiveDeviceFixture {
 protected val context get()=InstrumentationRegistry.getInstrumentation().targetContext
 protected open val interceptPublicDns=false
 private var originalIntercept=false
 private var preferencesChanged=false
 private var started=false
 private val manage get()=InstrumentationRegistry.getArguments().getString("managePrivateDns")=="true"
 protected fun underlay():android.net.Network {
  val manager=context.getSystemService(android.net.ConnectivityManager::class.java)
  @Suppress("DEPRECATION") val networks=manager.allNetworks.toList()
  val candidates=networks.mapNotNull { n -> manager.getNetworkCapabilities(n)?.let { c ->
   UnderlayCandidate(n.networkHandle,c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI),
    c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED),c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
    c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET),c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED),
    c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) } }
  val chosen=UnderlayPolicy.choose(candidates)
  return networks.first { it.networkHandle==chosen }
 }
 protected fun shell(command:String) {
  InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { fd ->
   java.io.FileInputStream(fd.fileDescriptor).use {it.readBytes()}
  }
 }
 @Before fun startVpn() {
  assumeTrue(InstrumentationRegistry.getArguments().getString("liveVpn")=="true")
  if(manage){shell("settings put global private_dns_specifier dnsbunker.org");shell("settings put global private_dns_mode hostname")}
  val settings=UserSettings(context);originalIntercept=settings.current().interceptPublicDns
  settings.update{it.copy(interceptPublicDns=this.interceptPublicDns)};preferencesChanged=true
  assertNull("User VPN consent required",VpnService.prepare(context))
  context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(500)
  ContextCompat.startForegroundService(context,Intent(context,DnsVpnService::class.java).setAction(DnsVpnService.ACTION_START));started=true
  val deadline=System.nanoTime()+40_000_000_000L
  while(AppState.snapshot.value.status!=ProtectionStatus.ACTIVE&&System.nanoTime()<deadline)Thread.sleep(100)
  assertEquals("Encrypted upstream and real TUN must work",ProtectionStatus.ACTIVE,AppState.snapshot.value.status)
  if(manage)shell("settings put global private_dns_mode off")
 }
 @After fun stopVpn() {
  if(manage){shell("settings put global private_dns_specifier dnsbunker.org");shell("settings put global private_dns_mode hostname")}
  if(started){context.startService(Intent(context,DnsVpnService::class.java).setAction(DnsVpnService.ACTION_STOP))
   val deadline=System.nanoTime()+5_000_000_000L
   while(AppState.snapshot.value.status!=ProtectionStatus.OFF&&System.nanoTime()<deadline)Thread.sleep(100)
   assertEquals(ProtectionStatus.OFF,AppState.snapshot.value.status)
  }
  if(preferencesChanged)UserSettings(context).update{it.copy(interceptPublicDns=originalIntercept)}
 }
}
