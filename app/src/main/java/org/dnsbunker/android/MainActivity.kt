package org.dnsbunker.android

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.location.LocationManager
import android.net.Uri
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import org.dnsbunker.android.core.AppState
import org.dnsbunker.android.core.DnsSettings
import org.dnsbunker.android.core.ProtectionStatus
import org.dnsbunker.android.core.UserSettings
import org.dnsbunker.android.core.WifiPolicy
import org.dnsbunker.android.core.UpdateState
import org.dnsbunker.android.core.Updater
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dnsbunker.android.ui.DnsBunkerScreen
import org.dnsbunker.android.ui.DnsBunkerTheme
import org.dnsbunker.android.ui.ScreenActions
import org.dnsbunker.android.vpn.DnsVpnService

class MainActivity : ComponentActivity() {
    private lateinit var settings: UserSettings
    private var pendingWifiAction: String = "exempt"

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) sendVpn(DnsVpnService.ACTION_START)
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { prepareVpn() }

    private val backgroundLocationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshAndroidState()
        refreshVpnIfRunning()
    }

    private val wifiPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        refreshAndroidState()
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) addCurrentWifi(pendingWifiAction)
        else Toast.makeText(this, R.string.wifi_unavailable, Toast.LENGTH_LONG).show()
    }

    private val dnsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refreshAndroidState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        settings = UserSettings(this)
        contentResolver.registerContentObserver(Settings.Global.getUriFor("private_dns_mode"), false, dnsObserver)
        val actions = ScreenActions(
            toggleProtection = ::toggleProtection,
            setPadding = { enabled -> change { it.copy(ednsPaddingEnabled = enabled) } },
            setWatchdog = { enabled -> change { it.copy(watchdogEnabled = enabled) } },
            setDotFallback = { enabled -> change { it.copy(fallbackDoT = enabled) } },
            setSplitDns = { enabled -> change { it.copy(splitDnsEnabled = enabled) } },
            setRouterDns = { enabled -> change { it.copy(allowLocalPlaintext = enabled) } },
            setInterception = { enabled -> change { it.copy(interceptPublicDns = enabled) } },
            addExemptWifi = { addCurrentWifi("exempt") },
            removeExemptWifi = { name -> change { it.copy(exemptSsids = it.exemptSsids - name) } },
            addTrustedWifi = { addCurrentWifi("trusted") },
            removeTrustedWifi = { name -> change { it.copy(trustedLocalDnsSsids = it.trustedLocalDnsSsids - name) } },
            openBatterySettings = {
                launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            },
            openPrivateDnsSettings = { launch(Intent(Settings.ACTION_WIRELESS_SETTINGS), Intent(Settings.ACTION_SETTINGS)) },
            openCaptivePortal = { launch(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY), Intent(Settings.ACTION_WIFI_SETTINGS)) },
            allowBackgroundLocation = {
                if (Build.VERSION.SDK_INT >= 29) backgroundLocationPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            },
            setAutoUpdate = { enabled -> change { it.copy(updateCheck = enabled) }; if (enabled) checkUpdates(force = true) },
            checkUpdates = { checkUpdates(force = true) },
            installUpdate = ::installUpdate,
            openVpnSettings = { launch(Intent(Settings.ACTION_VPN_SETTINGS), Intent(Settings.ACTION_WIRELESS_SETTINGS)) },
            runDiagnostics = { sendVpn(DnsVpnService.ACTION_DIAGNOSTICS) },
        )
        setContent {
            val status by AppState.snapshot.collectAsState()
            val preferences by settings.value.collectAsState()
            DnsBunkerTheme { DnsBunkerScreen(status = status, preferences = preferences, actions = actions) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshAndroidState()
        checkUpdates(force = intent?.getBooleanExtra(DnsVpnService.EXTRA_CHECK_UPDATE, false) == true)
        intent?.removeExtra(DnsVpnService.EXTRA_CHECK_UPDATE)
        if (AppState.snapshot.value.wifiLocationReady) refreshVpnIfRunning()
    }

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(dnsObserver)
        super.onDestroy()
    }

    private fun refreshAndroidState() {
        val power = getSystemService(POWER_SERVICE) as PowerManager
        val mode = Settings.Global.getString(contentResolver, "private_dns_mode")
        val location = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            LocationManagerCompat.isLocationEnabled(getSystemService(LOCATION_SERVICE) as LocationManager)
        val background = Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        AppState.update { it.copy(batteryExempt = Build.VERSION.SDK_INT < 23 || power.isIgnoringBatteryOptimizations(packageName),
            privateDnsStrict = mode == "hostname", wifiLocationReady = location, backgroundLocation = background) }
    }

    private fun checkUpdates(force: Boolean) {
        val busy = AppState.snapshot.value.updateState.let { it == UpdateState.CHECKING || it == UpdateState.DOWNLOADING || it == UpdateState.INSTALLING }
        if (busy) return
        if (!force && (!settings.current().updateCheck || System.currentTimeMillis() - settings.lastUpdateCheck() < 6 * 3_600_000L)) return
        lifecycleScope.launch {
            AppState.update { it.copy(updateState = UpdateState.CHECKING) }
            try {
                val release = Updater.check()
                settings.setLastUpdateCheck(System.currentTimeMillis())
                AppState.update { it.copy(updateState = if (release != null) UpdateState.AVAILABLE else UpdateState.LATEST, updateVersion = release?.version) }
            } catch (c: CancellationException) { throw c } catch (_: Exception) {
                AppState.update { it.copy(updateState = UpdateState.FAILED) }
            }
        }
    }

    private fun installUpdate() {
        val release = Updater.available ?: return checkUpdates(force = true)
        if (!Updater.canInstall(this)) {
            AppState.update { it.copy(updateState = UpdateState.NEEDS_PERMISSION) }
            launch(Updater.installPermissionIntent(this), Intent(Settings.ACTION_SECURITY_SETTINGS))
            return
        }
        lifecycleScope.launch {
            AppState.update { it.copy(updateState = UpdateState.DOWNLOADING) }
            try {
                val apk = Updater.download(this@MainActivity, release)
                AppState.update { it.copy(updateState = UpdateState.INSTALLING) }
                withContext(Dispatchers.IO) { Updater.install(this@MainActivity, apk) }
            } catch (c: CancellationException) { throw c } catch (_: Exception) {
                AppState.update { it.copy(updateState = UpdateState.FAILED) }
            }
        }
    }

    private fun change(transform: (DnsSettings) -> DnsSettings) {
        settings.update(transform)
        refreshVpnIfRunning()
    }

    private fun launch(primary: Intent, fallback: Intent) {
        try { startActivity(primary) } catch (_: ActivityNotFoundException) {
            try { startActivity(fallback) } catch (_: ActivityNotFoundException) { }
        } catch (_: SecurityException) {
            try { startActivity(fallback) } catch (_: ActivityNotFoundException) { }
        }
    }

    private fun toggleProtection() {
        val status = AppState.snapshot.value.status
        if (status == ProtectionStatus.OFF || status == ProtectionStatus.ERROR) requestVpn()
        else sendVpn(DnsVpnService.ACTION_STOP)
    }

    private fun requestVpn() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else prepareVpn()
    }

    private fun prepareVpn() {
        val intent = try { VpnService.prepare(this) } catch (_: Exception) { null }
        if (intent == null) sendVpn(DnsVpnService.ACTION_START)
        else try { vpnConsent.launch(intent) } catch (_: ActivityNotFoundException) {
            AppState.update { it.copy(status = ProtectionStatus.ERROR, detail = getString(R.string.vpn_error_detail)) }
        }
    }

    private fun sendVpn(action: String) {
        val intent = Intent(this, DnsVpnService::class.java).setAction(action)
        if (action == DnsVpnService.ACTION_STOP) startService(intent)
        else ContextCompat.startForegroundService(this, intent)
    }

    private fun refreshVpnIfRunning() {
        val status = AppState.snapshot.value.status
        if (status != ProtectionStatus.OFF && status != ProtectionStatus.ERROR) sendVpn(DnsVpnService.ACTION_REFRESH)
    }

    private fun addCurrentWifi(action: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            pendingWifiAction = action
            wifiPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val name = AppState.snapshot.value.currentWifi ?: WifiPolicy.normalizeSsid(wifi.connectionInfo?.ssid)
        if (name == null) {
            Toast.makeText(this, R.string.wifi_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        if (action == "trusted") change { it.copy(trustedLocalDnsSsids = it.trustedLocalDnsSsids + name) }
        else change { it.copy(exemptSsids = it.exemptSsids + name) }
    }
}
