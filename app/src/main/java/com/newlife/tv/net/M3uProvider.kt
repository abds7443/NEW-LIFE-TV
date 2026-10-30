package com.newlife.tv.net

import android.content.Context
import android.net.Uri
import com.newlife.tv.data.AccountInfo
import com.newlife.tv.data.Category
import com.newlife.tv.data.ContentKind
import com.newlife.tv.data.EpgProgram
import com.newlife.tv.data.Episode
import com.newlife.tv.data.L
import com.newlife.tv.data.MediaEntry
import com.newlife.tv.data.PlayRequest
import com.newlife.tv.data.Profile
import com.newlife.tv.data.Season
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** M3U / M3U8 playlist (URL, local file or content:// document). */
class M3uProvider(private val ctx: Context, override val profile: Profile) : IptvProvider {
    private var items: List<MediaEntry> = emptyList()
    private var tvgUrl = ""

    override suspend fun connect(): AccountInfo = withContext(Dispatchers.IO) {
        val src = profile.portalUrl.trim()
        if (src.isBlank()) throw IOException(L.t("أدخل رابط قائمة التشغيل", "Enter the playlist URL"))
        val text = when {
            src.startsWith("content://") -> ctx.contentResolver.openInputStream(Uri.parse(src))?.bufferedReader()?.use { it.readText() }
                ?: throw IOException("Cannot open file")
            src.startsWith("file://") -> File(Uri.parse(src).path ?: "").readText()
            src.startsWith("/") -> File(src).readText()
            else -> Http.getString(if (src.startsWith("http")) src else "http://$src", mapOf("User-Agent" to playerUa))
        }
        val parsed = M3uParser.parse(text)
        if (parsed.entries.isEmpty()) throw IOException(L.t("قائمة التشغيل فارغة أو غير صالحة", "Playlist is empty or invalid"))
        items = parsed.entries
        tvgUrl = profile.epgUrl.ifBlank { parsed.tvgUrl }
        AccountInfo(listOf(
            L.t("النوع", "Type") to "M3U Playlist",
            L.t("المصدر", "Source") to src,
            L.t("القنوات", "Channels") to items.count { it.kind == ContentKind.LIVE }.toString(),
            L.t("الأفلام", "Movies") to items.count { it.kind == ContentKind.VOD }.toString(),
            L.t("المسلسلات", "Series") to items.count { it.kind == ContentKind.SERIES }.toString(),
            L.t("دليل البرامج", "EPG") to tvgUrl.ifBlank { L.t("غير متوفر", "None") },
        ))
    }

    override suspend fun categories(kind: ContentKind): List<Category> {
        val groups = items.filter { it.kind == kind }.map { it.categoryId }.distinct()
        return listOf(Category(IptvProvider.ALL, L.t("الكل", "All"), kind)) +
            groups.map { Category(it, it.ifBlank { L.t("بدون تصنيف", "Uncategorized") }, kind) }
    }

    override suspend fun entries(kind: ContentKind, categoryId: String): List<MediaEntry> =
        items.filter { it.kind == kind && (categoryId == IptvProvider.ALL || it.categoryId == categoryId) }

    override suspend fun seasons(series: MediaEntry): List<Season> =
        listOf(Season("1", series.title, 1, listOf(Episode(series.id, series.title, 1, series.cmd))))

    override suspend fun resolve(entry: MediaEntry): PlayRequest = PlayRequest(
        url = entry.cmd,
        headers = if (entry.referer.isNotBlank()) mapOf("Referer" to entry.referer) else emptyMap(),
        userAgent = entry.userAgent.ifBlank { playerUa },
        title = entry.title,
        isLive = entry.kind == ContentKind.LIVE,
        drmScheme = entry.drmScheme,
        drmLicense = entry.drmLicense,
    )

    override suspend fun resolveEpisode(series: MediaEntry, season: Season, episode: Episode) = resolve(series)

    override suspend fun epg(entry: MediaEntry): List<EpgProgram> =
        if (tvgUrl.isBlank()) emptyList() else Xmltv.programs(tvgUrl, playerUa, entry.epgId, entry.title)

    override suspend fun catchup(entry: MediaEntry, program: EpgProgram): PlayRequest? {
        if (entry.catchupDays <= 0 && entry.catchupType.isBlank()) return null
        val start = program.start / 1000
        val end = program.end / 1000
        val now = System.currentTimeMillis() / 1000
        fun fill(t: String): String {
            var s = t
            val d = Date(program.start)
            mapOf("Y" to "yyyy", "m" to "MM", "d" to "dd", "H" to "HH", "M" to "mm", "S" to "ss").forEach { (k, f) ->
                s = s.replace("{$k}", SimpleDateFormat(f, Locale.US).format(d))
            }
            listOf("{utc}", "\${start}", "{start}", "{timestamp}", "\${timestamp}").forEach { s = s.replace(it, "$start") }
            listOf("{utcend}", "\${end}", "{end}").forEach { s = s.replace(it, "$end") }
            listOf("{lutc}", "\${now}", "{now}").forEach { s = s.replace(it, "$now") }
            listOf("{duration}", "\${duration}").forEach { s = s.replace(it, "${end - start}") }
            s = s.replace("{offset}", "${now - start}").replace("\${offset}", "${now - start}")
            return s
        }
        val base = entry.cmd
        val url = when (entry.catchupType.lowercase()) {
            "append" -> base + fill(entry.catchupSource)
            "flussonic", "flussonic-hls", "fs" ->
                if (base.contains("/mpegts")) base.replace("/mpegts", "/timeshift_abs-$start.ts")
                else base.replace(Regex("/(index|video|mono)\\.m3u8"), "/$1-$start-${end - start}.m3u8")
            "default" -> if (entry.catchupSource.isNotBlank()) fill(entry.catchupSource) else shift(base, start, now)
            else -> if (entry.catchupSource.isNotBlank()) {
                if (entry.catchupSource.startsWith("http")) fill(entry.catchupSource) else base + fill(entry.catchupSource)
            } else shift(base, start, now)
        }
        return resolve(entry).copy(url = url, title = "${entry.title} - ${program.title}", isLive = false)
    }

    private fun shift(base: String, start: Long, now: Long) =
        base + (if (base.contains("?")) "&" else "?") + "utc=$start&lutc=$now"
}

object M3uParser {
    data class Result(val entries: List<MediaEntry>, val tvgUrl: String)

    private val ATTR = Regex("([\\w-]+)=\"([^\"]*)\"")

    fun parse(text: String): Result {
        val out = ArrayList<MediaEntry>()
        var tvg = ""
        var attrs: Map<String, String> = emptyMap()
        var title = ""
        var group = ""
        var ua = ""
        var ref = ""
        var licType = ""
        var licKey = ""
        var pending = false
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTM3U") -> {
                    val a = ATTR.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    tvg = a["url-tvg"] ?: a["x-tvg-url"] ?: a["tvg-url"] ?: ""
                    tvg = tvg.split(',').firstOrNull()?.trim() ?: ""
                }
                line.startsWith("#EXTINF") -> {
                    val comma = titleComma(line)
                    val head = if (comma >= 0) line.substring(0, comma) else line
                    attrs = ATTR.findAll(head).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    title = if (comma >= 0) line.substring(comma + 1).trim() else ""
                    group = attrs["group-title"] ?: ""
                    ua = attrs["http-user-agent"] ?: attrs["user-agent"] ?: ""
                    ref = attrs["http-referrer"] ?: ""
                    licType = ""; licKey = ""
                    pending = true
                }
                line.startsWith("#EXTGRP:") -> group = line.substringAfter(":").trim()
                line.startsWith("#EXTVLCOPT:") -> {
                    val opt = line.substringAfter(":")
                    when {
                        opt.startsWith("http-user-agent=") -> ua = opt.substringAfter("=")
                        opt.startsWith("http-referrer=") || opt.startsWith("http-referer=") -> ref = opt.substringAfter("=")
                    }
                }
                line.startsWith("#KODIPROP:") -> {
                    val opt = line.substringAfter(":")
                    when {
                        opt.contains("license_type=") -> licType = opt.substringAfter("=")
                        opt.contains("license_key=") -> licKey = opt.substringAfter("=")
                    }
                }
                line.startsWith("#") -> Unit
                else -> {
                    var url = line
                    if (url.contains("|")) {
                        val opts = url.substringAfter("|")
                        url = url.substringBefore("|")
                        opts.split("&").forEach { kv ->
                            val k = kv.substringBefore("=").lowercase(); val v = kv.substringAfter("=", "")
                            if (k == "user-agent") ua = v
                            if (k == "referer" || k == "referrer") ref = v
                        }
                    }
                    val lower = url.lowercase()
                    val kind = when {
                        lower.contains("/series/") -> ContentKind.SERIES
                        lower.contains("/movie/") || lower.contains("/vod/") -> ContentKind.VOD
                        !lower.contains("/live/") && Regex("\\.(mp4|mkv|avi|mov)(\\?|$)").containsMatchIn(lower) -> ContentKind.VOD
                        else -> ContentKind.LIVE
                    }
                    val name = title.ifBlank { attrs["tvg-name"] ?: url.substringAfterLast('/') }
                    out += MediaEntry(
                        id = Integer.toHexString(url.hashCode()) + "_" + out.size,
                        title = name,
                        logo = attrs["tvg-logo"] ?: attrs["logo"] ?: "",
                        categoryId = group,
                        kind = kind,
                        number = attrs["tvg-chno"]?.toIntOrNull() ?: 0,
                        cmd = url,
                        epgId = attrs["tvg-id"] ?: "",
                        catchupDays = (attrs["catchup-days"] ?: attrs["tvg-rec"] ?: attrs["timeshift"])?.toIntOrNull()
                            ?: if (attrs.containsKey("catchup")) 7 else 0,
                        catchupType = attrs["catchup"] ?: "",
                        catchupSource = attrs["catchup-source"] ?: "",
                        userAgent = ua,
                        referer = ref,
                        drmScheme = licType,
                        drmLicense = licKey,
                    )
                    if (pending) { attrs = emptyMap(); title = ""; ua = ""; ref = ""; licType = ""; licKey = ""; pending = false }
                }
            }
        }
        return Result(out, tvg)
    }

    /** Index of the comma that separates attributes from the title (first comma outside quotes). */
    private fun titleComma(line: String): Int {
        var inQuotes = false
        for (i in line.indices) {
            when (line[i]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) return i
            }
        }
        return -1
    }
}
