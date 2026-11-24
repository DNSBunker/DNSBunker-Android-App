package org.dnsbunker.android.vpn

import android.Manifest
import org.dnsbunker.android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.core.app.ServiceCompat
import androidx.core.location.LocationManagerCompat
import org.dnsbunker.android.core.Compat
import org.dnsbunker.android.core.UpdateState
import org.dnsbunker.android.core.Updater
import androidx.core.content.ContextCompat
import org.dnsbunker.android.MainActivity
import org.dnsbunker.android.core.AppState
import org.dnsbunker.android.core.DnsClient
import org.dnsbunker.android.core.DnsWire
import org.dnsbunker.android.core.ProtectionStatus
import org.dnsbunker.android.core.UserSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Semaphore
import org.dnsbunker.android.core.WifiPolicy
import org.dnsbunker.android.core.UnderlayPolicy
import org.dnsbunker.android.core.UnderlayCandidate
import org.dnsbunker.android.core.TunHealthProbe
import org.dnsbunker.android.core.NativeFullTunnel
import org.dnsbunker.android.core.NativeQuic
import org.dnsbunker.android.core.RoutePlan

class DnsVpnService : VpnService() {
    companion object {
        const val ACTION_START = "org.dnsbunker.android.START"
        const val ACTION_STOP = "org.dnsbunker.android.STOP"
        const val ACTION_DIAGNOSTICS = "org.dnsbunker.android.DIAGNOSTICS"
        const val ACTION_REFRESH = "org.dnsbunker.android.REFRESH"
        private const val CHANNEL_ID = "dnsbunker_vpn"
        private const val NOTIFICATION_ID = 1001
        private const val WARNING_CHANNEL_ID = "dnsbunker_warnings"
        private const val WARNING_ID = 1002
        private const val UPDATE_CHANNEL_ID = "dnsbunker_updates"
        private const val UPDATE_ID = 1003
        const val EXTRA_CHECK_UPDATE = "org.dnsbunker.android.CHECK_UPDATE"
        private const val DNS_V4 = "198.18.0.1"
        private const val TUN_V4 = "198.18.0.2"
        private const val DNS_V6 = "fd77:db4b::1"
        private const val TUN_V6 = "fd77:db4b::2"
    }

    @Volatile private var stopping = false
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private val reconcileMutex = Mutex()
    private val outputLock=Any()
    @Volatile private var fullStack:NativeFullTunnel.Session?=null
    private var tunnelIntercepts=false
    private var tunnelUnderlay:Network?=null
    private val networkEpoch = AtomicLong(0)
    private val capabilities = ConcurrentHashMap<Network, NetworkCapabilities>()
    private val networkManager by lazy { getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager }
    private val settings by lazy { UserSettings(this) }
    private val virtualDns4 by lazy { InetAddress.getByName(DNS_V4).address }
    private val virtualDns6 by lazy { InetAddress.getByName(DNS_V6).address }
    @Volatile private var tunnel: ParcelFileDescriptor? = null
    private val sessionStreams=ConcurrentHashMap<ParcelFileDescriptor,Pair<FileInputStream,FileOutputStream>>()
    private var readJob: Job? = null
    private var tunnelNeedsRecovery = false
    private var recoveryScheduled = false
    private var watchdogJob: Job? = null
    private var healthJob: Job? = null
    private var resolverProfile: org.dnsbunker.android.core.DnsSettings? = null
    private var resolverNetwork: Network? = null
    private var resolverLocal = false
    private var vpnNetwork: Network? = null
    private var networkChangedJob: Job? = null
    private val resolverRef=AtomicReference<DnsClient?>(null)
    private var resolver:DnsClient?
        get()=resolverRef.get()
        set(value){val old=resolverRef.getAndSet(value);if(old!==value)old?.close()}
    private fun invalidateResolver(network:Network) {
        val old=resolverRef.get()?:return
        if(old.networkHandle==Compat.id(network) && resolverRef.compareAndSet(old,null))scope.launch {old.close()}
    }
    @Volatile private var activeNetwork: Network? = null

    private val knownSsid = ConcurrentHashMap<Network, String>()
    @Volatile private var locationForeground = false

    private fun underlayLost(network: Network) {
        capabilities.remove(network)
        knownSsid.remove(network)
        invalidateResolver(network)
        scheduleNetworkChange()
    }
    private fun underlayChanged(network: Network, caps: NetworkCapabilities) {
        val before = knownSsid[network]
        val old = capabilities.put(network, caps)
        val after = rememberSsid(network, caps)
        if (old == null || meaningfulChange(old, caps) || before != after) scheduleNetworkChange()
    }
    private fun newNetworkCallback(): ConnectivityManager.NetworkCallback =
        if (Build.VERSION.SDK_INT >= 31) {
            object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                override fun onAvailable(network: Network) = scheduleNetworkChange()
                override fun onLost(network: Network) = underlayLost(network)
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = underlayChanged(network, caps)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = scheduleNetworkChange()
                override fun onLost(network: Network) = underlayLost(network)
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = underlayChanged(network, caps)
            }
        }
    private var networkCallback: ConnectivityManager.NetworkCallback = newNetworkCallback()
    private val underlayRequest by lazy { NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build() }

    private val lockdownEnabled: Boolean get() = Build.VERSION.SDK_INT >= 29 && isLockdownEnabled

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.channel), NotificationManager.IMPORTANCE_LOW))
    }

    private fun warnRevoked() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            Build.VERSION.SDK_INT >= 33) return
        if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(WARNING_CHANNEL_ID, getString(R.string.warning_channel), NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val warning = NotificationCompat.Builder(this, WARNING_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(getString(R.string.revoked_title))
            .setContentText(getString(R.string.revoked_body))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.revoked_body)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(this).notify(WARNING_ID, warning) } catch (_: SecurityException) { }
    }

    private suspend fun checkForUpdate() {
        if (!settings.current().updateCheck || System.currentTimeMillis() - settings.lastUpdateCheck() < 24 * 3_600_000L) return
        val release = try { Updater.check() } catch (c: CancellationException) { throw c } catch (_: Exception) { return }
        settings.setLastUpdateCheck(System.currentTimeMillis())
        if (release == null || settings.notifiedUpdate() == release.version) return
        AppState.update { it.copy(updateState = UpdateState.AVAILABLE, updateVersion = release.version) }
        settings.setNotifiedUpdate(release.version)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(UPDATE_CHANNEL_ID, getString(R.string.update_channel), NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java).putExtra(EXTRA_CHECK_UPDATE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = getString(R.string.update_notification, release.version)
        val note = NotificationCompat.Builder(this, UPDATE_CHANNEL_ID).setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("DNSBunker").setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true).build()
        try { NotificationManagerCompat.from(this).notify(UPDATE_ID, note) } catch (_: SecurityException) { }
    }

    private fun clearWarning() = NotificationManagerCompat.from(this).cancel(WARNING_ID)

    private fun hasLocationAccess(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            LocationManagerCompat.isLocationEnabled(getSystemService(LOCATION_SERVICE) as LocationManager)

    private fun enterForeground(text: String, refresh: Boolean = true) {
        val notification = notification(text)
        var withLocation = false
        if (Build.VERSION.SDK_INT >= 34) {
            val base = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (hasLocationAccess()) {
                withLocation = try {
                    startForeground(NOTIFICATION_ID, notification, base or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION); true
                } catch (_: Exception) { false }
            }
            if (!withLocation) startForeground(NOTIFICATION_ID, notification, base)
        } else {
            startForeground(NOTIFICATION_ID, notification)
            withLocation = hasLocationAccess()
        }
        val gained = withLocation && !locationForeground
        locationForeground = withLocation
        if (gained && refresh) refreshNetworkCallback()
    }

    private fun refreshNetworkCallback() {
        try { networkManager.unregisterNetworkCallback(networkCallback) } catch (_: Exception) { }
        networkCallback = newNetworkCallback()
        try { networkManager.registerNetworkCallback(underlayRequest, networkCallback) } catch (_: SecurityException) { }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        enterForeground(getString(R.string.preparing), refresh = false)
        scope.launch { AppState.snapshot.map { it.status }.distinctUntilChanged().collect { TileRefresher.refresh(this@DnsVpnService) } }
        scope.launch { while (isActive) { delay(15 * 60_000L); checkForUpdate() } }
        try {
            networkManager.registerNetworkCallback(underlayRequest, networkCallback)
            networkManager.registerNetworkCallback(NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN).build(),vpnCallback)
        } catch (_: SecurityException) {
            AppState.recordFailure(getString(R.string.network_events_unavailable))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action != ACTION_STOP) {
            enterForeground(notificationText ?: getString(R.string.preparing))
            if (action == ACTION_REFRESH || action == ACTION_DIAGNOSTICS) {
                if (stopping) {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
            } else {
                stopping = false
                settings.setProtectionWanted(true)
                clearWarning()
            }
        }
        when (action) {
            ACTION_STOP -> {
                stopping = true
                settings.setProtectionWanted(false)
                clearWarning()
                notificationText = null
                networkEpoch.incrementAndGet()
                networkChangedJob?.cancel()
                watchdogJob?.cancel()
                healthJob?.cancel()
                resolver = null
                closeTunnel()
                AppState.update { it.copy(status = ProtectionStatus.OFF, transport = "—", currentWifi = null, detail = getString(R.string.off_detail)) }
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_DIAGNOSTICS -> scope.launch {
                reconcile()
                val client=resolver ?: return@launch
                kotlinx.coroutines.coroutineScope {client.transportNames().forEach {name->launch {client.probeTransport(name)}}}
            }
            else -> scope.launch { reconcile() }
        }
        return START_STICKY
    }

    private fun scheduleNetworkChange() {
        if (stopping) return
        networkEpoch.incrementAndGet()
        networkChangedJob?.cancel()
        networkChangedJob = scope.launch {
            delay(1800)
            reconcile()
        }
    }

    private fun meaningfulChange(old: NetworkCapabilities, next: NetworkCapabilities): Boolean =
        listOf(NetworkCapabilities.NET_CAPABILITY_INTERNET,NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED,
            NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL).any { old.hasCapability(it) != next.hasCapability(it) }

    private fun rememberSsid(network: Network, caps: NetworkCapabilities): String? {
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val info = if (Build.VERSION.SDK_INT >= 31) caps.transportInfo as? WifiInfo
            else @Suppress("DEPRECATION") (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).connectionInfo
        WifiPolicy.normalizeSsid(info?.ssid)?.let { knownSsid[network] = it }
        return knownSsid[network]
    }

    @Suppress("DEPRECATION")
    private fun availableUnderlay(): Network? {
        val networks=networkManager.allNetworks.toList()
        val candidates=networks.mapNotNull { network ->
            val c=capabilities[network] ?: networkManager.getNetworkCapabilities(network) ?: return@mapNotNull null
            UnderlayCandidate(Compat.id(network),c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED),
                c.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
        }
        val chosen=UnderlayPolicy.choose(candidates,activeNetwork?.let(Compat::id))
        return networks.firstOrNull { Compat.id(it)==chosen }
    }
    private val vpnCallback=object: ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if(android.os.Build.VERSION.SDK_INT>=30) {
                if(caps.ownerUid!=android.os.Process.myUid())return
            } else if(tunnel==null||(Build.VERSION.SDK_INT>=23&&networkManager.activeNetwork!=network)||VpnService.prepare(this@DnsVpnService)!=null)return
            vpnNetwork=network
            AppState.update { it.copy(androidValidated=caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) }
        }
        override fun onLost(network: Network) { if(vpnNetwork==network) {vpnNetwork=null;AppState.update {it.copy(androidValidated=false)}} }
    }

    private fun ssid(network: Network?): String? {
        if (network == null) return null
        val locationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!locationGranted) return null
        val caps = capabilities[network] ?: networkManager.getNetworkCapabilities(network) ?: return knownSsid[network]
        return rememberSsid(network, caps)
    }

    private suspend fun reconcile(renewResolver: Boolean = false) = reconcileMutex.withLock {
        if (stopping) return@withLock
        val epoch = networkEpoch.get()
        val underlay = availableUnderlay()
        val profile = settings.current()
        val currentSsid = ssid(underlay)
        val caps=underlay?.let { capabilities[it] ?: networkManager.getNetworkCapabilities(it) }
        AppState.update { it.copy(underlayAvailable=underlay!=null,currentWifi=currentSsid,lockdown=lockdownEnabled,
            captivePortal=caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)==true,
            underlayValidated=caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true) }
        activeNetwork = underlay
        val exempt = WifiPolicy.exempt(currentSsid, profile.exemptSsids, lockdownEnabled)
        if (exempt) {
            closeTunnel()
            resolver = null
            AppState.update { it.copy(status = ProtectionStatus.WIFI_EXEMPT, transport = "—", detail = getString(R.string.wifi_exempt_detail, currentSsid)) }
            refreshNotification(getString(R.string.status_exempt))
            return@withLock
        }
        val intercept=profile.interceptPublicDns || lockdownEnabled
        if(intercept&&!NativeQuic.available) {
            AppState.update {it.copy(status=ProtectionStatus.ERROR,detail=getString(R.string.strict_unavailable))}
            return@withLock
        }
        if (tunnel == null || tunnelNeedsRecovery || tunnelIntercepts!=intercept || (intercept&&tunnelUnderlay!=underlay)) {
            AppState.update { it.copy(status = ProtectionStatus.STARTING, detail = getString(R.string.tunnel_starting)) }
            try {
                val builder = Builder()
                    .setSession(getString(R.string.vpn_session))
                    .setMtu(1500)
                    .addAddress(TUN_V4, 32)
                    .addDnsServer(DNS_V4)
                    .addRoute(DNS_V4, 32)
                    .addAddress(TUN_V6, 128)
                    .addDnsServer(DNS_V6)
                    .addRoute(DNS_V6, 128)
                    .setBlocking(true)
                if (Build.VERSION.SDK_INT >= 22) builder.setUnderlyingNetworks(underlay?.let { arrayOf(it) } ?: emptyArray())
                if (Build.VERSION.SDK_INT >= 29) builder.setMetered(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)!=true)
                if(intercept) {
                    if(lockdownEnabled) {builder.addRoute("0.0.0.0",0);builder.addRoute("::",0)}
                    else RoutePlan.publicRoutes.forEach {builder.addRoute(it.address,it.prefix)}
                }
                val newTunnel=builder.establish() ?: throw IOException("VPN consent missing")
                val oldTunnel = tunnel
                val oldReader = readJob
                tunnel = newTunnel
                tunnelNeedsRecovery = false
                tunnelIntercepts=intercept;tunnelUnderlay=underlay
                AppState.update {it.copy(interceptPublicDns=intercept)}
                readJob = scope.launch {
                    try{readPackets(newTunnel,intercept,underlay)}
                    catch(cancelled:CancellationException){throw cancelled}
                    catch(_:Exception){recoverTunnel(newTunnel)}
                }
                oldReader?.cancel()
                runCatching { oldTunnel?.close() }
                oldTunnel?.let {sessionStreams.remove(it)}
            } catch (error: Exception) {
                val retained = tunnel
                AppState.update { it.copy(status = if (retained == null) ProtectionStatus.ERROR else ProtectionStatus.DEGRADED,
                    detail = getString(if (retained == null) R.string.vpn_error_detail else R.string.tunnel_recovering)) }
                if (retained != null) scope.launch { recoverTunnel(retained) }
                return@withLock
            }
        }
        val local=WifiPolicy.allowLocal(currentSsid,profile.trustedLocalDnsSsids,profile.allowLocalPlaintext)
        val changed=renewResolver || resolver==null || resolverNetwork!=underlay || resolverProfile!=profile || resolverLocal!=local
        if(changed) {
            healthJob?.cancel()
            resolver=underlay?.let { DnsClient(this,it,profile,allowLocalDns=local) }
            resolverNetwork=underlay;resolverProfile=profile;resolverLocal=local
            AppState.update { it.copy(protocolHealth=emptyList()) }
            resolver?.startBackgroundProbes()
        }
        if (Build.VERSION.SDK_INT >= 22) setUnderlyingNetworks(underlay?.let { arrayOf(it) } ?: emptyArray())
        if (underlay == null) {
            AppState.update { it.copy(status = ProtectionStatus.DEGRADED, detail = getString(R.string.offline_detail), transport = "—", padding = profile.ednsPaddingEnabled) }
            refreshNotification(getString(R.string.offline_notification))
        } else if(changed || AppState.snapshot.value.status != ProtectionStatus.ACTIVE) {
            AppState.update { it.copy(status = ProtectionStatus.DEGRADED,
                detail = getString(R.string.health_checking), transport = "—", padding = profile.ednsPaddingEnabled) }
            val client = resolver
            healthJob=scope.launch {
                val healthy = client?.probe() == true && TunHealthProbe.check()
                if (!stopping && epoch == networkEpoch.get() && client === resolver && tunnel != null) {
                    reportHealth(healthy)
                }
            }
        }
        if (profile.watchdogEnabled && watchdogJob?.isActive != true) {
            watchdogJob = scope.launch { watchConnection() }
        }
    }

    private suspend fun readPackets(descriptor: ParcelFileDescriptor,intercept:Boolean=false,underlay:Network?=null) {
        if(tunnel!==descriptor||stopping)return
        val input = FileInputStream(descriptor.fileDescriptor)
        val output=FileOutputStream(descriptor.fileDescriptor)
        sessionStreams[descriptor]=input to output
        val buffer = ByteArray(65535)
        val tunnelScope = CoroutineScope(SupervisorJob(job) + Dispatchers.IO)
        fun writePacket(bytes:ByteArray) {
            synchronized(outputLock) {
                if(tunnel===descriptor&&!stopping)output.write(bytes)
            }
        }
        suspend fun sendPacket(bytes:ByteArray)=writePacket(bytes)
        suspend fun resolveQuery(query: ByteArray, metrics: Boolean = true): ByteArray {
            val current = resolver ?: return DnsWire.servfail(query)
            return try {
                val answer = current.resolve(query,metrics)
                if (current === resolver) answer else DnsWire.servfail(query)
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { DnsWire.servfail(query) }
        }
        val native=try{if(intercept)NativeFullTunnel.start(this,underlay,{bytes->
            try{writePacket(bytes)}catch(_:IOException){scope.launch{recoverTunnel(descriptor)}}
        }) {query,udp,port ->
            val answer=resolveQuery(query,!TunHealthProbe.isHealthPort(port))
            if(udp)DnsWire.udpSafeResponse(query,answer)else answer
        }else null}catch(error:Exception){tunnelScope.cancel();scope.launch{recoverTunnel(descriptor)};return}
        if(tunnel!==descriptor||stopping){native?.close();tunnelScope.cancel();sessionStreams.remove(descriptor);return}
        if(native!=null)fullStack=native
        val permits = Semaphore(128)
        val tcp = DnsTcpProxy(tunnelScope, { resolveQuery(it) }, ::sendPacket) {
            scope.launch { recoverTunnel(descriptor) }
        }
        try {
            while (scope.isActive && tunnel === descriptor) {
                val length = input.read(buffer)
                if (length < 0) throw IOException("TUN closed")
                if (length == 0) continue
                if(native!=null){native.input(buffer,length);continue}
                val request = DnsUdpPacket.parse(buffer, length)
                if (request == null) {
                    val segment = DnsTcpPacket.parse(buffer, length)
                    if (segment != null && segment.destinationPort == 53 &&
                        isVirtualDnsAddress(segment.ipVersion, segment.destination)
                    ) tcp.handle(segment)
                    continue
                }
                if (!isVirtualDnsAddress(request.ipVersion, request.destination)) continue
                if (!DnsWire.validQuery(request.dnsMessage) || !permits.tryAcquire()) continue
                val responseJob = tunnelScope.launch {
                    try {
                        val answer = resolveQuery(request.dnsMessage,!TunHealthProbe.isHealthPort(request.sourcePort))
                        val reply = DnsUdpPacket.response(request, DnsWire.udpSafeResponse(request.dnsMessage, answer))
                        sendPacket(reply)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: IOException) {
                        scope.launch { recoverTunnel(descriptor) }
                    } catch (_: Exception) {
                    }
                }
                responseJob.invokeOnCompletion { permits.release() }
            }
        } catch (_: IOException) {
        } finally {
            tunnelScope.cancel()
            native?.close()
            if(fullStack===native)fullStack=null
            if(tunnel!==descriptor)sessionStreams.remove(descriptor)
            if (scope.isActive && tunnel === descriptor) scope.launch { recoverTunnel(descriptor) }
        }
    }

    private var recoveryAttempt = 0
    internal suspend fun recoverTunnel(descriptor: ParcelFileDescriptor) {
        val recover = reconcileMutex.withLock {
            if (stopping || tunnel !== descriptor || recoveryScheduled) false else {
                resolver = null
                tunnelNeedsRecovery = true
                recoveryScheduled = true
                AppState.update { it.copy(status = ProtectionStatus.DEGRADED,
                    transport = "—", detail = getString(R.string.tunnel_recovering), reconnects = it.reconnects + 1) }
                true
            }
        }
        if (recover) {
            delay((500L shl recoveryAttempt.coerceAtMost(5)).coerceAtMost(16_000L))
            recoveryAttempt++
            reconcileMutex.withLock { recoveryScheduled = false }
            if (scope.isActive && tunnel === descriptor) reconcile()
        }
    }

    private fun reportHealth(healthy: Boolean) {
        if (stopping) return
        if (healthy) {
            recoveryAttempt = 0
            if (Build.VERSION.SDK_INT >= 23) vpnNetwork?.let { networkManager.reportNetworkConnectivity(it,true) }
        }
        AppState.update { it.copy(status = if (healthy) ProtectionStatus.ACTIVE else ProtectionStatus.DEGRADED,
            transport = if (healthy) resolver?.lastTransport ?: "—" else "—",
            detail = if (healthy) getString(R.string.active_detail) else getString(R.string.health_failed)) }
        refreshNotification(if (healthy) getString(R.string.active_notification) else getString(R.string.degraded_notification))
    }

    private fun isVirtualDnsAddress(version: Int, address: ByteArray): Boolean =
        when (version) {
            4 -> address.contentEquals(virtualDns4)
            6 -> address.contentEquals(virtualDns6)
            else -> false
        }

    private suspend fun watchConnection() {
        var consecutiveFailures = 0
        while (scope.isActive) {
            delay(if(AppState.snapshot.value.status==ProtectionStatus.ACTIVE) 60_000 else 10_000)
            if (!settings.current().watchdogEnabled || tunnel == null) continue
            val client = resolver
            client?.startBackgroundProbes()
            val epoch = networkEpoch.get()
            val success = client?.probe() == true && TunHealthProbe.check()
            if (epoch != networkEpoch.get() || client !== resolver || tunnel == null) continue
            reportHealth(success)
            if (success) {
                consecutiveFailures = 0
                AppState.update { if (it.status == ProtectionStatus.DEGRADED) it.copy(status = ProtectionStatus.ACTIVE, detail = getString(R.string.connection_restored)) else it }
            } else {
                consecutiveFailures++
                if (consecutiveFailures >= 3) {
                    AppState.update { it.copy(status = ProtectionStatus.DEGRADED, detail = getString(R.string.watchdog_failed), reconnects = it.reconnects + 1) }
                    reconcile(renewResolver = true)
                    consecutiveFailures = 0
                }
            }
        }
    }

    private fun closeTunnel() {
        fullStack?.close();fullStack=null
        readJob?.cancel()
        readJob = null
        val descriptor = tunnel
        tunnel = null
        tunnelNeedsRecovery = false
        runCatching { descriptor?.close() }
        descriptor?.let{sessionStreams.remove(it)}
    }

    @Volatile private var notificationText: String? = null
    private fun refreshNotification(message: String) {
        notificationText = message
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification(message))
    }

    private fun notification(message: String): Notification {
        val activity = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("DNSBunker")
            .setContentText(message)
            .setContentIntent(activity)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val status = AppState.snapshot.value.status
        val detail = when (status) {
            ProtectionStatus.ACTIVE -> getString(R.string.active_detail)
            ProtectionStatus.STARTING -> getString(R.string.tunnel_starting)
            ProtectionStatus.DEGRADED -> getString(if (activeNetwork == null) R.string.offline_detail else R.string.health_failed)
            ProtectionStatus.ERROR -> getString(R.string.vpn_error_detail)
            ProtectionStatus.WIFI_EXEMPT -> getString(R.string.wifi_exempt_detail, ssid(activeNetwork).orEmpty())
            ProtectionStatus.OFF -> getString(R.string.off_detail)
        }
        AppState.update { it.copy(detail = detail) }
        refreshNotification(detail)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createChannel()
    }

    override fun onRevoke() {
        settings.setProtectionWanted(false)
        stopping=true
        networkEpoch.incrementAndGet()
        resolver=null
        healthJob?.cancel();watchdogJob?.cancel()
        closeTunnel()
        AppState.update {it.copy(status=ProtectionStatus.OFF,transport="—",currentWifi=null,androidValidated=false,detail=getString(R.string.off_detail))}
        warnRevoked()
        super.onRevoke()
        stopSelf()
    }

    override fun onDestroy() {
        stopping = true
        resolver = null
        healthJob?.cancel()
        try { networkManager.unregisterNetworkCallback(vpnCallback) } catch (_: Exception) { }
        try { networkManager.unregisterNetworkCallback(networkCallback) } catch (_: Exception) { }
        closeTunnel()
        scope.cancel()
        AppState.update { it.copy(status = ProtectionStatus.OFF, transport = "—", currentWifi = null, detail = getString(R.string.off_detail)) }
        super.onDestroy()
    }
}
