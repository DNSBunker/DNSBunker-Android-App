package org.dnsbunker.android.core

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import org.dnsbunker.android.MainActivity
import org.dnsbunker.android.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.dnsbunker.android.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

enum class UpdateState { IDLE, CHECKING, LATEST, AVAILABLE, DOWNLOADING, INSTALLING, NEEDS_PERMISSION, FAILED }

data class Release(val version: String, val apkName: String, val apkUrl: String, val sumsUrl: String, val notes: String)

object Updater {
    private const val LATEST = "https://api.github.com/repos/DNSBunker/DNSBunker-Android-App/releases/latest"
    private const val MAX_APK = 300L * 1024 * 1024
    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.MINUTES).followRedirects(true).followSslRedirects(false)
            .apply { if (Trust.needsBundledRoots) sslSocketFactory(Trust.socketFactory, Trust.trustManager) }
            .build()
    }

    @Volatile var available: Release? = null
        private set
    @Volatile var pendingConfirmation: Intent? = null
    @Volatile var foreground = false
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val CONFIRM_ID = 1004

    fun requestConfirmation(context: Context, confirm: Intent) {
        if (foreground || Build.VERSION.SDK_INT < 29) {
            try { context.startActivity(confirm); return } catch (_: Exception) { }
        }
        pendingConfirmation = confirm
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (Build.VERSION.SDK_INT >= 26) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel("dnsbunker_updates", context.getString(R.string.update_channel), NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(context, 3, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = context.getString(R.string.update_confirm_notification)
        val note = NotificationCompat.Builder(context, "dnsbunker_updates").setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("DNSBunker").setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true).build()
        try { NotificationManagerCompat.from(context).notify(CONFIRM_ID, note) } catch (_: SecurityException) { }
    }

    fun resumeConfirmation(context: Context): Intent? {
        val confirm = pendingConfirmation ?: return null
        pendingConfirmation = null
        NotificationManagerCompat.from(context).cancel(CONFIRM_ID)
        return confirm
    }

    fun trustedConfirmation(context: Context, confirm: Intent): Intent? {
        if (confirm.action?.startsWith("android.content.pm.action.CONFIRM_") != true) return null
        val target = confirm.component?.packageName ?: confirm.`package`
        if (target == null || target == context.packageName) return null
        val grants = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        return Intent(confirm).setFlags((confirm.flags and grants.inv()) or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun finished(context: Context) {
        pendingConfirmation = null
        NotificationManagerCompat.from(context).cancel(CONFIRM_ID)
        File(context.cacheDir, "updates").deleteRecursively()
    }

    fun currentVersion(): String = BuildConfig.VERSION_NAME.substringBefore('-')

    fun newer(remote: String, local: String): Boolean {
        val a = remote.split('.').map { it.toIntOrNull() ?: 0 }
        val b = local.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun get(url: String, accept: String): Request =
        Request.Builder().url(url).header("Accept", accept).header("User-Agent", "DNSBunker-Android").build()

    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        val json = http.newCall(get(LATEST, "application/vnd.github+json")).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub ${response.code}")
            JSONObject(response.body?.string() ?: throw IOException("empty response"))
        }
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) return@withContext null
        val version = json.getString("tag_name").removePrefix("v")
        if (!Regex("[0-9]+(\\.[0-9]+){1,3}").matches(version) || !newer(version, currentVersion())) {
            available = null
            return@withContext null
        }
        val apkName = "DNSBunker-$version.apk"
        val assets = json.getJSONArray("assets")
        var apkUrl: String? = null
        var sumsUrl: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            when (asset.optString("name")) {
                apkName -> apkUrl = asset.optString("browser_download_url")
                "SHA256SUMS" -> sumsUrl = asset.optString("browser_download_url")
            }
        }
        val release = Release(version, apkName,
            apkUrl?.takeIf { it.startsWith("https://github.com/") } ?: return@withContext null,
            sumsUrl?.takeIf { it.startsWith("https://github.com/") } ?: return@withContext null,
            changelog(version) ?: clean(json.optString("body")))
        available = release
        release
    }

    fun versionCode(version: String): Int {
        val parts = version.split('.').map { it.toIntOrNull() ?: 0 }
        return parts.getOrElse(0) { 0 } * 10000 + parts.getOrElse(1) { 0 } * 100 + parts.getOrElse(2) { 0 }
    }

    fun clean(markdown: String): String = markdown.lineSequence()
        .map { it.replace("**", "").replace("`", "").trimEnd() }
        .map { if (it.trimStart().startsWith("- ")) "• " + it.trimStart().removePrefix("- ") else it }
        .joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()

    private fun changelog(version: String): String? {
        val locales = if (java.util.Locale.getDefault().language == "de") listOf("de-DE", "en-US") else listOf("en-US")
        for (locale in locales) {
            val url = "https://raw.githubusercontent.com/DNSBunker/DNSBunker-Android-App/v$version/fastlane/metadata/android/$locale/changelogs/${versionCode(version)}.txt"
            val text = runCatching {
                http.newCall(get(url, "text/plain")).execute().use { response ->
                    if (!response.isSuccessful) null
                    else response.body?.byteStream()?.let { DnsWire.readBounded(it, 16384).toString(Charsets.UTF_8) }
                }
            }.getOrNull()
            if (!text.isNullOrBlank()) return clean(text)
        }
        return null
    }

    suspend fun download(context: Context, release: Release): File = withContext(Dispatchers.IO) {
        val expected = http.newCall(get(release.sumsUrl, "text/plain")).execute().use { response ->
            if (!response.isSuccessful) throw IOException("SHA256SUMS ${response.code}")
            val text = response.body?.byteStream()?.let { DnsWire.readBounded(it, 65535).toString(Charsets.UTF_8) } ?: ""
            text.lineSequence().map { it.trim().split(Regex("\\s+")) }
                .firstOrNull { it.size == 2 && it[1].removePrefix("*") == release.apkName }?.get(0)?.lowercase()
                ?: throw IOException("No checksum for ${release.apkName}")
        }
        val dir = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val apk = File(dir, release.apkName)
        val digest = MessageDigest.getInstance("SHA-256")
        http.newCall(get(release.apkUrl, "application/octet-stream")).execute().use { response ->
            if (!response.isSuccessful) throw IOException("APK ${response.code}")
            val body = response.body ?: throw IOException("empty APK")
            var total = 0L
            body.byteStream().use { input ->
                apk.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_APK) throw IOException("APK too large")
                        digest.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                    }
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) { apk.delete(); throw IOException("Checksum mismatch") }
        verify(context, apk)
        apk
    }

    @Suppress("DEPRECATION")
    private fun verify(context: Context, apk: File) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: throw IOException("Invalid APK")
        if (archive.packageName != context.packageName) throw IOException("Wrong package")
        val installed = pm.getPackageInfo(context.packageName, flags)
        if (PackageInfoCompat.getLongVersionCode(archive) <= PackageInfoCompat.getLongVersionCode(installed)) throw IOException("Not newer")
        val remote = signers(archive)
        if (remote.isNotEmpty() && remote != signers(installed)) throw IOException("Signature mismatch")
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val raw = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
            else info.signatures
        return raw.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
    }

    fun canInstall(context: Context): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { output ->
                apk.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
            val callback = Intent(context, UpdateReceiver::class.java).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            session.commit(PendingIntent.getBroadcast(context, id, callback, flags).intentSender)
        }
    }
}
