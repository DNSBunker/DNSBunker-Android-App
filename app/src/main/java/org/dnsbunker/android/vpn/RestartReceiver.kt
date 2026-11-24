package org.dnsbunker.android.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import org.dnsbunker.android.core.UserSettings

class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!UserSettings(context).protectionWanted()) return
        if (VpnService.prepare(context) != null) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_START))
        } catch (_: Exception) {
        }
    }
}
