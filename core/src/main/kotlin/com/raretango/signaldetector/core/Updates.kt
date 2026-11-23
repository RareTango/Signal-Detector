package com.raretango.signaldetector.core

import org.json.JSONObject

/**
 * In-app updates from GitHub Releases.
 *
 * The CI workflow (.github/workflows/android.yml) publishes every build as a
 * release tagged "build-<number>" whose body contains lines like:
 *
 *     Build: 42
 *     Author: Jane Doe
 *     Commit: 1a2b3c4
 *
 * and attaches the signed APK.
 */
data class ReleaseInfo(
    val buildNumber: Int,
    val author: String,
    val commit: String,
    val message: String,
    val publishedAt: String,
    val apkUrl: String?,
    val apkApiUrl: String?,
    val apkSize: Long,
    val htmlUrl: String,
)

enum class UpdateState { UPDATE_AVAILABLE, CURRENT, AHEAD }

object Updates {
    const val REPO = "RareTango/Signal-Detector"
    const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"

    private fun field(body: String, name: String): String? =
        Regex("""^\s*\**$name\**\s*:\s*(.+?)\s*$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
            .find(body)?.groupValues?.get(1)?.trim()

    fun parseRelease(json: String): ReleaseInfo {
        val o = JSONObject(json)
        val tag = o.optString("tag_name")
        val body = o.optString("body")
        val number = field(body, "Build")?.toIntOrNull()
            ?: Regex("""(\d+)""").find(tag)?.value?.toIntOrNull()
            ?: throw IllegalArgumentException("Release '$tag' has no build number")
        val author = field(body, "Author")
            ?: o.optJSONObject("author")?.optString("login")
            ?: "unknown"
        var apk: JSONObject? = null
        val assets = o.optJSONArray("assets")
        if (assets != null) for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) { apk = a; break }
        }
        return ReleaseInfo(
            buildNumber = number,
            author = author,
            commit = field(body, "Commit") ?: "",
            message = field(body, "Message") ?: o.optString("name"),
            publishedAt = o.optString("published_at"),
            apkUrl = apk?.optString("browser_download_url"),
            apkApiUrl = apk?.optString("url"),
            apkSize = apk?.optLong("size") ?: 0,
            htmlUrl = o.optString("html_url"),
        )
    }

    fun compare(installedBuild: Int, latest: ReleaseInfo): UpdateState = when {
        latest.buildNumber > installedBuild -> UpdateState.UPDATE_AVAILABLE
        latest.buildNumber == installedBuild -> UpdateState.CURRENT
        else -> UpdateState.AHEAD
    }

    /** The one-line status shown next to the Update button. */
    fun statusText(installedBuild: Int, latest: ReleaseInfo?): String {
        if (latest == null) return "Build $installedBuild installed"
        return when (compare(installedBuild, latest)) {
            UpdateState.UPDATE_AVAILABLE -> "Build ${latest.buildNumber} available · sent by ${latest.author}"
            UpdateState.CURRENT -> "Current build (Build ${latest.buildNumber} · sent by ${latest.author})"
            UpdateState.AHEAD -> "Build $installedBuild is newer than the latest release (Build ${latest.buildNumber} · sent by ${latest.author})"
        }
    }
}
