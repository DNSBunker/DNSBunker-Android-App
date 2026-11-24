package org.dnsbunker.android

import android.net.*
import android.os.ParcelFileDescriptor
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.dnsbunker.android.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.*
import java.io.IOException
import java.security.KeyStore

@RunWith(AndroidJUnit4::class)
class QuicSecurityTest : LiveDeviceFixture() {
 private class Attached(context:android.content.Context):VpnService(){init{attachBaseContext(context)}}
 @Test fun untrustedCertificateIsRejectedOnNativeHttp3()=runBlocking {
  val network=underlay();val service=Attached(context)
  val q=DnsWire.healthQuery()
  QuicBackend(service,network).use {assertTrue(DnsWire.healthyResponse(q,it.query(1,true,q)))}
  val store=KeyStore.getInstance("AndroidCAStore").apply{load(null)}
  val aliases=store.aliases();var pem:String?=null
  while(aliases.hasMoreElements()) {
   val alias=aliases.nextElement();val certificate=store.getCertificate(alias) as? java.security.cert.X509Certificate?:continue
   if(alias.startsWith("system:")&&certificate.subjectX500Principal.name.contains("DigiCert")) {
    pem="-----BEGIN CERTIFICATE-----\n"+Base64.encodeToString(certificate.encoded,Base64.NO_WRAP)+"\n-----END CERTIFICATE-----\n";break
   }
  }
  assertNotNull("Unrelated system CA required for negative test",pem)
  DatagramSocket(null).use {socket->
   socket.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"),0))
   assertTrue(service.protect(socket));network.bindSocket(socket)
   val fd=ParcelFileDescriptor.fromDatagramSocket(socket).detachFd()
   val handle=NativeQuic.nativeCreate(fd,"185.250.250.61","185.250.250.61",pem!!)
   assertTrue(handle!=0L)
   try {
    try{NativeQuic.nativeQuery(handle,1,q,4000);fail("Untrusted certificate accepted")}
    catch(error:IOException){assertTrue(error.message.orEmpty(),error.message.orEmpty().contains("certificate",ignoreCase=true))}
   }finally{NativeQuic.nativeClose(handle)}
  }
 }
 @Test fun repeatedQuicSessionCloseDoesNotLeakSocketDescriptors()=runBlocking {
  val network=underlay();val service=Attached(context);Thread.sleep(1000)
  val before=java.io.File("/proc/self/fd").list()!!.size
  repeat(8){QuicBackend(service,network).use {backend->val q=DnsWire.healthQuery();assertTrue(DnsWire.healthyResponse(q,backend.query(0,true,q)))}}
  Thread.sleep(500)
  val after=java.io.File("/proc/self/fd").list()!!.size
  println("QUIC_FD before=$before after=$after")
  assertTrue("socket descriptors must remain bounded",after<=before+8)
 }
}
