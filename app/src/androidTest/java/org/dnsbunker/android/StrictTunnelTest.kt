package org.dnsbunker.android
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.dnsbunker.android.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.*
import java.io.*
@RunWith(AndroidJUnit4::class)
class StrictTunnelTest:LiveDeviceFixture(){
 override val interceptPublicDns=true
 private fun udp(ip:String){
  val q=DnsWire.healthQuery()
  DatagramSocket().use{s->s.soTimeout=10000;s.connect(InetAddress.getByName(ip),53);s.send(DatagramPacket(q,q.size));val p=DatagramPacket(ByteArray(4096),4096);s.receive(p);assertTrue(DnsWire.healthyResponse(q,p.data.copyOf(p.length)))}
 }
 @Test fun allPublicUdpDnsIsInterceptedWithoutContactingDestination(){
  assertTrue(AppState.snapshot.value.interceptPublicDns)
  for(ip in listOf("203.0.113.53","2001:db8::53","8.8.8.8","2606:4700:4700::1111"))udp(ip)
 }
 @Test fun publicTcpSupportsRepeatedAndPipelinedDnsOnBothIpVersions(){
  for(ip in listOf("203.0.113.53","2001:db8::53"))Socket().use{s->
   s.connect(InetSocketAddress(ip,53),5000);s.soTimeout=15000
   val out=DataOutputStream(s.outputStream);val input=DataInputStream(s.inputStream)
   val queries=List(4){DnsWire.healthQuery()}
   queries.forEach{q->out.writeShort(q.size);out.write(q)};out.flush()
   queries.forEach{q->val a=ByteArray(input.readUnsignedShort());input.readFully(a);assertTrue(DnsWire.healthyResponse(q,a))}
  }
 }
 @Test fun ordinaryHttpsAndAndroidValidationSurviveFullTunnel(){
  shell("am force-stop org.dnsbunker.android.networkprobe");shell("am start -W -n org.dnsbunker.android.networkprobe/.ProbeActivity")
  val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
  val label=device.wait(Until.findObject(By.textContains("PROBE_RESULT")),40000)
  val text=label?.text.orEmpty();println(text)
  assertTrue(text,text.contains("VPN=true"));assertTrue(text,text.contains("HTTPS=200"))
  assertTrue(text,Regex("DNS_A=[1-9]").containsMatchIn(text));assertTrue(text,Regex("DNS_AAAA=[1-9]").containsMatchIn(text))
  val until=System.nanoTime()+20_000_000_000L
  while(!AppState.snapshot.value.androidValidated&&System.nanoTime()<until)Thread.sleep(200)
  assertTrue(AppState.snapshot.value.androidValidated)
 }
}
