package com.newlife.tv.net

import android.util.Base64
import com.google.gson.JsonElement
import com.google.gson.JsonObject
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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/** Xtream Codes API (player_api.php). Accepts a plain server URL or a full get.php link. */
class XtreamProvider(override val profile: Profile) : IptvProvider {
    private val base: String
    private val user: String
    private val pass: String
    private var serverTz: String = TimeZone.getDefault().id
    private val cache = ConcurrentHashMap<String, List<MediaEntry>>()

    init {
        var u = profile.portalUrl.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        val h = u.toHttpUrlOrNull()
        user = profile.username.ifBlank { h?.queryParameter("username") ?: "" }
        pass = profile.password.ifBlank { h?.queryParameter("password") ?: "" }
        base = if (h != null) {
            var path = h.encodedPath.trimEnd('/')
            for (suffix in listOf("/player_api.php", "/get.php", "/xmltv.php", "/panel_api.php", "/c")) path = path.removeSuffix(suffix)
            "${h.scheme}://${h.host}:${h.port}$path"
        } else u.trimEnd('/')
    }

    private fun api(action: String?, extra: Map<String, String> = emptyMap()): JsonElement? {
        val b = "$base/player_api.php".toHttpUrl().newBuilder()
            .addQueryParameter("username", user).addQueryParameter("password", pass)
        action?.let { b.addQueryParameter("action", it) }
        extra.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return parseJson(Http.getString(b.build().toString(), mapOf("User-Agent" to playerUa)))
    }

    override suspend fun connect(): AccountInfo = withContext(Dispatchers.IO) {
        if (user.isBlank()) throw IOException(L.t("أدخل اسم المستخدم وكلمة المرور", "Enter username and password"))
        val js = api(null).obj() ?: throw IOException(L.t("الخادم لم يرد ببيانات Xtream", "Server did not return Xtream data"))
        val ui = js.get("user_info").obj()
        if (ui == null || ui.s("auth") == "0") throw IOException(L.t("اسم المستخدم أو كلمة المرور غير صحيحة", "Wrong username or password"))
        val si = js.get("server_info").obj()
        serverTz = si.s("timezone").ifBlank { serverTz }
        val exp = ui.l("exp_date")
        AccountInfo(listOf(
            L.t("النوع", "Type") to "Xtream Codes",
            L.t("الخادم", "Server") to base,
            L.t("المستخدم", "User") to user,
            L.t("الحالة", "Status") to ui.s("status"),
            L.t("تاريخ الانتهاء", "Expiry") to if (exp > 0) L.dateTime(exp * 1000) else L.t("غير محدود", "Unlimited"),
            L.t("تجريبي", "Trial") to if (ui.s("is_trial") == "1") L.t("نعم", "Yes") else L.t("لا", "No"),
            L.t("الاتصالات", "Connections") to "${ui.s("active_cons")} / ${ui.s("max_connections")}",
            L.t("الصيغ المسموحة", "Formats") to (ui.get("allowed_output_formats").arr()?.joinToString { it.asString } ?: ""),
            L.t("المنطقة الزمنية", "Timezone") to serverTz,
        ).filter { it.second.isNotBlank() })
    }

    override suspend fun categories(kind: ContentKind): List<Category> = withContext(Dispatchers.IO) {
        val action = when (kind) {
            ContentKind.LIVE -> "get_live_categories"
            ContentKind.VOD -> "get_vod_categories"
            ContentKind.SERIES -> "get_series_categories"
        }
        val list = api(action).arr()?.mapNotNull { el ->
            val o = el.obj() ?: return@mapNotNull null
            Category(o.s("category_id"), o.s("category_name"), kind)
        } ?: emptyList()
        listOf(Category(IptvProvider.ALL, L.t("الكل", "All"), kind)) + list
    }

    override suspend fun entries(kind: ContentKind, categoryId: String): List<MediaEntry> = withContext(Dispatchers.IO) {
        cache.getOrPut("$kind|$categoryId") {
            val action = when (kind) {
                ContentKind.LIVE -> "get_live_streams"
                ContentKind.VOD -> "get_vod_streams"
                ContentKind.SERIES -> "get_series"
            }
            val extra = if (categoryId == IptvProvider.ALL) emptyMap() else mapOf("category_id" to categoryId)
            api(action, extra).arr()?.mapNotNull { el -> el.obj()?.let { map(it, kind) } } ?: emptyList()
        }
    }

    private fun map(o: JsonObject, kind: ContentKind): MediaEntry = when (kind) {
        ContentKind.LIVE -> MediaEntry(
            id = o.s("stream_id"), title = o.s("name"), logo = o.s("stream_icon"), categoryId = o.s("category_id"),
            kind = kind, number = o.i("num"), epgId = o.s("epg_channel_id"),
            catchupDays = if (o.s("tv_archive") == "1") o.i("tv_archive_duration", 1).coerceAtLeast(1) else 0,
        )
        ContentKind.VOD -> MediaEntry(
            id = o.s("stream_id"), title = o.s("name"), logo = o.s("stream_icon"), categoryId = o.s("category_id"),
            kind = kind, extension = o.s("container_extension").ifBlank { "mp4" }, rating = o.s("rating"),
            plot = o.s("plot"), year = o.s("year"),
        )
        ContentKind.SERIES -> MediaEntry(
            id = o.s("series_id"), title = o.s("name"), logo = o.s("cover"), categoryId = o.s("category_id"),
            kind = kind, plot = o.s("plot"), rating = o.s("rating"), year = o.s("releaseDate").take(4),
        )
    }

    override suspend fun seasons(series: MediaEntry): List<Season> = withContext(Dispatchers.IO) {
        val js = api("get_series_info", mapOf("series_id" to series.id)).obj() ?: return@withContext emptyList()
        val eps = js.get("episodes")
        val groups: List<Pair<String, List<JsonObject>>> = when {
            eps.obj() != null -> eps.obj()!!.entrySet().map { (k, v) -> k to (v.arr()?.mapNotNull { it.obj() } ?: emptyList()) }
            eps.arr() != null -> eps.arr()!!.mapIndexed { i, v ->
                val list = v.arr()?.mapNotNull { it.obj() } ?: listOfNotNull(v.obj())
                (list.firstOrNull().s("season").ifBlank { "${i + 1}" }) to list
            }
            else -> emptyList()
        }
        groups.map { (key, list) ->
            val n = key.toIntOrNull() ?: 0
            Season(key, L.t("الموسم $key", "Season $key"), n, list.mapIndexed { i, e ->
                val info = e.get("info").obj()
                Episode(
                    id = e.s("id"), title = e.s("title").ifBlank { L.t("الحلقة ${i + 1}", "Episode ${i + 1}") },
                    number = e.i("episode_num", i + 1), extension = e.s("container_extension").ifBlank { "mp4" },
                    plot = info.s("plot"), image = info.s("movie_image"),
                )
            })
        }.sortedBy { it.number }
    }

    override suspend fun resolve(entry: MediaEntry): PlayRequest {
        val url = when (entry.kind) {
            ContentKind.LIVE -> "$base/live/$user/$pass/${entry.id}.${if (profile.outputFormat == "m3u8") "m3u8" else "ts"}"
            ContentKind.VOD -> "$base/movie/$user/$pass/${entry.id}.${entry.extension.ifBlank { "mp4" }}"
            ContentKind.SERIES -> "$base/series/$user/$pass/${entry.id}.${entry.extension.ifBlank { "mp4" }}"
        }
        return PlayRequest(url, userAgent = playerUa, title = entry.title, isLive = entry.kind == ContentKind.LIVE)
    }

    override suspend fun resolveEpisode(series: MediaEntry, season: Season, episode: Episode): PlayRequest =
        PlayRequest(
            "$base/series/$user/$pass/${episode.id}.${episode.extension.ifBlank { "mp4" }}",
            userAgent = playerUa, title = "${series.title} - S${season.number}E${episode.number} ${episode.title}",
        )

    override suspend fun epg(entry: MediaEntry): List<EpgProgram> = withContext(Dispatchers.IO) {
        val list = runCatching {
            api("get_short_epg", mapOf("stream_id" to entry.id, "limit" to "30")).obj()?.get("epg_listings").arr()
                ?.mapNotNull { el ->
                    val o = el.obj() ?: return@mapNotNull null
                    EpgProgram(o.s("id"), b64(o.s("title")), b64(o.s("description")),
                        o.l("start_timestamp") * 1000, o.l("stop_timestamp") * 1000)
                }
        }.getOrNull().orEmpty().filter { it.end > it.start }
        if (list.isNotEmpty()) list
        else {
            val url = profile.epgUrl.ifBlank { "$base/xmltv.php?username=$user&password=$pass" }
            runCatching { Xmltv.programs(url, playerUa, entry.epgId, entry.title) }.getOrDefault(emptyList())
        }
    }

    override suspend fun catchup(entry: MediaEntry, program: EpgProgram): PlayRequest? {
        if (entry.catchupDays <= 0 || !program.isPast && !program.isNow) return null
        val minutes = ((program.end - program.start) / 60000).coerceAtLeast(1)
        val fmt = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).apply { timeZone = TimeZone.getTimeZone(serverTz) }
        val url = "$base/timeshift/$user/$pass/$minutes/${fmt.format(Date(program.start))}/${entry.id}.ts"
        return PlayRequest(url, userAgent = playerUa, title = "${entry.title} - ${program.title}")
    }

    private fun b64(s: String): String =
        runCatching { String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8) }.getOrDefault(s).ifBlank { s }
}
