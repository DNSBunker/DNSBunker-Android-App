package org.dnsbunker.android.vpn

import android.app.NotificationManager
import android.content.Intent
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.dnsbunker.android.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class VpnServiceLifecycleTest {
    @Test fun `service creates foreground channel and destruction clears protection state`() {
        val controller = Robolectric.buildService(DnsVpnService::class.java).create()
        val service = controller.get()
        try {
            val manager = service.getSystemService(NotificationManager::class.java)
            assertNotNull(manager.getNotificationChannel("dnsbunker_vpn"))
            AppState.update { it.copy(status = ProtectionStatus.ACTIVE, transport = "DoH/2") }
        } finally { controller.destroy() }
        assertEquals(ProtectionStatus.OFF, AppState.snapshot.value.status)
        assertEquals("—", AppState.snapshot.value.transport)
    }
    @Test fun `explicit stop releases the VPN before waiting for service destruction`() {
        val controller = Robolectric.buildService(DnsVpnService::class.java).create()
        val descriptors = ParcelFileDescriptor.createPipe()
        val service = controller.get()
        DnsVpnService::class.java.getDeclaredField("tunnel").apply { isAccessible = true }.set(service, descriptors[0])
        try {
            AppState.update { it.copy(status = ProtectionStatus.ACTIVE) }
            service.onStartCommand(Intent().setAction(DnsVpnService.ACTION_STOP), 0, 1)
            assertEquals(ProtectionStatus.OFF, AppState.snapshot.value.status)
            assertNull(DnsVpnService::class.java.getDeclaredField("tunnel").apply { isAccessible = true }.get(service))
        } finally { controller.destroy(); descriptors[1].close() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `TUN recovery retains the old descriptor throughout backoff`() = runTest {
        val controller = Robolectric.buildService(DnsVpnService::class.java).create()
        val descriptors = ParcelFileDescriptor.createPipe()
        val service = controller.get()
        DnsVpnService::class.java.getDeclaredField("tunnel").apply { isAccessible = true }.set(service, descriptors[0])
        try {
            val recovery = launch { service.recoverTunnel(descriptors[0]) }
            runCurrent()
            assertTrue("Recovery must keep DNS routes installed until replacement", descriptors[0].fileDescriptor.valid())
            assertEquals(ProtectionStatus.DEGRADED, AppState.snapshot.value.status)
            recovery.cancelAndJoin()
        } finally { controller.destroy(); descriptors[1].close() }
    }

    @Test fun `no physical underlay cannot create an unbound default network socket`() {
        val controller = Robolectric.buildService(DnsVpnService::class.java).create()
        try {
            try { ProtectedSockets(controller.get(), null).newSocket(); fail("must fail closed") }
            catch (_: IOException) { }
        } finally { controller.destroy() }
    }

    @Test fun `start after stop re-arms the service while refresh keeps it stopped`() {
        val controller = Robolectric.buildService(DnsVpnService::class.java).create()
        val service = controller.get()
        val stopping = DnsVpnService::class.java.getDeclaredField("stopping").apply { isAccessible = true }
        try {
            service.onStartCommand(Intent().setAction(DnsVpnService.ACTION_STOP), 0, 1)
            assertTrue(stopping.getBoolean(service))
            service.onStartCommand(Intent().setAction(DnsVpnService.ACTION_REFRESH), 0, 2)
            assertTrue("refresh must not restart a stopped VPN", stopping.getBoolean(service))
            service.onStartCommand(Intent().setAction(DnsVpnService.ACTION_START), 0, 3)
            assertFalse("explicit start must re-arm the service", stopping.getBoolean(service))
        } finally { controller.destroy() }
    }

    @Test fun `explicit start and stop remember whether protection should come back after updates`() {
        val controller = Robolectric.buildService(DnsVpnService::class.java).create()
        val service = controller.get()
        val settings = UserSettings(service)
        settings.update { it.copy(interceptPublicDns = true) }
        try {
            service.onStartCommand(Intent().setAction(DnsVpnService.ACTION_START), 0, 1)
            assertTrue(settings.protectionWanted())
            service.onStartCommand(Intent().setAction(DnsVpnService.ACTION_REFRESH), 0, 2)
            assertTrue(settings.protectionWanted())
            service.onStartCommand(Intent().setAction(DnsVpnService.ACTION_STOP), 0, 3)
            assertFalse(settings.protectionWanted())
        } finally { controller.destroy() }
    }

    @Test fun `restart receiver only reacts to boot and update when protection was wanted`() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val shadow = org.robolectric.Shadows.shadowOf(app)
        val receiver = RestartReceiver()
        org.robolectric.shadows.ShadowVpnService.setPrepareResult(null)
        UserSettings(app).setProtectionWanted(false)
        receiver.onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadow.nextStartedService)
        UserSettings(app).setProtectionWanted(true)
        receiver.onReceive(app, Intent(Intent.ACTION_TIME_CHANGED))
        assertNull(shadow.nextStartedService)
        receiver.onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        val started = shadow.nextStartedService
        assertNotNull(started)
        assertEquals(DnsVpnService.ACTION_START, started.action)
    }

    @Test fun `losing the VPN to another app warns the user and forgets the wish to run`() {
        val controller = Robolectric.buildService(DnsVpnService::class.java).create()
        val service = controller.get()
        val manager = service.getSystemService(NotificationManager::class.java)
        try {
            UserSettings(service).setProtectionWanted(true)
            service.onRevoke()
            assertFalse(UserSettings(service).protectionWanted())
            val warning = org.robolectric.Shadows.shadowOf(manager).allNotifications
                .firstOrNull { it.extras.getString(android.app.Notification.EXTRA_TITLE) == service.getString(org.dnsbunker.android.R.string.revoked_title) }
            assertNotNull("revocation warning posted", warning)
        } finally { controller.destroy() }
    }
}
