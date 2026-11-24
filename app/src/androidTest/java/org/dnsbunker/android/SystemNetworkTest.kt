package org.dnsbunker.android

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.dnsbunker.android.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemNetworkTest : LiveDeviceFixture() {
 @Test fun ordinaryAppUsesSystemDnsAndHttpsThroughValidatedVpn() {
  val before=AppState.snapshot.value.queries
  shell("am force-stop org.dnsbunker.android.networkprobe")
  shell("am start -W -n org.dnsbunker.android.networkprobe/.ProbeActivity")
  val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
  val deadline=System.nanoTime()+30_000_000_000L;var text=""
  while(System.nanoTime()<deadline) {
   val label=device.findObject(By.textContains("PROBE_RESULT"))
   if(label!=null){text=label.text;break}
   Thread.sleep(200)
  }
  println(text)
  assertTrue(text,text.contains("VPN=true"));assertTrue(text,text.contains("HTTPS=200"))
  assertTrue(text,text.contains("DNS_A="));assertTrue(text,text.contains("DNS_AAAA="))
  assertTrue("Normal app DNS must be intercepted",AppState.snapshot.value.queries>=before+2)
  val validatedDeadline=System.nanoTime()+20_000_000_000L
  while(!AppState.snapshot.value.androidValidated&&System.nanoTime()<validatedDeadline)Thread.sleep(200)
  assertTrue("Android VPN internet validation must succeed",AppState.snapshot.value.androidValidated)
  context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
 }
}
