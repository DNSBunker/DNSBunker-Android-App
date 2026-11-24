package org.dnsbunker.android.core
import org.junit.Assert.*
import org.junit.Test
class WireAuditTest {
 @Test fun `EDNS extended errors do not confirm a working resolver`() {
  val q=DnsWire.healthQuery()
  val a=DnsWire.servfail(q).apply{this[3]=0x80.toByte();this[7]=1;this[11]=1}+
   byteArrayOf(0xc0.toByte(),12,0,1,0,1,0,0,0,60,0,4,93,184.toByte(),216.toByte(),34)+
   byteArrayOf(0,0,41,4,0xd0.toByte(),1,0,0,0,0,0)
  assertEquals(16,DnsWire.responseCode(a));assertFalse(DnsWire.healthyResponse(q,a))
 }
 @Test fun `compression pointer cycles and invalid expanded names are rejected`() {
  val q=DnsWire.healthQuery()
  val a=DnsWire.servfail(q).apply{this[7]=1}
  val bad=a+byteArrayOf(0xc0.toByte(),(a.size+2).toByte(),0xc0.toByte(),a.size.toByte())+ByteArray(10)
  assertFalse(DnsWire.validResponse(q,bad))
  val long=byteArrayOf(63)+ByteArray(63){97}+byteArrayOf(63)+ByteArray(63){97}+byteArrayOf(63)+ByteArray(63){97}+byteArrayOf(63)+ByteArray(63){97}+byteArrayOf(0)
  val answer=a+long+ByteArray(10)
  assertFalse(DnsWire.validResponse(q,answer))
 }
}
