package com.newlife.tv.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.newlife.tv.data.AccountInfo
import com.newlife.tv.data.Category
import com.newlife.tv.data.ContentKind
import com.newlife.tv.data.EpgProgram
import com.newlife.tv.data.Episode
import com.newlife.tv.data.L
import com.newlife.tv.data.MediaEntry
import com.newlife.tv.data.PlayRequest
import com.newlife.tv.data.Profile
import com.newlife.tv.data.ProfileStore
import com.newlife.tv.data.ScheduledRecording
import com.newlife.tv.data.Season
import com.newlife.tv.net.IptvProvider
import com.newlife.tv.net.ProviderFactory
import com.newlife.tv.net.Session
import com.newlife.tv.net.SpeedTest
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.newlife.tv.net.Xmltv
import com.newlife.tv.player.ExternalPlayer
import com.newlife.tv.player.PlayerActivity
import com.newlife.tv.record.RecordingScheduler
import com.newlife.tv.record.RecordingService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Profiles : Screen
    data class Edit(val profile: Profile?) : Screen
    data object Home : Screen
    data class SeriesDetail(val entry: MediaEntry) : Screen
    data object Settings : Screen
}

enum class Section(val kind: ContentKind?) {
    LIVE(ContentKind.LIVE), VOD(ContentKind.VOD), SERIES(ContentKind.SERIES), GUIDE(ContentKind.LIVE),
    FAVORITES(null), SEARCH(null), RECORDINGS(null), SPEED(null), ACCOUNT(null), SETTINGS(null)
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    // ---------------- navigation ----------------
    val stack = mutableStateListOf<Screen>(Screen.Profiles)
    val screen: Screen get() = stack.last()
    fun push(s: Screen) { stack.add(s) }
    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    // ---------------- connection ----------------
    var connecting by mutableStateOf(false); private set
    var connectingName by mutableStateOf(""); private set
    var error by mutableStateOf<String?>(null)
    var account by mutableStateOf<AccountInfo?>(null); private set
    var activeProfile by mutableStateOf<Profile?>(null); private set
    var toast by mutableStateOf<String?>(null)
    private var connectJob: Job? = null

    // ---------------- content ----------------
    var section by mutableStateOf(Section.LIVE); private set
    val categories = mutableStateMapOf<ContentKind, List<Category>>()
    val selectedCat = mutableStateMapOf<ContentKind, String>()
    var entries by mutableStateOf<List<MediaEntry>>(emptyList()); private set
    var entriesKind by mutableStateOf<ContentKind?>(null); private set
    var loading by mutableStateOf(false); private set
    var focused by mutableStateOf<MediaEntry?>(null)
    var guideChannel by mutableStateOf<MediaEntry?>(null)
    var vodDialog by mutableStateOf<MediaEntry?>(null)
    val epg = mutableStateMapOf<String, List<EpgProgram>>()
    var seasons by mutableStateOf<List<Season>>(emptyList()); private set
    var seasonsLoading by mutableStateOf(false); private set
    var favKeys by mutableStateOf<Set<String>>(emptySet()); private set
    var searchQuery by mutableStateOf("")
    var searchResults by mutableStateOf<List<MediaEntry>>(emptyList()); private set
    var searching by mutableStateOf(false); private set

    private val cache = HashMap<String, List<MediaEntry>>()
    private var loadJob: Job? = null
    private var epgJob: Job? = null
    private val provider: IptvProvider? get() = Session.provider
    private val ctx: Context get() = getApplication()

    // =============================================================== profiles
    fun connect(profile: Profile) {
        connectJob?.cancel()
        error = null
        connecting = true
        connectingName = profile.displayName()
        connectJob = viewModelScope.launch {
            try {
                val p = ProviderFactory.create(ctx, profile)
                val info = p.connect()
                Session.provider = p
                activeProfile = profile
                account = info
                ProfileStore.touch(profile.id)
                resetContent()
                favKeys = ProfileStore.favoriteKeys(profile.id)
                stack.clear(); stack.add(Screen.Profiles); stack.add(Screen.Home)
                selectSection(Section.LIVE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = (e.message ?: e.javaClass.simpleName)
            } finally {
                connecting = false
            }
        }
    }

    fun cancelConnect() {
        connectJob?.cancel()
        connecting = false
        error = L.t("تم إلغاء الاتصال", "Connection cancelled")
    }

    fun logout() {
        Session.provider = null
        activeProfile = null
        account = null
        resetContent()
        stack.clear(); stack.add(Screen.Profiles)
    }

    fun refresh() {
        val p = activeProfile ?: return
        Xmltv.clear()
        val keep = section
        connectJob?.cancel()
        connecting = true
        connectJob = viewModelScope.launch {
            try {
                val prov = ProviderFactory.create(ctx, p)
                account = prov.connect()
                Session.provider = prov
                resetContent()
                selectSection(keep)
                toast = L.t("تم التحديث", "Refreshed")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast = L.t("فشل التحديث: ", "Refresh failed: ") + e.message
            } finally {
                connecting = false
            }
        }
    }

    fun testConnection() {
        val p = activeProfile ?: return
        viewModelScope.launch {
            val t0 = System.currentTimeMillis()
            toast = try {
                val info = ProviderFactory.create(ctx, p).connect()
                account = info
                L.t("الاتصال ناجح", "Connection OK") + " (${System.currentTimeMillis() - t0} ms)"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.t("فشل الاتصال: ", "Connection failed: ") + e.message
            }
        }
    }

    private fun resetContent() {
        cache.clear(); categories.clear(); selectedCat.clear(); epg.clear()
        entries = emptyList(); entriesKind = null; focused = null; guideChannel = null
        searchResults = emptyList()
    }

    // ================================================================ content
    fun selectSection(s: Section) {
        section = s
        val kind = s.kind ?: return
        viewModelScope.launch {
            if (categories[kind] == null) {
                loading = true
                val cats = runCatching { provider?.categories(kind) }.getOrNull().orEmpty()
                categories[kind] = cats
                loading = false
            }
            val cat = selectedCat[kind] ?: categories[kind]?.firstOrNull()?.id ?: IptvProvider.ALL
            selectCategory(kind, cat)
        }
    }

    fun selectCategory(kind: ContentKind, id: String) {
        selectedCat[kind] = id
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val key = "$kind|$id"
            cache[key]?.let { entries = it; entriesKind = kind; return@launch }
            loading = true
            try {
                val list = provider?.entries(kind, id).orEmpty()
                cache[key] = list
                entries = list
                entriesKind = kind
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                entries = emptyList(); entriesKind = kind
                toast = L.t("تعذر تحميل القائمة: ", "Failed to load list: ") + e.message
            } finally {
                loading = false
            }
        }
    }

    fun focus(e: MediaEntry) {
        focused = e
        if (e.kind == ContentKind.LIVE) loadEpg(e, debounce = true)
    }

    fun loadEpg(e: MediaEntry, debounce: Boolean = false, force: Boolean = false) {
        if (!force && epg.containsKey(e.id)) return
        val p = provider ?: return
        if (debounce) epgJob?.cancel()
        val job = viewModelScope.launch {
            if (debounce) delay(300)
            val list = runCatching { p.epg(e) }.getOrDefault(emptyList())
            epg[e.id] = list
        }
        if (debounce) epgJob = job
    }

    fun loadSeasons(e: MediaEntry) {
        seasons = emptyList()
        seasonsLoading = true
        viewModelScope.launch {
            seasons = runCatching { provider?.seasons(e) }.getOrNull().orEmpty()
            seasonsLoading = false
            if (seasons.isEmpty()) toast = L.t("لا توجد حلقات", "No episodes found")
        }
    }

    fun openSeries(e: MediaEntry) {
        loadSeasons(e)
        push(Screen.SeriesDetail(e))
    }

    // ================================================================ favourites
    fun isFav(e: MediaEntry) = favKeys.contains(ProfileStore.keyOf(e))

    fun toggleFav(e: MediaEntry) {
        val pid = activeProfile?.id ?: return
        val added = ProfileStore.toggleFavorite(pid, e)
        favKeys = ProfileStore.favoriteKeys(pid)
        toast = if (added) L.t("أضيفت إلى المفضلة", "Added to favorites") else L.t("أزيلت من المفضلة", "Removed from favorites")
    }

    fun favorites(): List<MediaEntry> {
        val pid = activeProfile?.id ?: return emptyList()
        return ProfileStore.favorites.value.filter { it.profileId == pid }.map { it.entry }
    }

    // ================================================================ search
    fun search() {
        val q = searchQuery.trim()
        if (q.length < 2) { toast = L.t("اكتب حرفين على الأقل", "Type at least 2 characters"); return }
        val p = provider ?: return
        searching = true
        viewModelScope.launch {
            val out = ArrayList<MediaEntry>()
            for (kind in ContentKind.entries) {
                val key = "$kind|${IptvProvider.ALL}"
                val all = cache[key] ?: runCatching { p.entries(kind, IptvProvider.ALL) }.getOrDefault(emptyList()).also { cache[key] = it }
                out += all.filter { it.title.contains(q, ignoreCase = true) }.take(300)
            }
            searchResults = out
            searching = false
            if (out.isEmpty()) toast = L.t("لا توجد نتائج", "No results")
        }
    }

    // ================================================================ playback
    private fun launchPlayer(context: Context, req: PlayRequest, entry: MediaEntry?, list: List<MediaEntry>) {
        val st = ProfileStore.settings.value
        if (st.playerEngine == "external" || st.externalPlayer) {
            ExternalPlayer.open(context, req); return
        }
        Session.zapList = list
        PlayerActivity.start(context, req, entry, if (entry == null) -1 else list.indexOf(entry))
    }

    fun play(context: Context, e: MediaEntry, list: List<MediaEntry> = listOf(e)) {
        val p = provider ?: return
        viewModelScope.launch {
            try {
                val req = p.resolve(e)
                launchPlayer(context, req, e, if (e.kind == ContentKind.LIVE) list else emptyList())
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                toast = L.t("تعذر التشغيل: ", "Cannot play: ") + ex.message
            }
        }
    }

    fun playEpisode(context: Context, series: MediaEntry, season: Season, ep: Episode) {
        val p = provider ?: return
        viewModelScope.launch {
            try {
                val req = p.resolveEpisode(series, season, ep)
                launchPlayer(context, req, null, emptyList())
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                toast = L.t("تعذر التشغيل: ", "Cannot play: ") + ex.message
            }
        }
    }

    fun playCatchup(context: Context, e: MediaEntry, program: EpgProgram) {
        val p = provider ?: return
        viewModelScope.launch {
            val req = runCatching { p.catchup(e, program) }.getOrNull()
            if (req == null) toast = L.t("الإعادة غير متاحة لهذا البرنامج", "Catch-up not available for this program")
            else launchPlayer(context, req, null, emptyList())
        }
    }

    // ================================================================ recording
    fun recordNow(e: MediaEntry, minutes: Int) {
        val p = provider ?: return
        viewModelScope.launch {
            try {
                val req = p.resolve(e)
                RecordingService.startNow(ctx, req, e.title, minutes)
                toast = L.t("بدأ تسجيل ${e.title}", "Recording ${e.title}")
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                toast = L.t("تعذر بدء التسجيل: ", "Cannot record: ") + ex.message
            }
        }
    }

    fun scheduleRecording(e: MediaEntry, program: EpgProgram) {
        val pid = activeProfile?.id ?: return
        val s = ScheduledRecording(
            profileId = pid, entry = e, title = "${e.title} - ${program.title}",
            startMs = program.start - 60_000, endMs = program.end + 120_000,
        )
        ProfileStore.addSchedule(s)
        RecordingScheduler.schedule(ctx, s)
        toast = L.t("تمت جدولة التسجيل: ", "Recording scheduled: ") + "${L.hm(program.start)} ${program.title}"
    }

    fun cancelSchedule(s: ScheduledRecording) {
        RecordingScheduler.cancel(ctx, s.id)
        RecordingService.stop(ctx, s.id)
        ProfileStore.removeSchedule(s.id)
    }

    // ================================================================ speed test
    var netPing by mutableStateOf<Long?>(null); private set
    var netMbps by mutableStateOf<Double?>(null); private set
    var srvPing by mutableStateOf<Long?>(null); private set
    var srvMbps by mutableStateOf<Double?>(null); private set
    var srvChannel by mutableStateOf(""); private set
    var speedLive by mutableStateOf(0.0); private set
    var speedRunning by mutableStateOf<String?>(null); private set
    var speedError by mutableStateOf<String?>(null); private set
    private var speedJob: Job? = null

    fun stopSpeedTest() { speedJob?.cancel(); speedRunning = null }

    fun testInternet() {
        speedJob?.cancel()
        speedError = null; netPing = null; netMbps = null; speedLive = 0.0
        speedRunning = "net"
        speedJob = viewModelScope.launch {
            try {
                netPing = SpeedTest.internetLatency()
                netMbps = SpeedTest.internetDownload(10_000) { speedLive = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                speedError = L.t("فشل اختبار الإنترنت: ", "Internet test failed: ") + e.message
            } finally {
                if (speedRunning == "net") speedRunning = null
            }
        }
    }

    fun testServer() {
        val p = provider ?: run { toast = L.t("سجّل الدخول إلى اشتراك أولاً", "Log in to a subscription first"); return }
        speedJob?.cancel()
        speedError = null; srvPing = null; srvMbps = null; speedLive = 0.0
        speedRunning = "srv"
        speedJob = viewModelScope.launch {
            try {
                val url = p.profile.portalUrl.trim().let { if (it.startsWith("http")) it else "http://$it" }
                url.toHttpUrlOrNull()?.let { h ->
                    srvPing = SpeedTest.latency("${h.scheme}://${h.host}:${h.port}/", mapOf("User-Agent" to p.playerUa))
                }
                val channel = focused?.takeIf { it.kind == ContentKind.LIVE }
                    ?: entries.firstOrNull { it.kind == ContentKind.LIVE }
                    ?: runCatching { p.entries(ContentKind.LIVE, IptvProvider.ALL).firstOrNull() }.getOrNull()
                if (channel == null) { speedError = L.t("لا توجد قناة لاختبارها", "No channel to test"); return@launch }
                srvChannel = channel.title
                val req = p.resolve(channel)
                srvMbps = SpeedTest.streamSpeed(req, 10_000) { speedLive = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                speedError = L.t("فشل اختبار السيرفر: ", "Server test failed: ") + e.message
            } finally {
                if (speedRunning == "srv") speedRunning = null
            }
        }
    }
}
