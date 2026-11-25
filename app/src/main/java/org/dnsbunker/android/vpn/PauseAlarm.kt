package org.dnsbunker.android.vpn

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

object PauseAlarm {
    private fun intent(context: Context) = PendingIntent.getBroadcast(context, 30, Intent(context, PauseReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun schedule(context: Context, at: Long) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= 23) alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(context))
        else alarms.set(AlarmManager.RTC_WAKEUP, at, intent(context))
    }

    fun cancel(context: Context) {
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(intent(context))
    }
}

class PauseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try { context.startService(Intent(context, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_REFRESH)) } catch (_: Exception) { }
    }
}
