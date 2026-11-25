package org.dnsbunker.android.vpn

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import org.dnsbunker.android.MainActivity
import org.dnsbunker.android.R
import org.dnsbunker.android.core.AppState
import org.dnsbunker.android.core.ProtectionStatus

object TileRefresher {
    fun refresh(context: Context) {
        if (Build.VERSION.SDK_INT >= 24) {
            runCatching { TileService.requestListeningState(context, ComponentName(context, QuickTile::class.java)) }
        }
    }
}

@RequiresApi(24)
class QuickTile : TileService() {
    private fun running() = AppState.snapshot.value.status.let { it != ProtectionStatus.OFF && it != ProtectionStatus.ERROR }

    private fun paused() = AppState.snapshot.value.status == ProtectionStatus.PAUSED

    override fun onStartListening() = show(running() && !paused(), paused())

    private fun show(active: Boolean, paused: Boolean = false) {
        val tile = qsTile ?: return
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (paused) getString(R.string.tile_paused) else null
        tile.updateTile()
    }

    override fun onClick() {
        if (paused()) {
            ContextCompat.startForegroundService(this, Intent(this, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_RESUME))
            show(true)
        } else if (running()) {
            startService(Intent(this, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_STOP))
            show(false)
        } else if (VpnService.prepare(this) == null) {
            ContextCompat.startForegroundService(this, Intent(this, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_START))
            show(true)
        } else {
            val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
                startActivityAndCollapse(intent)
            }
        }
    }
}
