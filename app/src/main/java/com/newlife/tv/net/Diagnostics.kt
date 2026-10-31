package com.newlife.tv.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.newlife.tv.data.DeviceIdentity
import com.newlife.tv.data.L
import com.newlife.tv.data.PlayRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Internet / server / stream speed measurements. */
object SpeedTest {
    private val client by lazy {
        Http.client.newBuilder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    }

    val INTERNET_PING_URLS = listOf("https://www.google.com/generate_204", "https://1.1.1.1/", "https://www.cloudflare.com/")
    val INTERNET_DOWNLOAD_URLS = listOf(
        "https://speed.cloudflare.com/__down?bytes=200000000",
        "https://proof.ovh.net/files/100Mb.dat",
        "http://speedtest.tele2.net/100MB.zip",
    )

    fun mbps(bytes: Long, ms: Long): Double = bytes * 8.0 / ms.coerceAtLeast(1) / 1000.0

    /** Best (lowest) response time in ms, or null if unreachable. */
    suspend fun latency(url: String, headers: Map<String, String> = emptyMap(), tries: Int = 3): Long? = withContext(Dispatchers.IO) {
        val times = ArrayList<Long>()
        repeat(tries) {
            runCatching {
                val t0 = System.nanoTime()
                client.newCall(Http.request(url, headers + ("Range" to "bytes=0-0"))).execute().use { it.body?.close() }
                times += (System.nanoTime() - t0) / 1_000_000
            }
        }
        times.minOrNull()
    }

    /** Downloads from [url] for up to [maxMs] and returns the speed in Mbps. */
    suspend fun download(url: String, headers: Map<String, String>, maxMs: Long, onProgress: (Double) -> Unit): Double =
        withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            var bytes = 0L
            var last = t0
            val buf = ByteArray(64 * 1024)
            client.newCall(Http.request(url, headers)).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                val input = r.body?.byteStream() ?: throw IOException("empty")
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    bytes += n
                    val now = System.currentTimeMillis()
                    if (now - last > 300) { onProgress(mbps(bytes, now - t0)); last = now }
                    if (now - t0 > maxMs) break
                }
            }
            if (bytes == 0L) throw IOException("No data")
            mbps(bytes, System.currentTimeMillis() - t0)
        }

    suspend fun internetDownload(maxMs: Long, onProgress: (Double) -> Unit): Double {
        var last: Exception? = null
        for (u in INTERNET_DOWNLOAD_URLS) {
            try { return download(u, mapOf("User-Agent" to DeviceIdentity.DEFAULT_PLAYER_UA), maxMs, onProgress) }
            catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; last = e }
        }
        throw last ?: IOException("unreachable")
    }

    suspend fun internetLatency(): Long? {
        for (u in INTERNET_PING_URLS) latency(u)?.let { return it }
        return null
    }

    /**
     * Measures how fast the server delivers a real channel stream. For HLS the playlist is followed
     * down to its media segments, which are then downloaded back-to-back.
     */
    suspend fun streamSpeed(req: PlayRequest, maxMs: Long, onProgress: (Double) -> Unit): Double = withContext(Dispatchers.IO) {
        val headers = req.headers + ("User-Agent" to req.userAgent.ifBlank { DeviceIdentity.DEFAULT_PLAYER_UA })
        if (!req.url.contains("m3u8", true)) return@withContext download(req.url, headers, maxMs, onProgress)
        var url = req.url
        var text = Http.getString(url, headers, client)
        if (text.contains("#EXT-X-STREAM-INF")) {
            val lines = text.lines()
            val variant = lines.lastOrNull { it.isNotBlank() && !it.startsWith("#") }?.trim() ?: throw IOException("empty playlist")
            url = Http.resolve(url, variant)
            text = Http.getString(url, headers, client)
        }
        val segments = text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { Http.resolve(url, it.trim()) }
        if (segments.isEmpty()) throw IOException("no segments")
        val t0 = System.currentTimeMillis()
        var bytes = 0L
        for (s in segments) {
            coroutineContext.ensureActive()
            bytes += Http.getBytes(s, headers, client).size
            val el = System.currentTimeMillis() - t0
            onProgress(mbps(bytes, el))
            if (el > maxMs) break
        }
        mbps(bytes, System.currentTimeMillis() - t0)
    }

    fun networkType(ctx: Context): String {
        if (Build.VERSION.SDK_INT < 23) return ""
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return L.t("غير متصل", "Offline")
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> L.t("كابل إيثرنت", "Ethernet")
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> L.t("بيانات الجوال", "Mobile data")
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> L.t("شبكة أخرى", "Other")
        }
    }
}

/** Quick check of a stream link: HTTP status, response time and detected format. */
object StreamProbe {
    private val client by lazy {
        Http.client.newBuilder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    }

    suspend fun probe(r: PlayRequest): String = withContext(Dispatchers.IO) {
        if (!r.url.startsWith("http")) return@withContext L.t("رابط غير HTTP: ", "Non-HTTP link: ") + r.url.substringBefore(':')
        val headers = r.headers + ("User-Agent" to r.userAgent.ifBlank { DeviceIdentity.DEFAULT_PLAYER_UA })
        val t0 = System.currentTimeMillis()
        try {
            client.newCall(Http.request(r.url, headers)).execute().use { resp ->
                val ms = System.currentTimeMillis() - t0
                val buf = ByteArray(4096)
                var n = 0
                resp.body?.byteStream()?.let { input ->
                    while (n < buf.size) { val k = input.read(buf, n, buf.size - n); if (k < 0) break; n += k }
                }
                val head = String(buf, 0, n.coerceAtMost(200), Charsets.ISO_8859_1)
                val format = when {
                    n == 0 -> L.t("لا توجد بيانات", "no data")
                    head.startsWith("#EXTM3U") -> "HLS (m3u8)"
                    buf[0] == 0x47.toByte() -> "MPEG-TS"
                    head.contains("ftyp") -> "MP4"
                    head.startsWith("FLV") -> "FLV"
                    head.contains("<MPD", true) -> "DASH"
                    head.contains("<html", true) -> "HTML (" + L.t("ليس فيديو", "not a video") + ")"
                    else -> resp.header("Content-Type") ?: "?"
                }
                val hint = when (resp.code) {
                    in 200..299 -> L.t("الرابط يعمل ويرسل بيانات.", "The link works and sends data.")
                    401, 403 -> L.t("الخادم رفض الطلب: الاشتراك منتهٍ أو الجهاز/MAC غير مصرح أو الـUser-Agent مرفوض.",
                        "Server refused: subscription expired, device/MAC not allowed or User-Agent blocked.")
                    404 -> L.t("القناة غير موجودة على الخادم حالياً.", "Channel not found on the server.")
                    458, 509, 429 -> L.t("تم تجاوز عدد الاتصالات المسموح به في الاشتراك.", "Connection limit of the subscription reached.")
                    in 500..599 -> L.t("مشكلة في الخادم نفسه، حاول لاحقاً.", "Server-side problem, try later.")
                    else -> ""
                }
                val redirected = if (resp.request.url.toString() != r.url) "\n→ " + resp.request.url.host else ""
                "HTTP ${resp.code} • $ms ms • $format$redirected\n$hint"
            }
        } catch (e: Exception) {
            L.t("فشل الاتصال بخادم البث: ", "Could not reach the stream server: ") + (e.message ?: e.javaClass.simpleName)
        }
    }
}
