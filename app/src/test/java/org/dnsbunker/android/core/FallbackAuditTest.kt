package org.dnsbunker.android.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.dnsbunker.android.vpn.DnsVpnService

@RunWith(RobolectricTestRunner::class) @Config(sdk=[29])
class FallbackAuditTest {
 private fun answer(q:ByteArray):ByteArray {
  val a=DnsWire.servfail(q).apply{this[3]=0x80.toByte();this[7]=1}
  return a+byteArrayOf(0xc0.toByte(),12,0,1,0,1,0,0,0,60,0,4,93,184.toByte(),216.toByte(),34)
 }
 private class Fake(override val name:String,val result:suspend(ByteArray)->ByteArray):DnsTransport {
  var calls=0;var closed=false
  override suspend fun query(message:ByteArray):ByteArray{calls++;return result(message)}
  override fun close(){closed=true}
 }
 @Test fun `UDP blocked after successful QUIC warmup falls back to verified HTTPS only`() = runBlocking {
  val controller=Robolectric.buildService(DnsVpnService::class.java).create();var blocked=false
  val quic=Fake("DoH/3 · IP"){if(blocked)throw java.io.IOException("UDP blocked")else answer(it)}
  val https=Fake("DoH/2 · IP"){answer(it)}
  val client=DnsClient(controller.get(),null,DnsSettings(),transportOverride=listOf(quic,https))
  try {
   assertTrue(client.probeTransport(quic.name));blocked=true
   val q=DnsWire.healthQuery();assertTrue(DnsWire.healthyResponse(q,client.resolve(q,false)))
   assertEquals(https.name,client.lastTransport);val calls=quic.calls
   client.resolve(q,false);assertEquals(calls,quic.calls)
  }finally{client.close();controller.destroy()}
  assertTrue(quic.closed&&https.closed)
 }
 @Test fun `cancellation is never converted into fallback or a failed user query`() = runBlocking {
  val controller=Robolectric.buildService(DnsVpnService::class.java).create()
  val first=Fake("DoH/2 · IP"){throw CancellationException("stop")};val second=Fake("DoT · IP"){answer(it)}
  val client=DnsClient(controller.get(),null,DnsSettings(),transportOverride=listOf(first,second))
  val failures=AppState.snapshot.value.failures
  try {try{client.resolve(DnsWire.healthQuery());fail()}catch(_:CancellationException){};assertEquals(0,second.calls);assertEquals(failures,AppState.snapshot.value.failures)}
  finally{client.close();controller.destroy()}
 }
 @Test fun `late reply from closed network cannot be forwarded or create another connection`() = runBlocking {
  val controller=Robolectric.buildService(DnsVpnService::class.java).create();val pending=CompletableDeferred<ByteArray>()
  val first=Fake("DoH/2 · IP"){pending.await()};val second=Fake("DoT · IP"){answer(it)}
  val client=DnsClient(controller.get(),null,DnsSettings(),transportOverride=listOf(first,second));val q=DnsWire.healthQuery()
  try {
   val job=async {runCatching{client.resolve(q,false)}};yield();client.close();pending.complete(answer(q))
   assertTrue(job.await().isFailure);assertEquals(0,second.calls)
  }finally{client.close();controller.destroy()}
 }
}
