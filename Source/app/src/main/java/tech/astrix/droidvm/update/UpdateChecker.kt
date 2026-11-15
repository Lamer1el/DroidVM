package tech.astrix.droidvm.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import tech.astrix.droidvm.BuildConfig
import tech.astrix.droidvm.i18n.tr
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseAsset(val name: String, val url: String, val size: Long)

data class ReleaseInfo(
    val name: String,
    val tag: String,
    val body: String,
    val htmlUrl: String,
    val publishedAt: String,
    val prerelease: Boolean,
    val apk: ReleaseAsset?,
) {
    val isNewer: Boolean get() = UpdateChecker.compareVersions(tag, BuildConfig.VERSION_NAME) > 0
}

sealed interface UpdateResult {
    data class Found(val release: ReleaseInfo) : UpdateResult
    data object NoReleases : UpdateResult
    data class Failed(val message: String) : UpdateResult
}

/**
 * Checks GitHub Releases of the official repository. Only public, unauthenticated API calls are made
 * (no personal data is sent). The app only compares versions and shows the release notes; downloading
 * and installing is done by the user in the browser / system installer (no in-app installer).
 */
object UpdateChecker {
    const val REPO = "Lamer1el/DroidVM"
    const val REPO_URL = "https://github.com/$REPO"
    private const val API = "https://api.github.com/repos/$REPO/releases"

    private val json = Json { ignoreUnknownKeys = true }

    /** Compares dotted numeric versions; "v1.2" > "1.1.9". Non-numeric parts are ignored. */
    fun compareVersions(a: String, b: String): Int {
        fun parts(v: String) = Regex("""\d+""").findAll(v.substringBefore('-').substringBefore('+')).map { it.value.toIntOrNull() ?: 0 }.toList()
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    private fun get(url: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "DroidVM/${BuildConfig.VERSION_NAME}")
        return try {
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            code to text
        } finally { c.disconnect() }
    }

    private fun parse(o: JsonObject): ReleaseInfo {
        fun str(k: String) = o[k]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }?.takeIf { it != "null" } ?: ""
        val assets = (o["assets"] as? JsonArray)?.map { it.jsonObject }?.map { a ->
            ReleaseAsset(a["name"]?.jsonPrimitive?.content ?: "", a["browser_download_url"]?.jsonPrimitive?.content ?: "",
                a["size"]?.jsonPrimitive?.longOrNull ?: 0L)
        } ?: emptyList()
        val apk = assets.filter { it.name.endsWith(".apk", true) }
            .sortedByDescending { it.name.contains("release", true) }.firstOrNull()
        val tag = str("tag_name")
        return ReleaseInfo(
            name = str("name").ifBlank { tag }, tag = tag, body = str("body").trim(),
            htmlUrl = str("html_url").ifBlank { "$REPO_URL/releases" }, publishedAt = str("published_at").take(10),
            prerelease = o["prerelease"]?.jsonPrimitive?.content == "true", apk = apk,
        )
    }

    suspend fun check(): UpdateResult = withContext(Dispatchers.IO) {
        try {
            val (code, text) = get("$API/latest")
            when (code) {
                200 -> UpdateResult.Found(parse(json.parseToJsonElement(text).jsonObject))
                404 -> {
                    // no stable release yet - fall back to the newest pre-release, if any
                    val (c2, t2) = get("$API?per_page=5")
                    val list = if (c2 == 200) json.parseToJsonElement(t2).jsonArray.map { it.jsonObject } else emptyList()
                    list.firstOrNull { it["draft"]?.jsonPrimitive?.content != "true" }?.let { UpdateResult.Found(parse(it)) }
                        ?: UpdateResult.NoReleases
                }
                403, 429 -> UpdateResult.Failed(tr("GitHub rate limit reached, try again later"))
                else -> UpdateResult.Failed("HTTP $code")
            }
        } catch (e: IOException) {
            UpdateResult.Failed(tr("No connection to GitHub: {}").replace("{}", e.message ?: e.javaClass.simpleName))
        } catch (e: Exception) {
            UpdateResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Opens a URL in the user's browser (Intent.ACTION_VIEW). DroidVM itself never downloads or installs
     * APKs: the user downloads the update from the GitHub release page and installs it with the system
     * package installer, so the app needs no REQUEST_INSTALL_PACKAGES permission.
     */
    /** Downloads the release APK into the app cache; [progress] gets 0..1 (or -1 when the size is unknown). */
    suspend fun download(ctx: Context, release: ReleaseInfo, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val asset = release.apk ?: throw IOException(tr("This release has no APK file"))
        val dir = File(ctx.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val out = File(dir, "DroidVM-${release.tag.replace(Regex("[^A-Za-z0-9._-]"), "_")}.apk")
        var url = asset.url
        var conn: HttpURLConnection
        var hops = 0
        while (true) {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15000; conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", "DroidVM/${BuildConfig.VERSION_NAME}")
            val code = conn.responseCode
            if (code in 300..399 && hops++ < 5) {
                val next = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                // only follow HTTPS redirects (GitHub -> objects.githubusercontent.com)
                if (!next.startsWith("https://")) throw IOException("Insecure redirect")
                url = next; continue
            }
            if (code !in 200..299) throw IOException("HTTP $code")
            break
        }
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: asset.size
        try {
            conn.inputStream.use { inp ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024); var done = 0L; var n: Int
                    while (inp.read(buf).also { n = it } >= 0) {
                        o.write(buf, 0, n); done += n
                        progress(if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else -1f)
                    }
                }
            }
        } finally { conn.disconnect() }
        if (total > 0 && out.length() != total) { out.delete(); throw IOException(tr("Download incomplete")) }
        verify(ctx, out)
        out
    }

    /** The downloaded file must be a DroidVM APK signed with the same certificate as the installed app. */
    @Suppress("DEPRECATION")
    private fun verify(ctx: Context, apk: File) {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive: PackageInfo = pm.getPackageArchiveInfo(apk.path, flags)
            ?: run { apk.delete(); throw IOException(tr("The downloaded file is not a valid APK")) }
        if (archive.packageName != ctx.packageName) { apk.delete(); throw IOException(tr("The downloaded APK is not DroidVM")) }
        val installed = pm.getPackageInfo(ctx.packageName, flags)
        fun certs(p: PackageInfo): Set<String> = (if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.apkContentsSigners else p.signatures)
            ?.map { it.toCharsString() }?.toSet().orEmpty()
        val a = certs(archive); val b = certs(installed)
        if (a.isNotEmpty() && b.isNotEmpty() && a != b) {
            apk.delete(); throw IOException(tr("The update is signed with a different key and cannot be installed over this version"))
        }
    }

    /** true when the system installer was opened; false when the user first has to allow installs from DroidVM. */
    fun install(ctx: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { ctx.startActivity(i); true } catch (_: ActivityNotFoundException) { false }
    }

    fun openPage(ctx: Context, url: String): Boolean = try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) { false }
}
