package org.dnsbunker.android.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConcurrencyAuditTest {
 @Test fun `unvalidated internet remains selectable while restricted IMS and local USB do not`() {
  val wifi=UnderlayCandidate(1,true,false,false,true,true)
  val ims=UnderlayCandidate(2,false,true,false,false,false)
  val usb=UnderlayCandidate(3,false,true,false,false,true)
  assertEquals(1L,UnderlayPolicy.choose(listOf(ims,usb,wifi)))
  assertEquals(1L,UnderlayPolicy.choose(listOf(wifi.copy(validated=true)),1))
  assertEquals(1L,UnderlayPolicy.choose(listOf(wifi),1))
  assertNull(UnderlayPolicy.choose(listOf(ims,usb)))
 }
 @Test fun `captive network never evicts usable encrypted network`() {
  val captive=UnderlayCandidate(1,true,false,true,true,true)
  val mobile=UnderlayCandidate(2,false,true,false,true,true)
  assertEquals(2L,UnderlayPolicy.choose(listOf(captive,mobile)))
  assertEquals(1L,UnderlayPolicy.choose(listOf(captive)))
  assertNull(UnderlayPolicy.choose(listOf(captive.copy(vpn=true))))
 }
 @Test fun `circuit breaker prevents repeated QUIC timeouts and recovers after bounded cooldown`() {
  var now=0L;val policy=TransportPolicy {now}
  val names=listOf("DoH/3 · IP","DoH/2 · IP","DoT · IP")
  assertEquals("DoH/2 · IP",policy.order(names).first())
  policy.success(names[0],10);assertEquals(names[0],policy.order(names).first())
  policy.failure(names[0]);assertFalse(policy.canTry(names[0]));assertEquals(names[1],policy.order(names).first())
  now=4001;assertTrue(policy.canTry(names[0]));assertEquals(names[1],policy.order(names).first())
  policy.success(names[0],12);assertEquals(names[0],policy.order(names).first())
 }
 @Test fun `IPv6 winner does not wait for stalled IPv4 and loser is cancelled`() = runTest {
  var cancelled=false
  val answer=firstSuccess(listOf(suspend{try{delay(60000);"v4"}finally{cancelled=true}},suspend{"v6"}))
  assertEquals("v6",answer);assertEquals(200,currentTime);assertTrue(cancelled)
 }
 @Test fun `address races propagate failure and cancellation without stranded jobs`() = runTest {
  try{firstSuccess(listOf(suspend{throw java.io.IOException("v4")},suspend{throw java.io.IOException("v6")}));fail()}catch(_:java.io.IOException){}
  val job=launch {firstSuccess(listOf(suspend{awaitCancellation()},suspend{awaitCancellation()}))};runCurrent();job.cancelAndJoin()
  assertTrue(job.isCancelled)
 }
 @Test fun `parallel metrics preserve exact totals and immutable snapshots`() = runBlocking {
  AppState.resetStatistics();val before=AppState.snapshot.value
  coroutineScope {repeat(16){launch(Dispatchers.Default){repeat(250){AppState.recordQuery(10,it%5==0)}}}}
  val result=AppState.snapshot.value
  assertEquals(4000,result.queries);assertEquals(800,result.blocked);assertEquals(40000,result.latencyTotalMillis)
  assertEquals(4000,result.recentActivity.sum());assertEquals(0,before.recentActivity.sum())
  AppState.resetStatistics();assertEquals(0,AppState.snapshot.value.queries)
 }
 @Test fun `circuit state stays bounded under simultaneous health and query updates`() = runBlocking {
  val policy=TransportPolicy();coroutineScope {repeat(16){launch(Dispatchers.Default){repeat(1000){if(it%3==0)policy.failure("DoQ")else policy.success("DoQ",3);policy.order(listOf("DoQ","DoH/2 · IP"))}}}}
  assertTrue(policy.state("DoQ").failures in 0..6)
 }
}
