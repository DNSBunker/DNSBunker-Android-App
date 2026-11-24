package org.dnsbunker.android.core
import android.net.Network
import android.net.VpnService
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object NativeFullTunnel {
 private data class Callbacks(val service:VpnService,val network:Network?,val output:(ByteArray)->Unit,val dns:suspend(ByteArray,Boolean,Int)->ByteArray)
 private val next=AtomicLong(0)
 private val sessions=ConcurrentHashMap<Long,Callbacks>()
 @JvmStatic external fun nativeStart(token:Long,mtu:Int):Boolean
 @JvmStatic external fun nativeInput(token:Long,packet:ByteArray,length:Int):Boolean
 @JvmStatic external fun nativeStop(token:Long)
 @JvmStatic fun protectSocket(token:Long,fd:Int):Boolean {
  val c=sessions[token]?:return false;val network=c.network?:return false
  return try{ParcelFileDescriptor.fromFd(fd).use {p->if(!c.service.protect(p.fd))false else{Compat.bind(network,p.fileDescriptor);true}}}catch(_:Exception){false}
 }
 @JvmStatic fun emitPacket(token:Long,packet:ByteArray):Boolean {
  val c=sessions[token]?:return false
  return try{c.output(packet);true}catch(_:Exception){false}
 }
 @JvmStatic fun resolveDns(token:Long,query:ByteArray,udp:Boolean,port:Int):ByteArray? {
  val c=sessions[token]?:return null
  if(!DnsWire.validQuery(query))return null
  return try{runBlocking{c.dns(query,udp,port)}}catch(_:Exception){null}
 }
 class Session internal constructor(val token:Long):Closeable {
  @Volatile private var closed=false
  fun input(packet:ByteArray,length:Int){if(!closed)nativeInput(token,packet,length)}
  override fun close(){if(!closed){closed=true;sessions.remove(token);nativeStop(token)}}
 }
 fun start(service:VpnService,network:Network?,output:(ByteArray)->Unit,dns:suspend(ByteArray,Boolean,Int)->ByteArray):Session {
  if(!NativeQuic.available)throw IOException("Native IP stack unavailable")
  val token=next.incrementAndGet();sessions[token]=Callbacks(service,network,output,dns)
  val ready=try{nativeStart(token,1500)}catch(error:LinkageError){sessions.remove(token);throw IOException("Native IP stack unavailable",error)}
  if(!ready){sessions.remove(token);throw IOException("Unable to initialize IP stack")}
  return Session(token)
 }
}
