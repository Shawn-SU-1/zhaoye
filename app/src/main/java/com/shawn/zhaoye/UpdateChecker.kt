package com.shawn.zhaoye

import android.content.Context
import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val currentVersion: String,
    val version: String,
    val githubUrl: String,
    val domesticUrl: String,
    val baiduUrl: String,
    val notes: String,
)

data class UpdateCheckResult(
    val update: UpdateInfo? = null,
    val error: String? = null,
)

/**
 * Checks several public endpoints in parallel. The CDN copies are tried first
 * for mainland networks; the GitHub feed remains a fallback. This only detects
 * an update and opens a download page. Android still owns install confirmation.
 */
object UpdateChecker {
    const val DOMESTIC_DOWNLOAD_URL = "https://wwbjy.lanzouv.com/i3EI64e0cfvc"
    const val BAIDU_DOWNLOAD_URL =
        "https://pan.baidu.com/s/1f_aZvZQsR4no8KrLcxiJHQ?pwd=h1ec"
    const val GITHUB_RELEASES_PAGE = "https://github.com/Shawn-SU-1/zhaoye/releases"

    private val manifestUrls = listOf(
        "https://gcore.jsdelivr.net/gh/Shawn-SU-1/zhaoye@main/update.json",
        "https://testingcf.jsdelivr.net/gh/Shawn-SU-1/zhaoye@main/update.json",
        "https://ghfast.top/https://raw.githubusercontent.com/Shawn-SU-1/zhaoye/main/update.json",
        "https://ghproxy.net/https://raw.githubusercontent.com/Shawn-SU-1/zhaoye/main/update.json",
        "https://raw.githubusercontent.com/Shawn-SU-1/zhaoye/main/update.json",
    )

    suspend fun check(context: Context): UpdateCheckResult = withContext(Dispatchers.IO) {
        runCatching {
            val current = currentVersion(context)
            val candidates = supervisorScope {
                manifestUrls.map { url ->
                    async(Dispatchers.IO) {
                        runCatching { fetchManifest(url, current) }.getOrNull()
                    }
                }.awaitAll().filterNotNull()
            }
            var newest: UpdateInfo? = null
            for (candidate in candidates) {
                if (newest == null || isNewer(candidate.version, newest.version)) {
                    newest = candidate
                }
            }
            val update = newest ?: fetchReleaseFeed(current)
            if (update != null && isNewer(update.version, current)) {
                UpdateCheckResult(update = update)
            } else {
                UpdateCheckResult()
            }
        }.getOrElse {
            UpdateCheckResult(error = it.message ?: it.javaClass.simpleName)
        }
    }

    private fun fetchManifest(url: String, current: String): UpdateInfo? {
        val root = JSONObject(fetchText(url, "application/json", current))
        val version = root.getString("version").trim().trimStart('v', 'V')
        val githubPage = root.optString("github_release_url")
            .ifBlank { "$GITHUB_RELEASES_PAGE/tag/v$version" }
        val githubApk = root.optString("github_apk_url").ifBlank { githubPage }
        val domestic = root.optString("domestic_url").ifBlank { DOMESTIC_DOWNLOAD_URL }
        val baidu = root.optString("baidu_url").ifBlank { BAIDU_DOWNLOAD_URL }
        return UpdateInfo(
            currentVersion = current,
            version = version,
            githubUrl = githubApk,
            domesticUrl = domestic,
            baiduUrl = baidu,
            notes = root.optString("release_notes").trim().take(1_600),
        )
    }

    /** Used when a release exists but its manifest is temporarily unavailable. */
    private fun fetchReleaseFeed(current: String): UpdateInfo? {
        val feed = fetchText(
            "$GITHUB_RELEASES_PAGE.atom",
            "application/atom+xml",
            current,
        )
        val entry = Regex("""<entry>([\s\S]*?)</entry>""")
            .find(feed)
            ?.groupValues
            ?.get(1)
            ?: return null
        val tag = Regex("""releases/tag/([^"<]+)""")
            .find(entry)
            ?.groupValues
            ?.get(1)
            ?: return null
        val version = tag.trimStart('v', 'V')
        if (!isNewer(version, current)) return null
        val githubApk = Regex(
            """https://github\.com/Shawn-SU-1/zhaoye/releases/download/[^"<&\s]+\.apk""",
        ).find(entry)?.value ?: "$GITHUB_RELEASES_PAGE/tag/$tag"
        val encodedNotes = Regex("""<content type="html">([\s\S]*?)</content>""")
            .find(entry)
            ?.groupValues
            ?.get(1)
            .orEmpty()
        return UpdateInfo(
            currentVersion = current,
            version = version,
            githubUrl = githubApk,
            domesticUrl = DOMESTIC_DOWNLOAD_URL,
            baiduUrl = BAIDU_DOWNLOAD_URL,
            notes = Html.fromHtml(
                unescapeXml(encodedNotes),
                Html.FROM_HTML_MODE_LEGACY,
            ).toString().trim().take(1_600),
        )
    }

    private fun fetchText(url: String, accept: String, current: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4_000
            readTimeout = 5_000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "Zhaoye/$current")
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("HTTP $code: $url")
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    internal fun isNewer(candidate: String, current: String): Boolean {
        val a = versionParts(candidate)
        val b = versionParts(current)
        if (a.isEmpty() || b.isEmpty()) return false
        for (index in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(index) { 0 }
            val right = b.getOrElse(index) { 0 }
            if (left != right) return left > right
        }
        return false
    }

    private fun versionParts(value: String): List<Int> =
        Regex("""\d+""").findAll(value).mapNotNull { it.value.toIntOrNull() }.toList()

    private fun unescapeXml(value: String): String = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")

    private fun currentVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty().ifBlank { "0" }
}
