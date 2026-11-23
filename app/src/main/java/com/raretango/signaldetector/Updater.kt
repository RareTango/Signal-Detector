package com.raretango.signaldetector

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import com.raretango.signaldetector.core.ReleaseInfo
import com.raretango.signaldetector.core.Updates
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL

/** Downloads builds published by CI to GitHub Releases and installs them. */
object Updater {
    private fun open(url: String, accept: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "SignalDetector-Android")
        }

    /** Latest published build, or null if nothing has been published yet. */
    fun fetchLatest(): ReleaseInfo? {
        val c = open(Updates.LATEST_URL, "application/vnd.github+json")
        try {
            if (c.responseCode == 404) return null
            if (c.responseCode != 200) throw RuntimeException("GitHub returned HTTP ${c.responseCode}")
            return Updates.parseRelease(c.inputStream.bufferedReader().readText())
        } catch (e: FileNotFoundException) {
            return null
        } finally {
            c.disconnect()
        }
    }

    fun download(context: Context, info: ReleaseInfo, progress: (Float) -> Unit): File {
        val url = info.apkUrl ?: throw IllegalStateException("Build ${info.buildNumber} has no APK attached")
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "signal-detector-${info.buildNumber}.apk")
        val c = open(url, "application/octet-stream")
        try {
            if (c.responseCode != 200) throw RuntimeException("Download failed: HTTP ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: info.apkSize
            c.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        if (total > 0) progress(done.toFloat() / total)
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        return out
    }

    /** True if Android allows this app to install packages; otherwise opens the setting. */
    fun ensureInstallPermission(context: Context): Boolean {
        if (context.packageManager.canRequestPackageInstalls()) return true
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return false
    }

    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("update.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(
                context, id, Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName), flags,
            )
            session.commit(pi.intentSender)
        }
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // the app restarts on the new build
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"
                Toast.makeText(context, "Update failed: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
