package com.newlife.tv.net

import android.util.Xml
import com.newlife.tv.data.EpgProgram
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/** Streaming XMLTV (EPG) loader with an in-memory cache per URL. */
object Xmltv {
    private class Guide(
        val byId: Map<String, List<EpgProgram>>,
        val nameToId: Map<String, String>,
        val loadedAt: Long,
    )

    private val cache = ConcurrentHashMap<String, Guide>()
    private val failed = ConcurrentHashMap<String, Long>()
    private val mutex = Mutex()
    private const val TTL = 6 * 3600_000L

    fun clear() { cache.clear(); failed.clear() }

    suspend fun programs(url: String, ua: String, epgId: String, name: String): List<EpgProgram> {
        val g = load(url, ua) ?: return emptyList()
        val id = when {
            epgId.isNotBlank() && g.byId.containsKey(epgId) -> epgId
            epgId.isNotBlank() && g.byId.containsKey(epgId.lowercase()) -> epgId.lowercase()
            else -> g.nameToId[norm(name)]
        } ?: return emptyList()
        return g.byId[id].orEmpty()
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("\\b(hd|fhd|uhd|4k|sd|hevc)\\b"), "").filter { it.isLetterOrDigit() }

    private suspend fun load(url: String, ua: String): Guide? = mutex.withLock {
        cache[url]?.takeIf { System.currentTimeMillis() - it.loadedAt < TTL }?.let { return@withLock it }
        failed[url]?.let { if (System.currentTimeMillis() - it < 10 * 60_000) return@withLock null }
        withContext(Dispatchers.IO) {
            try {
                Http.client.newCall(Http.request(url, mapOf("User-Agent" to ua))).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    val body = r.body ?: throw IOException("empty")
                    parse(body.byteStream()).also { cache[url] = it }
                }
            } catch (e: Exception) {
                failed[url] = System.currentTimeMillis(); null
            }
        }
    }

    private fun parse(raw: InputStream): Guide {
        val bis = BufferedInputStream(raw, 64 * 1024)
        bis.mark(4)
        val b1 = bis.read(); val b2 = bis.read()
        bis.reset()
        val input: InputStream = if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(bis, 64 * 1024) else bis

        val now = System.currentTimeMillis()
        val from = now - 8 * 24 * 3600_000L
        val to = now + 3 * 24 * 3600_000L
        val byId = HashMap<String, MutableList<EpgProgram>>()
        val names = HashMap<String, String>()

        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, null)
        var channelId = ""
        var progChannel = ""; var start = 0L; var stop = 0L
        var title = ""; var desc = ""
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (p.name) {
                    "channel" -> channelId = p.getAttributeValue(null, "id") ?: ""
                    "display-name" -> if (channelId.isNotEmpty()) {
                        val n = p.nextText()
                        names.putIfAbsent(norm(n), channelId)
                    }
                    "programme" -> {
                        progChannel = p.getAttributeValue(null, "channel") ?: ""
                        start = time(p.getAttributeValue(null, "start"))
                        stop = time(p.getAttributeValue(null, "stop"))
                        title = ""; desc = ""
                    }
                    "title" -> if (progChannel.isNotEmpty() && title.isEmpty()) title = p.nextText()
                    "desc" -> if (progChannel.isNotEmpty() && desc.isEmpty()) desc = p.nextText()
                }
            } else if (event == XmlPullParser.END_TAG) {
                when (p.name) {
                    "channel" -> channelId = ""
                    "programme" -> {
                        if (progChannel.isNotEmpty() && stop > from && start < to && stop > start) {
                            byId.getOrPut(progChannel) { ArrayList() }.add(EpgProgram("", title, desc, start, stop))
                        }
                        progChannel = ""
                    }
                }
            }
            event = p.next()
        }
        byId.values.forEach { it.sortBy { e -> e.start } }
        return Guide(byId, names, System.currentTimeMillis())
    }

    private val fmtTz = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
    }
    private val fmtUtc = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() =
            SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }

    private fun time(v: String?): Long {
        if (v.isNullOrBlank()) return 0L
        val s = v.trim()
        return runCatching { fmtTz.get()!!.parse(s)!!.time }.getOrElse {
            runCatching { fmtUtc.get()!!.parse(s.take(14))!!.time }.getOrDefault(0L)
        }
    }
}
