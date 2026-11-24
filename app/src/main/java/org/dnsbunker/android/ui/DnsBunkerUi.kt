package org.dnsbunker.android.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.dnsbunker.android.BuildConfig
import org.dnsbunker.android.R
import org.dnsbunker.android.core.AppState
import org.dnsbunker.android.core.DnsSettings
import org.dnsbunker.android.core.ProtectionStatus
import org.dnsbunker.android.core.RuntimeSnapshot
import java.text.NumberFormat

private val Navy = Color(0xFF061730)
private val Accent = Color(0xFF1BD7F0)
private val Blue = Color(0xFF075BDC)
private val Amber = Color(0xFFFFC266)
private val AmberDark = Color(0xFF9A5B00)
private val Green = Color(0xFF36D6A0)
private val Muted = Color(0xFFB4C9DF)

const val SOURCE_URL = "https://github.com/DNSBunker/DNSBunker-Android-App"
private const val LICENSE_ID = "GPL-3.0-or-later"

@Composable
fun DnsBunkerTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) darkColorScheme(
        primary = Accent, onPrimary = Navy, background = Navy, onBackground = Color(0xFFF5F9FF),
        surface = Color(0xFF102749), onSurface = Color(0xFFF5F9FF), surfaceVariant = Color(0xFF193557),
        onSurfaceVariant = Color(0xFFADC3DA), secondaryContainer = Color(0xFF153D62), onSecondaryContainer = Accent,
        surfaceContainerLowest = Color(0xFF081C38), surfaceContainerLow = Color(0xFF0D2242), surfaceContainer = Color(0xFF102749),
        surfaceContainerHigh = Color(0xFF15304F), surfaceContainerHighest = Color(0xFF1C3A5C), outline = Color(0xFF5B7899),
        error = Color(0xFFFFB4AB),
    ) else lightColorScheme(
        primary = Blue, onPrimary = Color.White, background = Color(0xFFF4F8FC),
        onBackground = Navy, surface = Color.White, onSurface = Navy, surfaceVariant = Color(0xFFE2F0FC),
        onSurfaceVariant = Color(0xFF4B5F74), secondaryContainer = Color(0xFFE2F0FC), onSecondaryContainer = Blue,
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF7FAFE), surfaceContainer = Color(0xFFF0F5FB),
        surfaceContainerHigh = Color(0xFFEAF1F9), surfaceContainerHighest = Color(0xFFDCE7F3), outline = Color(0xFF8397AC),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

data class ScreenActions(
    val toggleProtection: () -> Unit,
    val setPadding: (Boolean) -> Unit,
    val setWatchdog: (Boolean) -> Unit,
    val setDotFallback: (Boolean) -> Unit,
    val setSplitDns: (Boolean) -> Unit,
    val setRouterDns: (Boolean) -> Unit,
    val setInterception: (Boolean) -> Unit,
    val addExemptWifi: () -> Unit,
    val removeExemptWifi: (String) -> Unit,
    val addTrustedWifi: () -> Unit,
    val removeTrustedWifi: (String) -> Unit,
    val openBatterySettings: () -> Unit,
    val openPrivateDnsSettings: () -> Unit,
    val openCaptivePortal: () -> Unit,
    val runDiagnostics: () -> Unit,
)

private fun RuntimeSnapshot.isOff() = status == ProtectionStatus.OFF || status == ProtectionStatus.ERROR

@Composable
fun DnsBunkerScreen(status: RuntimeSnapshot, preferences: DnsSettings, actions: ScreenActions) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val pageScroll = rememberScrollState()
    var showVpnDisclosure by remember { mutableStateOf(false) }
    LaunchedEffect(tab) { pageScroll.scrollTo(0) }
    LaunchedEffect(Unit) { while (isActive) { delay(5000); AppState.advanceActivity() } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                val pages = listOf(
                    stringResource(R.string.nav_protection) to Icons.Default.Security,
                    stringResource(R.string.nav_statistics) to Icons.Default.BarChart,
                    stringResource(R.string.nav_settings) to Icons.Default.Settings,
                )
                pages.forEachIndexed { index, (title, icon) ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Icon(icon, contentDescription = null) },
                        label = { Text(title, fontSize = 12.sp) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.secondaryContainer),
                    )
                }
            }
        },
    ) { inset ->
        Column(
            Modifier.fillMaxSize().padding(inset).verticalScroll(pageScroll).padding(horizontal = 18.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Wordmark()
            when (tab) {
                0 -> ProtectionPage(status, actions, onConnect = {
                    if (status.isOff()) showVpnDisclosure = true else actions.toggleProtection()
                })
                1 -> StatisticsPage(status, actions.runDiagnostics)
                else -> SettingsPage(status, preferences, actions)
            }
        }
    }

    if (showVpnDisclosure) AlertDialog(
        onDismissRequest = { showVpnDisclosure = false },
        title = { Text(stringResource(R.string.vpn_title)) },
        text = { Column { Text(stringResource(R.string.vpn_body)); Spacer(Modifier.height(8.dp)); LegalLinks() } },
        confirmButton = { TextButton(onClick = { showVpnDisclosure = false; actions.toggleProtection() }) { Text(stringResource(R.string.activate_confirm)) } },
        dismissButton = { TextButton(onClick = { showVpnDisclosure = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Wordmark() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("DNS", fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, color = MaterialTheme.colorScheme.onBackground)
        Text("Bunker", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp)
    }
}

@Composable
private fun PageTitle(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BunkerCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
    ) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(20.dp),
        ) { Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp), content = content) }
    }
}

@Composable
private fun RowDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f))

@Composable
private fun ProtectionPage(status: RuntimeSnapshot, actions: ScreenActions, onConnect: () -> Unit) {
    StatusHero(status, onConnect)
    if (status.privateDnsStrict) PrivateDnsWarning(actions.openPrivateDnsSettings)
    if (status.captivePortal) BunkerCard {
        Text(stringResource(R.string.captive_title), fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.captive_body), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = actions.openCaptivePortal) { Text(stringResource(R.string.open_captive)) }
    }
    val number = NumberFormat.getIntegerInstance()
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Metric(stringResource(R.string.blocked_short), number.format(status.blocked), Modifier.weight(1f))
        Metric(stringResource(R.string.answered), number.format(status.queries), Modifier.weight(1f))
        Metric(stringResource(R.string.average_latency),
            if (status.warmSamples > 0) stringResource(R.string.latency_value, status.warmTotalMillis / status.warmSamples) else "—",
            Modifier.weight(1f))
    }
    BunkerCard {
        Text(stringResource(R.string.connection_title), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        InfoLine(stringResource(R.string.protocol), status.transport)
        InfoLine(stringResource(R.string.android_internet), stringResource(if (status.androidValidated) R.string.verified else R.string.pending))
        InfoLine(stringResource(R.string.system_dns), stringResource(if (status.status == ProtectionStatus.ACTIVE) R.string.verified else R.string.pending))
        if (status.interceptPublicDns) Text(stringResource(R.string.strict_active), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
    }
    ActivityChart(status)
}

@Composable
private fun StatusHero(status: RuntimeSnapshot, onConnect: () -> Unit) {
    val active = status.status == ProtectionStatus.ACTIVE
    val target = when {
        active -> Green
        status.status == ProtectionStatus.OFF -> Muted
        else -> Amber
    }
    val color by animateColorAsState(target, label = "status")
    val title = stringResource(when (status.status) {
        ProtectionStatus.ACTIVE -> R.string.status_active
        ProtectionStatus.OFF -> R.string.status_off
        ProtectionStatus.STARTING -> R.string.status_starting
        ProtectionStatus.WIFI_EXEMPT -> R.string.status_exempt
        ProtectionStatus.DEGRADED -> R.string.status_degraded
        ProtectionStatus.ERROR -> R.string.status_error
    })
    val detail = if (status.status == ProtectionStatus.OFF || status.detail.isBlank()) stringResource(R.string.off_detail) else status.detail
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF071A3B), Color(0xFF123864))))
            .padding(20.dp).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(54.dp).background(color.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Shield, contentDescription = null, tint = color, modifier = Modifier.size(30.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = Color.White, fontSize = 21.sp, lineHeight = 25.sp, fontWeight = FontWeight.ExtraBold)
                Text(detail, color = Color(0xFFD2E2F4), fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
        DnsRoute(active)
        if (status.isOff()) {
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Icon(Icons.Default.PowerSettingsNew, null, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.start), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        } else {
            OutlinedButton(
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            ) {
                Icon(Icons.Default.PowerSettingsNew, null, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.stop), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun DnsRoute(active: Boolean) {
    val line by animateColorAsState(if (active) Accent else Color(0xFF5B7899), label = "route")
    Column {
        Canvas(Modifier.fillMaxWidth().height(46.dp)) {
            val y = size.height / 2
            drawLine(line.copy(alpha = .5f), Offset(16.dp.toPx(), y), Offset(size.width - 16.dp.toPx(), y), 3.dp.toPx(), StrokeCap.Round)
            listOf(16.dp.toPx(), size.width / 2, size.width - 16.dp.toPx()).forEachIndexed { i, x ->
                val radius = if (i == 1) 19.dp.toPx() else 8.dp.toPx()
                drawCircle(Color(0xFF0A2346), radius, Offset(x, y))
                drawCircle(line, radius, Offset(x, y), style = Stroke(2.dp.toPx()))
                if (i == 1) {
                    drawLine(line, Offset(x - 6.dp.toPx(), y), Offset(x - 1.dp.toPx(), y + 5.dp.toPx()), 2.dp.toPx())
                    drawLine(line, Offset(x - 1.dp.toPx(), y + 5.dp.toPx()), Offset(x + 8.dp.toPx(), y - 6.dp.toPx()), 2.dp.toPx())
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.flow_device), fontSize = 11.sp, color = Color(0xFFBCD2EB))
            Text("DNSBunker", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.flow_internet), fontSize = 11.sp, color = Color(0xFFBCD2EB))
        }
    }
}

@Composable
private fun Metric(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 14.dp)) {
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 15.sp, minLines = 2, maxLines = 2)
        }
    }
}

@Composable
private fun InfoLine(name: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(name, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), fontSize = 13.sp)
        Text(value, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
}

@Composable
private fun warning() = if (isSystemInDarkTheme()) Amber else AmberDark

@Composable
private fun Hint(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) =
    Text(text, fontSize = 12.sp, lineHeight = 16.sp, color = color)

@Composable
private fun StatisticsPage(status: RuntimeSnapshot, diagnostics: () -> Unit) {
    var reset by remember { mutableStateOf(false) }
    val number = NumberFormat.getIntegerInstance()
    PageTitle(stringResource(R.string.statistics_title), stringResource(R.string.session_only))
    Impact(status)
    ActivityChart(status)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Metric(stringResource(R.string.failure_count), number.format(status.failures), Modifier.weight(1f))
        Metric(stringResource(R.string.watchdog), number.format(status.reconnects), Modifier.weight(1f))
    }
    BunkerCard {
        Text(stringResource(R.string.connection_title), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        InfoLine(stringResource(R.string.protocol), status.transport)
        InfoLine(stringResource(R.string.connect_latency), status.lastSetupMillis?.let { stringResource(R.string.latency_value, it) } ?: "—")
        InfoLine(stringResource(R.string.wall_latency), status.lastLatencyMillis?.let { stringResource(R.string.latency_value, it) } ?: "—")
        if (BuildConfig.DEBUG) {
            status.protocolHealth.sortedBy { it.name }.forEach { protocol ->
                InfoLine(protocol.name + " · " + stringResource(R.string.warm_latency),
                    if (protocol.ready) protocol.latencyMillis?.let { stringResource(R.string.latency_value, it) } ?: stringResource(R.string.protocol_ready)
                    else stringResource(R.string.protocol_failed))
            }
            OutlinedButton(onClick = diagnostics, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.run_diagnostics)) }
        }
        Hint(stringResource(R.string.latency_explainer))
        Hint(stringResource(R.string.resilience_info))
    }
    BunkerCard {
        Hint(stringResource(R.string.statistics_body))
        Hint(stringResource(R.string.category_explainer))
    }
    OutlinedButton(onClick = { reset = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.reset_statistics)) }
    if (reset) AlertDialog(
        onDismissRequest = { reset = false },
        title = { Text(stringResource(R.string.reset_title)) },
        text = { Text(stringResource(R.string.reset_body)) },
        confirmButton = { TextButton(onClick = { AppState.resetStatistics(); reset = false }) { Text(stringResource(R.string.reset_statistics)) } },
        dismissButton = { TextButton(onClick = { reset = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Impact(status: RuntimeSnapshot) {
    val fraction = if (status.queries > 0) (status.blocked.toFloat() / status.queries).coerceIn(0f, 1f) else 0f
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val number = NumberFormat.getIntegerInstance()
    BunkerCard {
        Text(stringResource(R.string.impact_title), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(78.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    drawArc(track, -90f, 360f, false, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round))
                    if (fraction > 0) drawArc(primary, -90f, 360 * fraction, false, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round))
                }
                Text(NumberFormat.getPercentInstance().format(fraction), fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
            }
            Column(Modifier.weight(1f)) {
                Text(number.format(status.blocked), fontSize = 34.sp, lineHeight = 37.sp, fontWeight = FontWeight.ExtraBold, color = primary)
                Text(stringResource(R.string.impact_caption), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (status.blocked > 0) {
            InfoLine(stringResource(R.string.category_pro), number.format(status.blockedPro))
            InfoLine(stringResource(R.string.category_tif), number.format(status.blockedTif))
            if (status.blockedOther > 0) InfoLine(stringResource(R.string.category_other), number.format(status.blockedOther))
        }
        Hint(stringResource(if (status.blocked == 0L) R.string.impact_empty else R.string.impact_explainer))
    }
}

@Composable
private fun ActivityChart(status: RuntimeSnapshot) {
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.surfaceVariant
    BunkerCard {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.activity_title), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Hint(stringResource(R.string.activity_subtitle))
        }
        if (status.recentActivity.all { it == 0L }) Hint(stringResource(R.string.activity_empty))
        Canvas(Modifier.fillMaxWidth().height(72.dp)) {
            val max = (status.recentActivity.maxOrNull() ?: 0L).coerceAtLeast(1)
            val width = size.width / 24
            status.recentActivity.forEachIndexed { index, value ->
                val h = (value.toFloat() / max * size.height).coerceAtLeast(2.dp.toPx())
                drawRoundRect(if (value == 0L) muted else primary.copy(alpha = .65f), Offset(index * width, size.height - h),
                    Size(width * .65f, h), CornerRadius(2.dp.toPx()))
                if (status.recentBlocked[index] > 0) {
                    val bh = status.recentBlocked[index].toFloat() / max * size.height
                    drawRect(Accent, Offset(index * width, size.height - bh), Size(width * .65f, bh))
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(status: RuntimeSnapshot, settings: DnsSettings, actions: ScreenActions) {
    var confirmInterception by remember { mutableStateOf(false) }
    var confirmWifi by remember { mutableStateOf(false) }
    var confirmRouter by remember { mutableStateOf(false) }
    var confirmBattery by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }

    PageTitle(stringResource(R.string.nav_settings))

    SectionCard(stringResource(R.string.section_protection)) {
        SwitchRow(stringResource(R.string.strict_title), stringResource(R.string.strict_body), settings.interceptPublicDns) {
            if (it) confirmInterception = true else actions.setInterception(false)
        }
        RowDivider()
        SwitchRow(stringResource(R.string.watchdog), stringResource(R.string.watchdog_body), settings.watchdogEnabled, actions.setWatchdog)
        RowDivider()
        if (status.batteryExempt) TextRow(stringResource(R.string.battery_title), stringResource(R.string.battery_exempt))
        else LinkRow(stringResource(R.string.battery_title), stringResource(R.string.battery_action)) { confirmBattery = true }
    }

    SectionCard(stringResource(R.string.section_wifi)) {
        TextRow(stringResource(R.string.wifi_exceptions), stringResource(R.string.wifi_exceptions_body))
        status.currentWifi?.let { name ->
            val state = stringResource(if (status.status == ProtectionStatus.WIFI_EXEMPT) R.string.status_exempt else R.string.status_active)
            Hint(stringResource(R.string.current_wifi, name) + " · " + state, MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
        }
        if (!status.wifiLocationReady) { Hint(stringResource(R.string.wifi_needs_location), warning()); Spacer(Modifier.height(6.dp)) }
        if (status.lockdown) { Hint(stringResource(R.string.wifi_lockdown_paused), warning()); Spacer(Modifier.height(6.dp)) }
        if (settings.exemptSsids.isEmpty()) Hint(stringResource(R.string.no_exceptions))
        NetworkList(settings.exemptSsids, actions.removeExemptWifi)
        OutlinedButton(onClick = { confirmWifi = true }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Icon(Icons.Default.Wifi, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.add_exception))
        }
        Hint(stringResource(R.string.wifi_warning), warning())
        Spacer(Modifier.height(8.dp))
    }

    SectionCard(stringResource(R.string.section_local)) {
        TextRow(stringResource(R.string.local_devices), stringResource(R.string.local_devices_body))
        RowDivider()
        SwitchRow(stringResource(R.string.split_dns), stringResource(R.string.split_dns_body), settings.splitDnsEnabled, actions.setSplitDns)
        Hint(stringResource(R.string.local_suffixes, settings.splitDnsSuffixes))
        Spacer(Modifier.height(6.dp))
        RowDivider()
        SwitchRow(stringResource(R.string.allow_router_dns), stringResource(R.string.router_dns_body), settings.allowLocalPlaintext) {
            if (it) confirmRouter = true else actions.setRouterDns(false)
        }
        if (settings.allowLocalPlaintext) {
            NetworkList(settings.trustedLocalDnsSsids, actions.removeTrustedWifi)
            OutlinedButton(onClick = actions.addTrustedWifi, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Icon(Icons.Default.Wifi, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.trust_wifi))
            }
        }
        Hint(stringResource(R.string.local_dns_notice), warning())
        Spacer(Modifier.height(8.dp))
    }

    SectionCard(stringResource(R.string.section_advanced)) {
        SwitchRow(stringResource(R.string.padding_title), stringResource(R.string.padding_body), settings.ednsPaddingEnabled, actions.setPadding)
        RowDivider()
        SwitchRow(stringResource(R.string.dot_fallback), stringResource(R.string.dot_fallback_body), settings.fallbackDoT, actions.setDotFallback)
        RowDivider()
        LinkRow(stringResource(R.string.private_dns_setting),
            stringResource(if (status.privateDnsStrict) R.string.private_dns_title else R.string.private_dns_ok),
            highlight = status.privateDnsStrict, onClick = actions.openPrivateDnsSettings)
    }

    SectionCard(stringResource(R.string.section_about)) {
        ExpandableRow(stringResource(R.string.protection_scope), stringResource(R.string.scope_notice))
        RowDivider()
        ExpandableRow(stringResource(R.string.encrypted_only_title), stringResource(R.string.encrypted_only_body))
        RowDivider()
        ExpandableRow(stringResource(R.string.privacy_title), stringResource(R.string.privacy_body))
        RowDivider()
        ExpandableRow(stringResource(R.string.app_privacy_title), stringResource(R.string.app_privacy_body) + "\n\n" + stringResource(R.string.strict_privacy))
        RowDivider()
        val uri = rememberLinkOpener()
        LinkRow(stringResource(R.string.privacy_policy), null) { uri("https://dnsbunker.org/privacy.html") }
        RowDivider()
        LinkRow(stringResource(R.string.terms), null) { uri("https://dnsbunker.org/tos.html") }
        RowDivider()
        LinkRow(stringResource(R.string.source_code), SOURCE_URL.removePrefix("https://")) { uri(SOURCE_URL) }
        RowDivider()
        LinkRow(stringResource(R.string.third_party), null) { showLicenses = true }
        RowDivider()
        TextRow(stringResource(R.string.version_line, BuildConfig.VERSION_NAME, LICENSE_ID), stringResource(R.string.footer))
    }

    if (confirmInterception) AlertDialog(
        onDismissRequest = { confirmInterception = false },
        title = { Text(stringResource(R.string.strict_confirm)) },
        text = { Text(stringResource(R.string.strict_body) + "\n\n" + stringResource(R.string.strict_privacy)) },
        confirmButton = { TextButton(onClick = { confirmInterception = false; actions.setInterception(true) }) { Text(stringResource(R.string.activate_confirm)) } },
        dismissButton = { TextButton(onClick = { confirmInterception = false }) { Text(stringResource(R.string.cancel)) } },
    )
    if (confirmWifi) AlertDialog(
        onDismissRequest = { confirmWifi = false },
        title = { Text(stringResource(R.string.wifi_confirm_title)) },
        text = { Text(stringResource(R.string.wifi_confirm_body)) },
        confirmButton = { TextButton(onClick = { confirmWifi = false; actions.addExemptWifi() }) { Text(stringResource(R.string.confirm_exception)) } },
        dismissButton = { TextButton(onClick = { confirmWifi = false }) { Text(stringResource(R.string.cancel)) } },
    )
    if (confirmRouter) AlertDialog(
        onDismissRequest = { confirmRouter = false },
        title = { Text(stringResource(R.string.local_confirm_title)) },
        text = { Text(stringResource(R.string.local_confirm_body)) },
        confirmButton = { TextButton(onClick = { confirmRouter = false; actions.setRouterDns(true) }) { Text(stringResource(R.string.confirm_local)) } },
        dismissButton = { TextButton(onClick = { confirmRouter = false }) { Text(stringResource(R.string.cancel)) } },
    )
    if (confirmBattery) AlertDialog(
        onDismissRequest = { confirmBattery = false },
        title = { Text(stringResource(R.string.battery_confirm)) },
        text = { Text(stringResource(R.string.battery_body)) },
        confirmButton = { TextButton(onClick = { confirmBattery = false; actions.openBatterySettings() }) { Text(stringResource(R.string.activate_confirm)) } },
        dismissButton = { TextButton(onClick = { confirmBattery = false }) { Text(stringResource(R.string.cancel)) } },
    )
    if (showLicenses) LicensesDialog { showLicenses = false }
}

@Composable
private fun SwitchRow(title: String, description: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = value, role = Role.Switch, onValueChange = onChange).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = value, onCheckedChange = null)
    }
}

@Composable
private fun TextRow(title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun LinkRow(title: String, description: String?, highlight: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            if (description != null) Text(description, fontSize = 12.sp, lineHeight = 16.sp,
                color = if (highlight) warning() else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ExpandableRow(title: String, body: String) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { open = !open }.padding(vertical = 12.dp).animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) {
            Spacer(Modifier.height(6.dp))
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun NetworkList(names: Set<String>, remove: (String) -> Unit) {
    names.sorted().forEach { name ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Wifi, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(name, modifier = Modifier.weight(1f), fontSize = 14.sp)
            TextButton(onClick = { remove(name) }) { Text(stringResource(R.string.remove)) }
        }
    }
}

@Composable
private fun PrivateDnsWarning(open: () -> Unit) {
    BunkerCard {
        Text(stringResource(R.string.private_dns_title), fontWeight = FontWeight.Bold, color = warning())
        Text(stringResource(R.string.private_dns_body), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = open) { Text(stringResource(R.string.private_dns_action)) }
    }
}

@Composable
private fun rememberLinkOpener(): (String) -> Unit {
    val uri = LocalUriHandler.current
    val context = LocalContext.current
    val missing = stringResource(R.string.browser_missing)
    return remember(uri, context, missing) {
        { url: String ->
            try { uri.openUri(url) } catch (_: Exception) {
                android.widget.Toast.makeText(context, missing, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
}

@Composable
private fun LegalLinks() {
    val open = rememberLinkOpener()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        TextButton(onClick = { open("https://dnsbunker.org/privacy.html") }) { Text(stringResource(R.string.privacy_policy), fontSize = 12.sp) }
        TextButton(onClick = { open("https://dnsbunker.org/tos.html") }) { Text(stringResource(R.string.terms), fontSize = 12.sp) }
    }
}

@Composable
private fun LicensesDialog(close: () -> Unit) {
    val context = LocalContext.current
    val text = remember {
        runCatching { context.assets.open("NOTICE.md").bufferedReader().use { it.readText() } }.getOrDefault("")
            .lines().filterNot { it.startsWith("# ") }.joinToString("\n").trim()
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.third_party)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("DNSBunker · $LICENSE_ID", fontWeight = FontWeight.SemiBold)
                Text(text, fontSize = 13.sp, lineHeight = 18.sp)
            }
        },
        confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.close)) } },
    )
}
