package org.dnsbunker.android.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

data class DnsSettings(
    val exemptSsids: Set<String> = emptySet(),
    val trustedLocalDnsSsids: Set<String> = emptySet(),
    val splitDnsEnabled: Boolean = true,
    val splitDnsSuffixes: String = "home.arpa",
    val watchdogEnabled: Boolean = true,
    val ednsPaddingEnabled: Boolean = true,
    val allowLocalPlaintext: Boolean = false,
    val fallbackDoT: Boolean = true,
    val interceptPublicDns: Boolean = false,
    val updateCheck: Boolean = true,
    val excludedApps: Set<String> = emptySet(),
    val rememberBlocked: Boolean = false,
    val captiveAuto: Boolean = true,
)

class UserSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("dnsbunker_preferences", Context.MODE_PRIVATE)
    val value = MutableStateFlow(read())

    fun current(): DnsSettings = read().also { value.value = it }

    fun protectionWanted(): Boolean = prefs.getBoolean("protection_wanted", false)

    fun setProtectionWanted(wanted: Boolean) {
        prefs.edit().putBoolean("protection_wanted", wanted).apply()
    }

    fun pausedUntil(): Long = prefs.getLong("paused_until", 0)
    fun setPausedUntil(time: Long) { prefs.edit().putLong("paused_until", time).apply() }

    fun lastUpdateCheck(): Long = prefs.getLong("update_last_check", 0)
    fun setLastUpdateCheck(time: Long) { prefs.edit().putLong("update_last_check", time).apply() }
    fun notifiedUpdate(): String? = prefs.getString("update_notified", null)
    fun setNotifiedUpdate(version: String) { prefs.edit().putString("update_notified", version).apply() }

    private fun read() = DnsSettings(
        exemptSsids = prefs.getStringSet("exempt_ssids", emptySet())?.toSet() ?: emptySet(),
        trustedLocalDnsSsids = prefs.getStringSet("trusted_local_ssids", emptySet())?.toSet() ?: emptySet(),
        splitDnsEnabled = prefs.getBoolean("split_dns", true),
        splitDnsSuffixes = prefs.getString("split_suffixes", "home.arpa") ?: "home.arpa",
        watchdogEnabled = prefs.getBoolean("watchdog", true),
        ednsPaddingEnabled = prefs.getBoolean("padding", true),
        allowLocalPlaintext = prefs.getBoolean("local_plaintext", false),
        fallbackDoT = prefs.getBoolean("dot_fallback", true),
        interceptPublicDns = prefs.getBoolean("intercept_public_dns",false),
        updateCheck = prefs.getBoolean("update_check", true),
        excludedApps = prefs.getStringSet("excluded_apps", emptySet())?.toSet() ?: emptySet(),
        rememberBlocked = prefs.getBoolean("remember_blocked", false),
        captiveAuto = prefs.getBoolean("captive_auto", true),
    )

    fun update(transform: (DnsSettings) -> DnsSettings) {
        val next = transform(value.value)
        prefs.edit()
            .putStringSet("exempt_ssids", next.exemptSsids)
            .putStringSet("trusted_local_ssids", next.trustedLocalDnsSsids)
            .putBoolean("split_dns", next.splitDnsEnabled)
            .putString("split_suffixes", next.splitDnsSuffixes)
            .putBoolean("watchdog", next.watchdogEnabled)
            .putBoolean("padding", next.ednsPaddingEnabled)
            .putBoolean("local_plaintext", next.allowLocalPlaintext)
            .putBoolean("dot_fallback", next.fallbackDoT)
            .putBoolean("intercept_public_dns",next.interceptPublicDns)
            .putBoolean("update_check", next.updateCheck)
            .putStringSet("excluded_apps", next.excludedApps)
            .putBoolean("remember_blocked", next.rememberBlocked)
            .putBoolean("captive_auto", next.captiveAuto)
            .remove("domain_history")
            .apply()
        value.value = next
    }
}
