package org.dnsbunker.android
import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.dnsbunker.android.core.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class UiBehaviorTest:LiveDeviceFixture(){
 private val device get()=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
 private fun text(id:Int)=context.getString(id)
 private fun click(id:Int){assertNotNull(text(id),device.wait(Until.findObject(By.text(text(id))),5000));device.findObject(By.text(text(id))).click();device.waitForIdle()}
 @Test fun port53DialogCanBeCancelledWithoutChangingMode(){
  click(R.string.nav_settings)
  val toggle=device.wait(Until.findObject(By.clazz("android.widget.Switch")),5000)
   ?:device.findObject(By.checkable(true))
  assertNotNull("Port53 switch visible",toggle);toggle.click()
  assertNotNull(device.wait(Until.findObject(By.text(text(R.string.strict_confirm))),3000))
  click(R.string.cancel);assertFalse(UserSettings(context).current().interceptPublicDns)
 }
 @Test fun batteryExemptionRequiresSystemApprovalAndCanBeDeclined(){
  val power=context.getSystemService(PowerManager::class.java)
  Assume.assumeFalse("Already exempt before test",power.isIgnoringBatteryOptimizations(context.packageName))
  click(R.string.nav_settings);click(R.string.battery_action);click(R.string.activate_confirm)
  Thread.sleep(700);assertNotEquals("Android must handle exemption",context.packageName,device.currentPackageName)
  device.pressBack();device.waitForIdle()
  assertFalse("Test must not grant an exemption",power.isIgnoringBatteryOptimizations(context.packageName))
 }
 @Test fun officialPrivacyAndTermsLinksAreVisible(){
  click(R.string.nav_settings)
  repeat(5){
   if(device.findObject(By.text(text(R.string.privacy_policy)))==null)device.findObject(By.scrollable(true))?.scroll(Direction.DOWN,.7f)
  }
  assertNotNull(device.findObject(By.text(text(R.string.privacy_policy))))
  assertNotNull(device.findObject(By.text(text(R.string.terms))))
 }
}
