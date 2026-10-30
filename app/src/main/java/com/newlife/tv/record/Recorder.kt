package com.newlife.tv.record

import android.content.Context
import android.os.Environment
import com.newlife.tv.data.DeviceIdentity
import com.newlife.tv.data.PlayRequest
import com.newlife.tv.data.ProfileStore
import com.newlife.tv.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext

/** Where recordings live: app folders on internal storage and on any SD card / USB volume. */
object RecordingStore {
    private const val FOLDER = "NewLifeTV"

    fun volumes(ctx: Context): List<File> =
        ctx.getExternalFilesDirs(Environment.DIRECTORY_MOVIES).filterNotNull()
            .ifEmpty { listOf(File(ctx.filesDir, "recordings")) }

    fun dir(ctx: Context): File {
        val v = volumes(ctx)
        val idx = ProfileStore.settings.value.recordStorageIndex.coerceIn(0, v.size - 1)
        return File(v[idx], FOLDER).apply { mkdirs() }
    }

    fun newFile(ctx: Context, title: String, url: String): File {
        val lower = url.lowercase().substringBefore('?')
        val ext = when {
            lower.endsWith(".mp4") -> "mp4"
            lower.endsWith(".mkv") -> "mkv"
            else -> "ts"
        }
        val safe = title.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim().take(60).ifBlank { "record" }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        return File(dir(ctx), "${safe}_$stamp.$ext")
    }

    fun list(ctx: Context): List<File> =
        volumes(ctx).flatMap { File(it, FOLDER).listFiles()?.toList() ?: emptyList() }
            .filter { it.isFile }.sortedByDescending { it.lastModified() }
}

/** Saves a live/VOD stream to disk: plain HTTP/TS copy or HLS segment download (incl. AES-128). */
object Recorder {
    private val client by lazy { Http.client.newBuilder().readTimeout(30, TimeUnit.SECONDS).build() }

    suspend fun record(req: PlayRequest, file: File, endAt: Long, progress: (Long) -> Unit) = withContext(Dispatchers.IO) {
        val headers = req.headers + ("User-Agent" to req.userAgent.ifBlank { DeviceIdentity.DEFAULT_PLAYER_UA })
        FileOutputStream(file, true).buffered(256 * 1024).use { out ->
            if (req.url.contains("m3u8", ignoreCase = true)) hls(req.url, headers, out, endAt, progress)
            else progressive(req.url, headers, out, endAt, progress)
        }
    }

    private suspend fun progressive(url: String, headers: Map<String, String>, out: OutputStream, endAt: Long, progress: (Long) -> Unit) {
        var total = 0L
        var failures = 0
        val buf = ByteArray(64 * 1024)
        while (coroutineContext.isActive && System.currentTimeMillis() < endAt && failures < 8) {
            try {
                client.newCall(Http.request(url, headers)).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    val body = r.body ?: throw IOException("empty body")
                    val length = body.contentLength()
                    var got = 0L
                    body.byteStream().use { input ->
                        while (true) {
                            coroutineContext.ensureActive()
                            if (System.currentTimeMillis() >= endAt) return
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            got += n; total += n
                            progress(total)
                        }
                    }
                    if (length > 0 && got >= length) return // finished file (VOD)
                }
                failures++
                delay(1500)
            } catch (e: IOException) {
                failures++
                delay(2000)
            }
        }
        if (total == 0L) throw IOException("No data received")
    }

    private data class Key(val uri: String, val iv: String?)
    private data class Segment(val seq: Long, val url: String, val key: Key?)
    private data class MediaPlaylist(val segments: List<Segment>, val target: Int, val ended: Boolean)

    private suspend fun hls(url: String, headers: Map<String, String>, out: OutputStream, endAt: Long, progress: (Long) -> Unit) {
        var playlistUrl = url
        var text = Http.getString(playlistUrl, headers, client)
        if (text.contains("#EXT-X-STREAM-INF")) {
            playlistUrl = Http.resolve(playlistUrl, bestVariant(text))
            text = Http.getString(playlistUrl, headers, client)
        }
        val keys = HashMap<String, ByteArray>()
        var lastSeq = -1L
        var total = 0L
        var errors = 0
        while (coroutineContext.isActive && System.currentTimeMillis() < endAt) {
            val pl = parseMedia(text, playlistUrl)
            for (seg in pl.segments) {
                if (seg.seq <= lastSeq) continue
                if (System.currentTimeMillis() >= endAt) return
                coroutineContext.ensureActive()
                var data = try { Http.getBytes(seg.url, headers, client) } catch (e: IOException) { lastSeq = seg.seq; continue }
                seg.key?.let { k ->
                    val keyBytes = keys.getOrPut(k.uri) { Http.getBytes(k.uri, headers, client) }
                    data = decrypt(data, keyBytes, k.iv, seg.seq)
                }
                out.write(data)
                total += data.size
                lastSeq = seg.seq
                progress(total)
            }
            if (pl.ended) return
            delay((pl.target * 500L).coerceIn(1000L, 6000L))
            text = try {
                Http.getString(playlistUrl, headers, client).also { errors = 0 }
            } catch (e: IOException) {
                if (++errors > 15) throw e
                delay(2000); continue
            }
        }
    }

    private fun bestVariant(master: String): String {
        val lines = master.lines()
        var best = ""
        var bestBw = -1L
        lines.forEachIndexed { i, l ->
            if (l.startsWith("#EXT-X-STREAM-INF")) {
                val bw = Regex("BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val uri = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.trim() ?: return@forEachIndexed
                if (bw > bestBw) { bestBw = bw; best = uri }
            }
        }
        return best
    }

    private fun parseMedia(text: String, base: String): MediaPlaylist {
        var seq = 0L
        var target = 6
        var key: Key? = null
        val segs = ArrayList<Segment>()
        text.lines().forEach { raw ->
            val l = raw.trim()
            when {
                l.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> seq = l.substringAfter(":").trim().toLongOrNull() ?: 0L
                l.startsWith("#EXT-X-TARGETDURATION:") -> target = l.substringAfter(":").trim().toDoubleOrNull()?.toInt() ?: 6
                l.startsWith("#EXT-X-KEY:") -> {
                    val method = Regex("METHOD=([A-Z0-9-]+)").find(l)?.groupValues?.get(1) ?: "NONE"
                    key = if (method == "AES-128") {
                        val uri = Regex("URI=\"([^\"]+)\"").find(l)?.groupValues?.get(1) ?: ""
                        val iv = Regex("IV=0[xX]([0-9A-Fa-f]+)").find(l)?.groupValues?.get(1)
                        Key(Http.resolve(base, uri), iv)
                    } else null
                }
                l.isEmpty() || l.startsWith("#") -> Unit
                else -> segs += Segment(seq++, Http.resolve(base, l), key)
            }
        }
        return MediaPlaylist(segs, target, text.contains("#EXT-X-ENDLIST"))
    }

    private fun decrypt(data: ByteArray, key: ByteArray, ivHex: String?, seq: Long): ByteArray {
        val iv = ByteArray(16)
        if (ivHex != null) {
            val hex = ivHex.padStart(32, '0').takeLast(32)
            for (i in 0 until 16) iv[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        } else {
            for (i in 0 until 8) iv[15 - i] = (seq shr (8 * i)).toByte()
        }
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(data)
    }
}
