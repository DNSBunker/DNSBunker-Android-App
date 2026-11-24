package org.dnsbunker.android.core
import org.junit.Test
import org.junit.Assert.*
import kotlinx.coroutines.*
import java.net.InetAddress
import java.math.BigInteger
import java.io.ByteArrayOutputStream
class CategoryTimingRouteTest {
 private fun name(s:String)=ByteArrayOutputStream().apply{s.split('.').forEach{write(it.length);write(it.toByteArray())};write(0)}.toByteArray()
 private fun response(zone:String,type:Int=6,rcode:Int=3):ByteArray {
  val q=DnsWire.healthQuery();val a=DnsWire.servfail(q).apply{this[3]=(0x80 or rcode).toByte();this[9]=1}
  val data=if(type==5)name(zone)else name("ns.example")+name("hostmaster.example")+ByteArray(20)
  val owner=if(type==5)byteArrayOf(0xc0.toByte(),12)else name(zone)
  return a+owner+byteArrayOf(0,type.toByte(),0,1,0,0,0,60,(data.size ushr 8).toByte(),data.size.toByte())+data
 }
 @Test fun exactProAndTifMarkersIdentifyNxdomainSoaAndCname(){
  for(type in listOf(5,6)){
   assertEquals(DnsWire.BlockCategory.PRO,DnsWire.blockCategory(response("pro.rpz.dnsbunker.org",type)))
   assertEquals(DnsWire.BlockCategory.TIF,DnsWire.blockCategory(response("tif.rpz.dnsbunker.org",type)))
  }
 }
 @Test fun recordedDnsBunkerSoaWithoutEdeCountsAsPro(){
  val hex=javaClass.getResource("/pro-nxdomain.hex")!!.readText().trim()
  val wire=hex.chunked(2).map{it.toInt(16).toByte()}.toByteArray()
  assertFalse(DnsWire.hasBlockingEde(wire));assertEquals(DnsWire.BlockCategory.PRO,DnsWire.blockCategory(wire))
 }
 @Test fun ordinaryNxdomainSpoofedSuffixAndSuccessfulCnameAreNotBlocks(){
  assertEquals(DnsWire.BlockCategory.NONE,DnsWire.blockCategory(DnsWire.servfail(DnsWire.healthQuery()).apply{this[3]=0x83.toByte()}))
  assertEquals(DnsWire.BlockCategory.NONE,DnsWire.blockCategory(response("pro.rpz.dnsbunker.org.evil")))
  assertEquals(DnsWire.BlockCategory.NONE,DnsWire.blockCategory(response("pro.rpz.dnsbunker.org",5,0)))
  assertEquals(DnsWire.BlockCategory.NONE,DnsWire.blockCategory(response("tif.rpz.dnsbunker.org",5).dropLast(1).toByteArray()))
 }
 @Test fun warmAverageExcludesColdAndKeepsActualTotalWait(){
  AppState.resetStatistics();AppState.recordQuery(1200,false,timing=QueryTiming(true,1000,1200))
  AppState.recordQuery(55,false,timing=QueryTiming(false,0,45));val s=AppState.snapshot.value
  assertEquals(2L,s.queries);assertEquals(1L,s.warmSamples);assertEquals(45L,s.warmTotalMillis);assertEquals(1000L,s.lastSetupMillis);assertEquals(55L,s.lastLatencyMillis)
  AppState.resetStatistics();assertEquals(0L,AppState.snapshot.value.warmSamples);assertNull(AppState.snapshot.value.lastSetupMillis)
 }
 @Test fun publicRoutesCoverBothFamiliesAndExcludeLan(){
  fun covered(ip:String):Boolean {val b=InetAddress.getByName(ip).address;return RoutePlan.publicRoutes.any{r->b.size==r.address.address.size&&BigInteger(1,b).shiftRight(b.size*8-r.prefix)==BigInteger(1,r.address.address).shiftRight(b.size*8-r.prefix)}}
  for(ip in listOf("8.8.8.8","203.0.113.53","2001:db8::53","2606:4700:4700::1111"))assertTrue(ip,covered(ip))
  for(ip in listOf("127.0.0.1","10.0.0.1","172.20.0.1","192.168.1.1","169.254.1.1","fd77::1","fe80::1","ff02::fb"))assertFalse(ip,covered(ip))
  assertTrue(RoutePlan.publicRoutes.size<200)
 }
 @Test fun selfCancelledAddressDoesNotHangOtherAttempts()=runBlocking{
  val result=withTimeout(1000){firstSuccess(listOf({throw CancellationException("one address canceled")},{42}),1)}
  assertEquals(42,result)
 }
}
