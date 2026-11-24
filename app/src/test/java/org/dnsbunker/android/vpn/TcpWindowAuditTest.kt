package org.dnsbunker.android.vpn
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.dnsbunker.android.core.DnsWire
import org.junit.Assert.*
import org.junit.Test
@OptIn(ExperimentalCoroutinesApi::class)
class TcpWindowAuditTest {
 private fun syn(port:Int=42000)=DnsTcpSegment(4,byteArrayOf(10,0,0,2),byteArrayOf(198.toByte(),18,0,1),port,53,100,0,DnsTcpPacket.SYN,65535,byteArrayOf())
 private fun parsed(b:ByteArray)=DnsTcpPacket.parse(b,b.size)!!
 @Test fun `zero receive window pauses response and reopening resumes with a bounded segment`()=runTest {
  val sent=mutableListOf<ByteArray>();val proxy=DnsTcpProxy(backgroundScope,{DnsWire.servfail(it)},{sent.add(it)})
  val c=syn();proxy.handle(c);val server=(parsed(sent.last()).sequence+1)and 0xffffffffL
  val q=DnsWire.healthQuery();val framed=byteArrayOf(0,q.size.toByte())+q
  val ack=c.copy(sequence=101,acknowledgement=server,flags=DnsTcpPacket.ACK,window=0,payload=framed)
  proxy.handle(ack);runCurrent()
  assertTrue(sent.none{parsed(it).payload.isNotEmpty()})
  proxy.handle(ack.copy(sequence=101L+framed.size,payload=byteArrayOf(),window=7));runCurrent()
  assertEquals(7,parsed(sent.last()).payload.size)
 }
 @Test fun `FIN is retransmitted until acknowledged without unbounded retries`()=runTest {
  val sent=mutableListOf<ByteArray>();val proxy=DnsTcpProxy(backgroundScope,{DnsWire.servfail(it)},{sent.add(it)})
  val c=syn();proxy.handle(c);val server=(parsed(sent.last()).sequence+1)and 0xffffffffL
  val q=DnsWire.healthQuery();val data=byteArrayOf(0,q.size.toByte())+q
  val ack=c.copy(sequence=101,acknowledgement=server,flags=DnsTcpPacket.ACK)
  proxy.handle(ack.copy(payload=data));runCurrent();val answer=parsed(sent.last())
  proxy.handle(ack.copy(sequence=101L+data.size,acknowledgement=(answer.sequence+answer.payload.size)and 0xffffffffL))
  val firstFin=parsed(sent.last());advanceTimeBy(5000);runCurrent()
  val fins=sent.map(::parsed).filter{it.flags and DnsTcpPacket.FIN!=0}
  assertEquals(4,fins.size);assertTrue(fins.all{it.sequence==firstFin.sequence})
 }
}
