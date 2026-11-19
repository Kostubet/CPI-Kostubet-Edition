package com.cosmod.launcherglass.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.cosmod.launcherglass.BuildConfig
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

internal object CpiReleases {
    private const val REPO = "Kostubet/CPI-Kostubet-Edition"
    private const val API = "https://api.github.com/repos/$REPO/releases?per_page=30"
    private const val MAX_APK = 128L * 1024 * 1024
    private val tagPattern = Regex("""v(\d+\.\d+\.\d+)(?:-build(\d+))?""")
    private val filePattern = Regex("""CPI-Kostubet-Edition-(\d+\.\d+\.\d+)-build(\d+)\.apk""")
    private val digestPattern = Regex("[0-9a-fA-F]{64}")

    data class Release(val version: String, val build: Int, val size: Long, val url: String,
        val digest: String?, val checksumUrl: String?)

    fun latest(): Release? = select(JSONArray(bytes(API, 2 * 1024 * 1024).toString(Charsets.UTF_8)))

    fun select(releases: JSONArray): Release? {
        var best: Release? = null
        for (i in 0 until releases.length()) {
            val entry = releases.optJSONObject(i) ?: continue
            if (entry.optBoolean("draft")) continue
            val tag = entry.optString("tag_name")
            val (version, taggedBuild) = tagPattern.matchEntire(tag)?.destructured ?: continue
            val assets = entry.optJSONArray("assets") ?: continue
            val base = "https://github.com/$REPO/releases/download/$tag/"
            for (j in 0 until assets.length()) {
                val asset = assets.optJSONObject(j) ?: continue
                val name = asset.optString("name")
                val (fileVersion, fileBuild) = filePattern.matchEntire(name)?.destructured ?: continue
                val build = fileBuild.toIntOrNull()?.takeIf { it > 0 } ?: continue
                if (fileVersion != version || (taggedBuild.isNotEmpty() && fileBuild != taggedBuild) ||
                    build <= (best?.build ?: 0) || asset.optString("browser_download_url") != base + name) continue
                val size = asset.optLong("size").takeIf { it in 1..MAX_APK } ?: continue
                val digest = asset.optString("digest").takeIf { it.startsWith("sha256:") }
                    ?.removePrefix("sha256:")?.takeIf(digestPattern::matches)
                val checksum = (0 until assets.length()).any {
                    assets.optJSONObject(it)?.optString("browser_download_url") == base + name + ".sha256"
                }
                if (digest != null || checksum) best = Release(version, build, size, base + name, digest,
                    if (checksum) base + name + ".sha256" else null)
            }
        }
        return best
    }

    fun download(context: Context, release: Release, progress: (Int) -> Unit): File {
        require(release.build > BuildConfig.VERSION_CODE)
        val expected = release.digest ?: bytes(checkNotNull(release.checksumUrl), 512).toString(Charsets.UTF_8)
            .trim().split(Regex("\\s+"), limit = 2).first().also { require(digestPattern.matches(it)) }
        val file = File.createTempFile("cpi-update-", ".apk", context.cacheDir)
        try {
            val sha = MessageDigest.getInstance("SHA-256")
            request(release.url) { response ->
                var total = 0L
                var percent = -1
                response.inputStream.use { input ->
                    file.outputStream().buffered().use { output ->
                        val chunk = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(chunk)
                            if (count < 0) break
                            total += count
                            require(total <= release.size)
                            sha.update(chunk, 0, count)
                            output.write(chunk, 0, count)
                            val next = (total * 100 / release.size).toInt()
                            if (next != percent) { percent = next; progress(next) }
                        }
                    }
                }
                require(total == release.size) { "Incomplete APK" }
            }
            require(sha.digest().joinToString("") { "%02x".format(it) }.equals(expected, true)) { "APK checksum mismatch" }
            verify(context, file, release)
            return file
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun verify(context: Context, file: File, release: Release) {
        val manager = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val current = manager.getPackageInfo(context.packageName, flags)
        val next = requireNotNull(manager.getPackageArchiveInfo(file.path, flags)) { "Invalid APK" }
        require(next.packageName == context.packageName && next.longVersionCode == release.build.toLong() &&
            next.longVersionCode > current.longVersionCode && next.versionName == release.version &&
            next.applicationInfo?.minSdkVersion?.let { it <= Build.VERSION.SDK_INT } == true) { "APK version mismatch" }
        val existing = current.signingInfo?.apkContentsSigners.orEmpty().toSet()
        require(existing.isNotEmpty() && existing == next.signingInfo?.apkContentsSigners.orEmpty().toSet()) {
            "APK signing certificate mismatch"
        }
    }

    private fun bytes(url: String, limit: Int): ByteArray = request(url) { response ->
        response.inputStream.use { input ->
            val result = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val size = input.read(chunk)
                if (size < 0) break
                require(result.size() + size <= limit)
                result.write(chunk, 0, size)
            }
            result.toByteArray()
        }
    }

    private fun <T> request(url: String, read: (HttpURLConnection) -> T): T {
        var target = URI(url)
        repeat(5) {
            require(target.scheme == "https" && target.userInfo == null && target.port in setOf(-1, 443) &&
                target.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"))
            val connection = target.toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", "CPI-Kostubet-Edition/${BuildConfig.VERSION_NAME}")
            if (target.host == "api.github.com") connection.setRequestProperty("Accept", "application/vnd.github+json")
            try {
                val status = connection.responseCode
                if (status in 300..399) target = target.resolve(requireNotNull(connection.getHeaderField("Location")))
                else {
                    check(status == HttpURLConnection.HTTP_OK) { "GitHub HTTP $status" }
                    return read(connection)
                }
            } finally { connection.disconnect() }
        }
        error("Too many release download redirects")
    }
}
