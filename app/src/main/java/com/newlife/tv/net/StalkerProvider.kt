package com.newlife.tv.net

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.newlife.tv.data.AccountInfo
import com.newlife.tv.data.Category
import com.newlife.tv.data.ContentKind
import com.newlife.tv.data.DeviceIdentity
import com.newlife.tv.data.EpgProgram
import com.newlife.tv.data.Episode
import com.newlife.tv.data.L
import com.newlife.tv.data.MediaEntry
import com.newlife.tv.data.PlayRequest
import com.newlife.tv.data.Profile
import com.newlife.tv.data.Season
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.URLEncoder
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * Stalker / Ministra (MAG) middleware client. Sends the MAC, serial number,
 * device IDs, signature and user-agent configured in the profile.
 */
class StalkerProvider(override val profile: Profile) : IptvProvider {

    private val mac = DeviceIdentity.normalizeMac(profile.mac)
    private val serial = profile.serial.ifBlank { DeviceIdentity.serialFromMac(mac) }
    private val deviceId1 = profile.deviceId1.ifBlank { DeviceIdentity.deviceIdFromMac(mac) }
    private val deviceId2 = profile.deviceId2.ifBlank { deviceId1 }
    private val signature = profile.signature.ifBlank { DeviceIdentity.signature(mac, serial, deviceId1) }
    private val model = profile.stbModel.ifBlank { "MAG250" }
    private val tz = profile.timezone.ifBlank { TimeZone.getDefault().id }

    private val serverCookies = ConcurrentHashMap<String, Cookie>()
    private val client: OkHttpClient = Http.client.newBuilder().cookieJar(object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { if (it.name !in BASE_COOKIES) serverCookies[it.name] = it }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val base = listOf(
                "mac" to enc(mac),
                "stb_lang" to profile.language.ifBlank { "en" },
                "timezone" to enc(tz),
            ).map { (n, v) -> Cookie.Builder().name(n).value(v).hostOnlyDomain(url.host).path("/").build() }
            return base + serverCookies.values.filter { it.matches(url) }
        }
    }).build()

    @Volatile private var loadUrl = ""
    @Volatile private var token = ""
    @Volatile private var random = ""
    private var root = ""
    private var referer = ""
    private var seriesType = "series"
    private var allChannels: List<MediaEntry>? = null
    private val listCache = ConcurrentHashMap<String, List<MediaEntry>>()

    // ------------------------------------------------------------------ http
    private fun headers(): Map<String, String> = buildMap {
        put("User-Agent", profile.userAgent.ifBlank { DeviceIdentity.DEFAULT_STB_UA })
        put("X-User-Agent", "Model: $model; Link: Ethernet")
        put("Accept", "*/*")
        put("Pragma", "no-cache")
        if (referer.isNotEmpty()) put("Referer", referer)
        if (token.isNotEmpty()) put("Authorization", "Bearer $token")
    }

    private fun rawGet(url: String, params: Map<String, String>): String {
        val b = url.toHttpUrl().newBuilder()
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        b.addQueryParameter("JsHttpRequest", "1-xml")
        return Http.getString(b.build().toString(), headers(), client)
    }

    private fun call(params: Map<String, String>, retry: Boolean = true): JsonElement? {
        if (loadUrl.isEmpty()) authenticate()
        val body = rawGet(loadUrl, params)
        if (retry && (body.contains("Authorization failed", true) || body.isBlank())) {
            authenticate()
            return call(params, false)
        }
        return parseJson(body).obj()?.get("js")
    }

    // ------------------------------------------------------------------ auth
    private fun candidates(): List<String> {
        var u = profile.portalUrl.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        val h = u.toHttpUrlOrNull() ?: throw IOException(L.t("رابط البورتال غير صحيح", "Invalid portal URL"))
        root = "${h.scheme}://${h.host}:${h.port}"
        val path = h.encodedPath.trimEnd('/')
        referer = if (path.endsWith(".php")) u else "$root$path/"
        val list = mutableListOf<String>()
        if (path.endsWith(".php")) list += root + path
        val parent = path.removeSuffix("/c")
        if (parent.isNotEmpty() && !parent.endsWith(".php")) {
            list += "$root$parent/server/load.php"
            list += "$root$parent/portal.php"
        }
        list += "$root/portal.php"
        list += "$root/stalker_portal/server/load.php"
        list += "$root/server/load.php"
        list += "$root/c/portal.php"
        return list.distinct()
    }

    private fun handshake(url: String): Boolean {
        token = ""
        val first = parseJson(rawGet(url, mapOf("type" to "stb", "action" to "handshake", "token" to "", "prehash" to "0")))
            .obj()?.get("js").obj()
        if (first != null && first.s("token").isNotEmpty()) {
            token = first.s("token"); random = first.s("random"); return true
        }
        // Some portals want the client to propose a token.
        val own = DeviceIdentity.randomHex(32)
        token = own
        val second = parseJson(rawGet(url, mapOf("type" to "stb", "action" to "handshake", "token" to own, "prehash" to "0")))
            .obj()?.get("js").obj() ?: run { token = ""; return false }
        token = second.s("token").ifBlank { own }
        random = second.s("random")
        return true
    }

    private fun discover() {
        var last: Exception? = null
        for (u in candidates()) {
            try {
                if (handshake(u)) { loadUrl = u; return }
            } catch (e: Exception) { last = e }
        }
        throw IOException(L.t("فشل الاتصال بالبورتال", "Portal handshake failed") + (last?.message?.let { ": $it" } ?: ""))
    }

    private fun getProfile(): JsonObject? {
        val metrics = JsonObject().apply {
            addProperty("mac", mac); addProperty("sn", serial); addProperty("type", "STB")
            addProperty("model", model); addProperty("uid", deviceId1); addProperty("random", random)
        }.toString()
        val p = linkedMapOf(
            "type" to "stb", "action" to "get_profile", "hd" to "1",
            "ver" to "ImageDescription: 0.2.18-r23-250; ImageDate: Thu Sep 13 11:31:16 EEST 2018; PORTAL version: 5.6.2; API Version: JS API version: 343; STB API version: 146; Player Engine version: 0x58c",
            "num_banks" to "2", "sn" to serial, "stb_type" to model, "client_type" to "STB",
            "image_version" to "218", "video_out" to "hdmi", "device_id" to deviceId1, "device_id2" to deviceId2,
            "signature" to signature, "auth_second_step" to "1", "hw_version" to "1.7-BD-00",
            "not_valid_token" to "0", "metrics" to metrics, "hw_version_2" to DeviceIdentity.sha1(mac),
            "timestamp" to (System.currentTimeMillis() / 1000).toString(), "api_signature" to "262", "prehash" to "",
        )
        return parseJson(rawGet(loadUrl, p)).obj()?.get("js").obj()
    }

    @Synchronized
    private fun authenticate(): JsonObject? {
        if (loadUrl.isEmpty() || !runCatching { handshake(loadUrl) }.getOrDefault(false)) discover()
        var prof = getProfile()
        if (profile.username.isNotBlank()) {
            rawGet(loadUrl, linkedMapOf(
                "type" to "stb", "action" to "do_auth", "login" to profile.username, "password" to profile.password,
                "device_id" to deviceId1, "device_id2" to deviceId2,
            ))
            prof = getProfile() ?: prof
        }
        return prof
    }

    override suspend fun connect(): AccountInfo = withContext(Dispatchers.IO) {
        if (!DeviceIdentity.isValidMac(mac)) throw IOException(L.t("عنوان MAC غير صالح", "Invalid MAC address"))
        loadUrl = ""
        val prof = authenticate() ?: throw IOException(L.t("لم يرد البورتال على طلب الملف الشخصي", "Portal returned no profile"))
        if (prof.s("blocked") == "1") throw IOException(L.t("هذا الجهاز محظور من البورتال", "This device is blocked by the portal"))
        val main = runCatching { call(mapOf("type" to "account_info", "action" to "get_main_info")).obj() }.getOrNull()
        val expiry = listOf(main.s("end_date"), main.s("phone"), prof.s("expire_billing_date"))
            .firstOrNull { it.isNotBlank() && it != "0000-00-00 00:00:00" } ?: L.t("غير محدد", "N/A")
        val fields = mutableListOf(
            L.t("النوع", "Type") to "Stalker / MAG",
            L.t("الخادم", "Server") to loadUrl,
            "MAC" to mac,
            L.t("الطراز", "Model") to model,
            L.t("الحالة", "Status") to if (prof.s("status") == "0" || prof.s("status").isBlank()) L.t("نشط", "Active") else prof.s("status"),
            L.t("تاريخ الانتهاء", "Expiry") to expiry,
        )
        listOf(
            L.t("الباقة", "Tariff") to (main.s("tariff_plan").ifBlank { prof.s("tariff_plan") }),
            L.t("الرصيد", "Balance") to main.s("account_balance"),
            L.t("اسم الدخول", "Login") to prof.s("login"),
            L.t("الاسم", "Name") to main.s("fname"),
        ).filter { it.second.isNotBlank() }.forEach { fields += it }
        AccountInfo(fields)
    }

    // ------------------------------------------------------------- categories
    override suspend fun categories(kind: ContentKind): List<Category> = withContext(Dispatchers.IO) {
        val list = when (kind) {
            ContentKind.LIVE -> genres("itv", "get_genres", kind)
            ContentKind.VOD -> genres("vod", "get_categories", kind)
            ContentKind.SERIES -> {
                val s = runCatching { genres("series", "get_categories", kind) }.getOrDefault(emptyList())
                if (s.any { it.id != IptvProvider.ALL }) { seriesType = "series"; s }
                else { seriesType = "vod"; genres("vod", "get_categories", kind) }
            }
        }
        val all = Category(IptvProvider.ALL, L.t("الكل", "All"), kind)
        listOf(all) + list.filter { it.id != IptvProvider.ALL }
    }

    private fun genres(type: String, action: String, kind: ContentKind): List<Category> =
        call(mapOf("type" to type, "action" to action)).arr()?.mapNotNull { el ->
            val o = el.obj() ?: return@mapNotNull null
            Category(o.s("id"), o.s("title").ifBlank { o.s("name") }, kind)
        } ?: emptyList()

    // ---------------------------------------------------------------- entries
    override suspend fun entries(kind: ContentKind, categoryId: String): List<MediaEntry> = withContext(Dispatchers.IO) {
        when (kind) {
            ContentKind.LIVE -> {
                val all = allChannels ?: loadAllChannels().also { allChannels = it }
                if (categoryId == IptvProvider.ALL) all else {
                    val f = all.filter { it.categoryId == categoryId }
                    f.ifEmpty { paged("itv", mapOf("genre" to categoryId, "fav" to "0", "sortby" to "number"), kind) }
                }
            }
            ContentKind.VOD -> listCache.getOrPut("vod|$categoryId") {
                paged("vod", mapOf("category" to categoryId, "genre" to "*", "sortby" to "added"), kind)
            }
            ContentKind.SERIES -> listCache.getOrPut("series|$categoryId") {
                paged(seriesType, mapOf("category" to categoryId, "genre" to "*", "sortby" to "added"), kind)
            }
        }
    }

    private fun loadAllChannels(): List<MediaEntry> {
        val data = call(mapOf("type" to "itv", "action" to "get_all_channels")).obj()?.get("data").arr()
        val list = data?.mapNotNull { mapEntry(it.obj() ?: return@mapNotNull null, ContentKind.LIVE) } ?: emptyList()
        return list.ifEmpty { paged("itv", mapOf("genre" to "*", "fav" to "0", "sortby" to "number"), ContentKind.LIVE) }
    }

    private fun paged(type: String, extra: Map<String, String>, kind: ContentKind): List<MediaEntry> {
        val out = ArrayList<MediaEntry>()
        var page = 1
        var pages = 1
        while (page <= pages && page <= MAX_PAGES) {
            val js = call(mapOf("type" to type, "action" to "get_ordered_list", "p" to page.toString()) + extra).obj() ?: break
            val data = js.get("data").arr() ?: break
            data.forEach { el -> el.obj()?.let { mapEntry(it, kind)?.let(out::add) } }
            val total = js.i("total_items")
            val per = js.i("max_page_items", data.size().coerceAtLeast(1))
            pages = if (per > 0) (total + per - 1) / per else 1
            if (data.size() == 0) break
            page++
        }
        return out
    }

    private fun mapEntry(o: JsonObject, kind: ContentKind): MediaEntry? {
        val id = o.s("id").ifBlank { return null }
        return if (kind == ContentKind.LIVE) {
            val archive = o.s("tv_archive") == "1" || o.s("enable_tv_archive") == "1"
            MediaEntry(
                id = id, title = o.s("name"), logo = absLogo(o.s("logo")), categoryId = o.s("tv_genre_id"),
                kind = kind, number = o.i("number"), cmd = o.s("cmd"), epgId = o.s("xmltv_id"),
                catchupDays = if (archive) (o.i("tv_archive_duration", 24) / 24).coerceAtLeast(1) else 0,
            )
        } else {
            val k = if (o.s("is_series") == "1") ContentKind.SERIES else kind
            MediaEntry(
                id = id, title = o.s("name"), logo = absLogo(o.s("screenshot_uri").ifBlank { o.s("cover_big") }),
                categoryId = o.s("category_id"), kind = k, cmd = o.s("cmd"), plot = o.s("description"),
                rating = o.s("rating_imdb").ifBlank { o.s("rating_kinopoisk") }, year = o.s("year"),
            )
        }
    }

    private fun absLogo(logo: String): String = when {
        logo.isBlank() -> ""
        logo.startsWith("http") -> logo
        logo.startsWith("/") -> root + logo
        else -> "$root/stalker_portal/misc/logos/320/$logo"
    }

    // ---------------------------------------------------------------- series
    override suspend fun seasons(series: MediaEntry): List<Season> = withContext(Dispatchers.IO) {
        val data = call(mapOf(
            "type" to seriesType, "action" to "get_ordered_list", "movie_id" to series.id,
            "season_id" to "0", "episode_id" to "0", "p" to "1",
        )).obj()?.get("data").arr() ?: return@withContext emptyList()
        data.mapIndexedNotNull { idx, el ->
            val o = el.obj() ?: return@mapIndexedNotNull null
            val numbers = o.get("series").arr()?.mapNotNull { runCatching { it.asInt }.getOrNull() } ?: emptyList()
            val seasonNo = idx + 1
            val episodes = if (numbers.isNotEmpty()) {
                numbers.map { n -> Episode("$n", L.t("الحلقة $n", "Episode $n"), n, cmd = o.s("cmd")) }
            } else {
                val eps = call(mapOf(
                    "type" to seriesType, "action" to "get_ordered_list", "movie_id" to series.id,
                    "season_id" to o.s("id"), "episode_id" to "0", "p" to "1",
                )).obj()?.get("data").arr()
                eps?.mapIndexedNotNull { i, e ->
                    val eo = e.obj() ?: return@mapIndexedNotNull null
                    Episode(eo.s("id"), eo.s("name"), eo.i("series_number", i + 1), cmd = eo.s("cmd"), plot = eo.s("description"))
                } ?: emptyList()
            }
            Season(o.s("id"), o.s("name").ifBlank { L.t("الموسم $seasonNo", "Season $seasonNo") }, seasonNo, episodes, o.s("cmd"))
        }
    }

    // ------------------------------------------------------------------ links
    private fun createLink(type: String, cmd: String, series: String = ""): String {
        val js = call(linkedMapOf(
            "type" to type, "action" to "create_link", "cmd" to cmd, "series" to series,
            "forced_storage" to "undefined", "disable_ad" to "0", "download" to "0", "force_ch_link_check" to "0",
        )).obj()
        return clean(js.s("cmd").ifBlank { cmd })
    }

    private fun clean(cmd: String): String =
        cmd.trim().replace(Regex("^(ffmpeg|ffrt\\d*|auto|rtsp_ffrt|ffrt)\\s+", RegexOption.IGNORE_CASE), "").trim()

    private fun req(url: String, title: String, live: Boolean) = PlayRequest(
        url = url,
        headers = mapOf("Cookie" to "mac=${enc(mac)}; stb_lang=${profile.language.ifBlank { "en" }}; timezone=${enc(tz)}"),
        userAgent = playerUa, title = title, isLive = live,
    )

    /** Some portals return "...&stream=&..." (empty id); fill it from the original command. */
    private fun fixStream(url: String, original: String, id: String): String {
        if (!Regex("[?&]stream=(&|$)").containsMatchIn(url)) return url
        val sid = Regex("/ch/(\\d+)").find(original)?.groupValues?.get(1)
            ?: Regex("stream=(\\d+)").find(original)?.groupValues?.get(1) ?: id
        return url.replace(Regex("([?&]stream=)(?=&|$)"), "\$1$sid")
    }

    override suspend fun resolve(entry: MediaEntry): PlayRequest = withContext(Dispatchers.IO) {
        when (entry.kind) {
            ContentKind.LIVE -> req(fixStream(createLink("itv", entry.cmd), entry.cmd, entry.id), entry.title, true)
            else -> req(createLink("vod", entry.cmd), entry.title, false)
        }
    }

    override suspend fun resolveEpisode(series: MediaEntry, season: Season, episode: Episode): PlayRequest =
        withContext(Dispatchers.IO) {
            val url = if (episode.cmd == season.cmd && season.cmd.isNotEmpty()) createLink("vod", season.cmd, episode.number.toString())
            else createLink("vod", episode.cmd)
            req(url, "${series.title} - ${season.title} - ${episode.title}", false)
        }

    // -------------------------------------------------------------------- epg
    override suspend fun epg(entry: MediaEntry): List<EpgProgram> = withContext(Dispatchers.IO) {
        val js = call(mapOf("type" to "itv", "action" to "get_short_epg", "ch_id" to entry.id, "size" to "30"))
        val arr = js.arr() ?: js.obj()?.get("data").arr()
        val list = arr?.mapNotNull { el ->
            val o = el.obj() ?: return@mapNotNull null
            EpgProgram(o.s("id"), o.s("name"), o.s("descr"), o.l("start_timestamp") * 1000, o.l("stop_timestamp") * 1000)
        }?.filter { it.end > it.start } ?: emptyList()
        if (list.isEmpty() && profile.epgUrl.isNotBlank()) Xmltv.programs(profile.epgUrl, playerUa, entry.epgId, entry.title)
        else list
    }

    override suspend fun catchup(entry: MediaEntry, program: EpgProgram): PlayRequest? = withContext(Dispatchers.IO) {
        if (entry.catchupDays <= 0 || program.id.isBlank()) return@withContext null
        val url = createLink("tv_archive", "auto /media/${program.id}.mpg")
        req(url, "${entry.title} - ${program.title}", false)
    }

    override suspend fun keepAlive() {
        withContext(Dispatchers.IO) {
            runCatching {
                call(mapOf("type" to "watchdog", "action" to "get_events", "init" to "0", "cur_play_type" to "1", "event_active_id" to "0"))
            }
        }
    }

    companion object {
        private const val MAX_PAGES = 40
        private val BASE_COOKIES = setOf("mac", "stb_lang", "timezone")
        private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    }
}
