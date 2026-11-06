package com.newlife.tv.net

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
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Free, legal movies and series from the Internet Archive (archive.org): public-domain and
 * openly licensed films. Lists come live from the archive's search API, so they stay up to date.
 */
class ArchiveProvider(override val profile: Profile) : IptvProvider {

    private data class Cat(val id: String, val ar: String, val en: String, val query: String)

    private val movieCats = listOf(
        Cat("feature", "أفلام كلاسيكية", "Feature films", "collection:feature_films"),
        Cat("arabic", "أفلام عربية", "Arabic films", "language:(Arabic OR ara) AND mediatype:movies"),
        Cat("scifi", "خيال علمي ورعب", "Sci-fi & horror", "collection:SciFi_Horror"),
        Cat("noir", "أفلام نوار وجريمة", "Film noir", "collection:film_noir"),
        Cat("comedy", "كوميديا", "Comedy", "collection:Comedy_Films"),
        Cat("silent", "أفلام صامتة", "Silent films", "collection:silent_films"),
        Cat("cartoons", "رسوم متحركة", "Cartoons", "collection:animationandcartoons"),
        Cat("docs", "وثائقيات", "Documentaries", "collection:documentaries AND mediatype:movies"),
        Cat("nature", "طبيعة وفضاء", "Nature & space", "(collection:nasa OR subject:nature) AND mediatype:movies"),
    )

    private val seriesCats = listOf(
        Cat("classic_tv", "مسلسلات تلفزيونية كلاسيكية", "Classic TV series", "collection:classic_tv"),
        Cat("cartoon_series", "مسلسلات كرتون", "Cartoon series", "collection:classic_cartoons"),
        Cat("serials", "مسلسلات سينمائية قديمة", "Movie serials", "collection:serials"),
    )

    private val cache = ConcurrentHashMap<String, List<MediaEntry>>()
    private val filesCache = ConcurrentHashMap<String, List<Pair<String, String>>>()
    private val ua = mapOf("User-Agent" to "NewLifeTV/1.0 (Android; IPTV player)")

    override suspend fun connect(): AccountInfo = withContext(Dispatchers.IO) {
        // quick reachability check
        Http.getString("$API?q=collection%3Afeature_films&fl%5B%5D=identifier&rows=1&output=json", ua)
        AccountInfo(listOf(
            L.t("النوع", "Type") to "Internet Archive (archive.org)",
            L.t("المحتوى", "Content") to L.t("أفلام ومسلسلات مجانية وقانونية (ملكية عامة)", "Free & legal movies and series (public domain)"),
            L.t("التحديث", "Updates") to L.t("القوائم تأتي مباشرة من الموقع وتتجدد تلقائياً", "Lists come live from the site and update automatically"),
        ))
    }

    private fun cats(kind: ContentKind) = when (kind) {
        ContentKind.VOD -> movieCats
        ContentKind.SERIES -> seriesCats
        ContentKind.LIVE -> emptyList()
    }

    override suspend fun categories(kind: ContentKind): List<Category> =
        cats(kind).map { Category(it.id, L.t(it.ar, it.en), kind) }

    override suspend fun entries(kind: ContentKind, categoryId: String): List<MediaEntry> = withContext(Dispatchers.IO) {
        val list = cats(kind)
        if (list.isEmpty()) return@withContext emptyList()
        val cat = list.firstOrNull { it.id == categoryId } ?: list.first()
        cache.getOrPut("$kind|${cat.id}") { search(cat.query, kind, cat.id) }
    }

    private fun search(query: String, kind: ContentKind, catId: String): List<MediaEntry> {
        val out = ArrayList<MediaEntry>()
        for (page in 1..3) {
            val url = "$API?q=" + enc("($query) AND mediatype:movies") +
                "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=year&fl%5B%5D=description&fl%5B%5D=avg_rating" +
                "&sort%5B%5D=downloads+desc&rows=200&page=$page&output=json"
            val docs = parseJson(Http.getString(url, ua)).obj()?.get("response").obj()?.get("docs").arr() ?: break
            docs.forEach { el ->
                val o = el.obj() ?: return@forEach
                val id = o.s("identifier").ifBlank { return@forEach }
                out += MediaEntry(
                    id = id,
                    title = first(o, "title").ifBlank { id },
                    logo = "https://archive.org/services/img/$id",
                    categoryId = catId,
                    kind = kind,
                    year = first(o, "year").take(4),
                    plot = first(o, "description").replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().take(600),
                    rating = first(o, "avg_rating").take(3),
                )
            }
            if (docs.size() < 200) break
        }
        return out
    }

    /** Some archive fields are strings, others arrays: take the first value either way. */
    private fun first(o: JsonObject, key: String): String {
        val e = o.get(key) ?: return ""
        return when {
            e.isJsonArray -> e.asJsonArray.firstOrNull()?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            e.isJsonPrimitive -> e.asString
            else -> ""
        }
    }

    /** Playable video files of an item as (file name, display title), best format first. */
    private fun videoFiles(id: String): List<Pair<String, String>> = filesCache.getOrPut(id) {
        val js = parseJson(Http.getString("https://archive.org/metadata/$id", ua)).obj()
            ?: throw IOException(L.t("تعذر قراءة بيانات العنصر", "Could not read item data"))
        val files = js.get("files").arr()?.mapNotNull { it.obj() } ?: emptyList()
        fun score(f: JsonObject): Int {
            val name = f.s("name").lowercase(); val fmt = f.s("format").lowercase()
            return when {
                fmt.contains("h.264") && !name.contains("512kb") -> 0
                fmt.contains("mpeg4") && !fmt.contains("512kb") -> 1
                name.endsWith(".mp4") && !name.contains("512kb") -> 2
                name.endsWith(".mp4") -> 3
                name.endsWith(".mkv") || name.endsWith(".webm") -> 4
                name.endsWith(".ogv") -> 5
                name.endsWith(".avi") || name.endsWith(".mpg") || name.endsWith(".mpeg") -> 6
                else -> 99
            }
        }
        val videos = files.filter { score(it) < 99 && it.s("source") != "metadata" }
        // one entry per base name (an episode is often available in several formats)
        videos.groupBy { it.s("name").substringBeforeLast('.').removeSuffix("_512kb") }
            .map { (_, group) -> group.minByOrNull { score(it) }!! }
            .sortedBy { it.s("name") }
            .map { f -> f.s("name") to f.s("title").ifBlank { f.s("name").substringBeforeLast('.').substringAfterLast('/').replace('_', ' ') } }
    }

    private fun fileUrl(id: String, name: String) =
        "https://archive.org/download/$id/" + name.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    override suspend fun seasons(series: MediaEntry): List<Season> = withContext(Dispatchers.IO) {
        val files = videoFiles(series.id)
        if (files.isEmpty()) return@withContext emptyList()
        listOf(Season("1", series.title, 1, files.mapIndexed { i, (name, title) ->
            Episode(id = name, title = title, number = i + 1, cmd = name, image = series.logo)
        }))
    }

    override suspend fun resolve(entry: MediaEntry): PlayRequest = withContext(Dispatchers.IO) {
        val f = videoFiles(entry.id).firstOrNull()
            ?: throw IOException(L.t("لا يوجد ملف فيديو قابل للتشغيل في هذا العنصر", "No playable video file in this item"))
        PlayRequest(fileUrl(entry.id, f.first), userAgent = ua.getValue("User-Agent"), title = entry.title)
    }

    override suspend fun resolveEpisode(series: MediaEntry, season: Season, episode: Episode): PlayRequest =
        PlayRequest(fileUrl(series.id, episode.cmd), userAgent = ua.getValue("User-Agent"), title = "${series.title} - ${episode.title}")

    override suspend fun epg(entry: MediaEntry): List<EpgProgram> = emptyList()

    companion object {
        private const val API = "https://archive.org/advancedsearch.php"
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    }
}
