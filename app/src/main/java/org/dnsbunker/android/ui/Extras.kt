package org.dnsbunker.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.Lock
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dnsbunker.android.R
import org.dnsbunker.android.core.BlockedEntry
import org.dnsbunker.android.core.BlockedLog
import org.dnsbunker.android.core.DnsEndpoint
import org.dnsbunker.android.core.DnsWire
import org.dnsbunker.android.core.ProtectionStatus
import org.dnsbunker.android.core.RuntimeSnapshot
import org.dnsbunker.android.core.ServerStatus
import java.net.URLEncoder

const val LISTSEEK_URL = "https://hagezi-mirror.dnsbunker.org/listseek.php?q="

internal fun copyText(context: Context, text: String, confirmation: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("DNSBunker", text))
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, confirmation, Toast.LENGTH_SHORT).show()
}

internal fun RuntimeSnapshot.usesOurPrivateDns() = privateDnsStrict && privateDnsHost?.trimEnd('.') == DnsEndpoint.HOST

@Composable
internal fun ExplainerCard() {
    BunkerCard {
        Text(stringResource(R.string.about_title), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        BenefitRow(Icons.Default.Block, R.string.about_ads_title, R.string.about_ads_body)
        BenefitRow(Icons.Default.GppGood, R.string.about_threats_title, R.string.about_threats_body)
        BenefitRow(Icons.Default.Lock, R.string.about_privacy_title, R.string.about_privacy_body)
        Hint(stringResource(R.string.about_how))
    }
}

@Composable
private fun BenefitRow(icon: ImageVector, title: Int, body: Int) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(title), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(stringResource(body), fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun aboutText(): String = listOf(
    stringResource(R.string.about_ads_title) + ": " + stringResource(R.string.about_ads_body),
    stringResource(R.string.about_threats_title) + ": " + stringResource(R.string.about_threats_body),
    stringResource(R.string.about_privacy_title) + ": " + stringResource(R.string.about_privacy_body),
    stringResource(R.string.about_how),
).joinToString("\n\n")

@Composable
internal fun PauseDialog(onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pause_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.pause_body), fontSize = 14.sp, lineHeight = 20.sp)
                Spacer(Modifier.size(4.dp))
                listOf(5 to R.string.pause_5, 15 to R.string.pause_15, 60 to R.string.pause_60).forEach { (minutes, label) ->
                    OutlinedButton(onClick = { onPick(minutes) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(label)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun ServerStatusCard(status: RuntimeSnapshot) {
    if (status.status != ProtectionStatus.DEGRADED || status.captivePortal) return
    val (title, body) = when (status.serverStatus) {
        ServerStatus.NO_INTERNET -> R.string.server_no_internet_title to R.string.server_no_internet_body
        ServerStatus.UNREACHABLE -> R.string.server_unreachable_title to R.string.server_unreachable_body
        ServerStatus.DNS_FAILING -> R.string.server_dns_title to R.string.server_dns_body
        else -> return
    }
    BunkerCard {
        Text(stringResource(title), fontWeight = FontWeight.Bold, color = warning())
        Text(stringResource(body), fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        status.serverMessage?.let { message ->
            Text(stringResource(R.string.server_message_label), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(message, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
internal fun PrivateDnsOursCard(running: Boolean) {
    BunkerCard {
        Text(stringResource(R.string.private_dns_ours_title), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(if (running) R.string.private_dns_ours_on else R.string.private_dns_ours_off),
            fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun PrivateDnsHelperDialog(openSettings: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val copied = stringResource(R.string.pdns_copied)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pdns_title)) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.pdns_body), fontSize = 14.sp, lineHeight = 20.sp)
                Text(stringResource(R.string.pdns_steps), fontSize = 14.sp, lineHeight = 20.sp)
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                        .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(DnsEndpoint.HOST, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { copyText(context, DnsEndpoint.HOST, copied) }) { Text(stringResource(R.string.copy)) }
                }
                Text(stringResource(R.string.pdns_tradeoff), fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); openSettings() }) { Text(stringResource(R.string.pdns_open)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
internal fun BlockedDomainsCard(enabled: Boolean, setEnabled: (Boolean) -> Unit) {
    val entries by BlockedLog.entries.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<BlockedEntry?>(null) }
    val context = LocalContext.current
    val timeFormat = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    BunkerCard {
        Text(stringResource(R.string.blocked_list_title), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        SwitchRow(stringResource(R.string.blocked_list_switch), stringResource(R.string.blocked_list_body), enabled, setEnabled)
        if (enabled) {
            if (entries.isEmpty()) Hint(stringResource(R.string.blocked_list_empty))
            for (entry in if (expanded) entries else entries.take(20)) {
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button) { selected = entry }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.domain, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(categoryLabel(entry.category), stringResource(R.string.blocked_count, entry.count),
                                timeFormat.format(java.util.Date(entry.lastSeen))).joinToString(" · "),
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (entries.size > 20) TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) stringResource(R.string.show_less) else stringResource(R.string.show_all, entries.size))
            }
        }
    }
    selected?.let { entry ->
        val open = rememberLinkOpener()
        val copied = stringResource(R.string.blocked_copied)
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(entry.domain, maxLines = 3, overflow = TextOverflow.Ellipsis) },
            text = { Text(stringResource(R.string.blocked_report_body, entry.domain), fontSize = 14.sp, lineHeight = 20.sp) },
            confirmButton = {
                TextButton(onClick = { selected = null; open(LISTSEEK_URL + URLEncoder.encode(entry.domain, "UTF-8")) }) {
                    Text(stringResource(R.string.blocked_report))
                }
            },
            dismissButton = {
                TextButton(onClick = { copyText(context, entry.domain, copied); selected = null }) { Text(stringResource(R.string.copy)) }
            },
        )
    }
}

@Composable
private fun categoryLabel(category: DnsWire.BlockCategory): String = when (category) {
    DnsWire.BlockCategory.PRO -> "HaGeZi Pro"
    DnsWire.BlockCategory.TIF -> "HaGeZi TIF"
    else -> stringResource(R.string.category_short_other)
}

private data class AppItem(val pkg: String, val label: String)

@Suppress("DEPRECATION")
private fun loadApps(context: Context, selected: Set<String>): List<AppItem> {
    val pm = context.packageManager
    val found = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).mapNotNull { info ->
        val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
        if (pkg == context.packageName) null else AppItem(pkg, info.loadLabel(pm).toString())
    }.distinctBy { it.pkg }
    val others = selected.filter { pkg -> found.none { it.pkg == pkg } }.map { pkg ->
        AppItem(pkg, runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg))
    }
    return found + others
}

@Composable
internal fun AppPickerDialog(selected: Set<String>, lockdown: Boolean, onDone: (Set<String>) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var chosen by remember { mutableStateOf(selected) }
    var query by remember { mutableStateOf("") }
    val apps by produceState<List<AppItem>?>(null) { value = withContext(Dispatchers.IO) { loadApps(context, selected) } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.apps_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Button(onClick = { onDone(chosen) }) { Text(stringResource(R.string.apps_done)) }
                }
                Hint(stringResource(R.string.apps_explainer))
                if (lockdown) Hint(stringResource(R.string.apps_lockdown), warning())
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text(stringResource(R.string.apps_search)) }, modifier = Modifier.fillMaxWidth())
                val list = apps
                if (list == null) Hint(stringResource(R.string.apps_loading)) else {
                    val filtered = list.filter { query.isBlank() || it.label.contains(query.trim(), true) || it.pkg.contains(query.trim(), true) }
                        .sortedWith(compareBy<AppItem>({ it.pkg !in selected }, { it.label.lowercase() }))
                    if (filtered.isEmpty()) Hint(stringResource(R.string.apps_none_found))
                    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                        items(filtered, key = { it.pkg }) { app ->
                            AppRow(app, app.pkg in chosen) { on -> chosen = if (on) chosen + app.pkg else chosen - app.pkg }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: AppItem, checked: Boolean, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, app.pkg) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(app.pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
    }
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp)) { icon?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) } }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(app.pkg, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Checkbox(checked = checked, onCheckedChange = null)
    }
}
