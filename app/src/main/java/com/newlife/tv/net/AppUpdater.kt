package com.newlife.tv.net

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Self-update from the GitHub releases of the project repository.
 * Every successful GitHub build publishes a release "v<build number>" with the APK attached.
 */
object AppUpdater {
    /** Public repository that only holds the published APKs (the source code repository can stay private). */
    const val REPO = "abds7443/NEW-LIFE-TV-Updates"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"

    data class Release(val versionCode: Long, val name: String, val notes: String, val apkUrl: String, val size: Long)

    fun currentVersionCode(ctx: Context): Long =
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0))

    fun currentVersionName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    /** Latest published release, or null if none / no APK attached. */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val js = parseJson(Http.getString(API, mapOf("Accept" to "application/vnd.github+json", "User-Agent" to "NewLifeTV-Updater"))).obj()
            ?: return@withContext null
        val tag = js.s("tag_name")
        val code = Regex("(\\d+)\\s*$").find(tag)?.groupValues?.get(1)?.toLongOrNull() ?: return@withContext null
        val asset = js.get("assets").arr()?.mapNotNull { it.obj() }?.firstOrNull { it.s("name").endsWith(".apk") }
            ?: return@withContext null
        Release(code, js.s("name").ifBlank { tag }, js.s("body"), asset.s("browser_download_url"), asset.l("size"))
    }

    /** Downloads the APK into the app cache; [progress] gets 0..1. */
    suspend fun download(ctx: Context, r: Release, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "NewLifeTV-${r.versionCode}.apk")
        Http.client.newCall(Http.request(r.apkUrl, mapOf("User-Agent" to "NewLifeTV-Updater"))).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("empty")
            val total = body.contentLength().takeIf { it > 0 } ?: r.size
            var done = 0L
            val buf = ByteArray(64 * 1024)
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) progress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        file
    }

    /** True when Android lets this app open the installer (Android 8+ asks the user once). */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", apk)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }
}
