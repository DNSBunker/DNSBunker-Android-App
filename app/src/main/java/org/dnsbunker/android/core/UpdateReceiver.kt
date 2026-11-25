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
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    ?.let { Updater.trustedConfirmation(context, it) }
                if (confirm == null) {
                    Updater.finished(context)
                    AppState.update { it.copy(updateState = UpdateState.FAILED) }
                } else {
                    Updater.requestConfirmation(context, confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Updater.finished(context)
                AppState.update { it.copy(updateState = UpdateState.LATEST, updateVersion = null, updateNotes = null) }
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Updater.finished(context)
                AppState.update { it.copy(updateState = if (it.updateVersion != null) UpdateState.AVAILABLE else UpdateState.LATEST) }
            }
            else -> {
                Updater.finished(context)
                AppState.update { it.copy(updateState = UpdateState.FAILED) }
            }
        }
    }
}
