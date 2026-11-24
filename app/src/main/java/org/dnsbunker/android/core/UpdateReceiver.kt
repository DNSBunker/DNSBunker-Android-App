package org.dnsbunker.android.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat

class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                try { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (_: Exception) { AppState.update { it.copy(updateState = UpdateState.FAILED) } }
            }
            PackageInstaller.STATUS_SUCCESS -> AppState.update { it.copy(updateState = UpdateState.LATEST, updateVersion = null) }
            else -> AppState.update { it.copy(updateState = UpdateState.FAILED) }
        }
    }
}
