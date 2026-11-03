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
import com.newlife.tv.data.Pins
import com.newlife.tv.data.ProfileType
import com.newlife.tv.data.ScheduledRecording
import com.newlife.tv.data.Season
import com.newlife.tv.net.IptvProvider
import com.newlife.tv.net.ProviderFactory
import com.newlife.tv.net.Session
import com.newlife.tv.net.SpeedTest
import com.newlife.tv.net.AppUpdater
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

/** A pending action waiting for the parental PIN (or the admin PIN when [admin]). */
class PinRequest(val title: String, val admin: Boolean = false, val onSuccess: () -> Unit)

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
                Session.uaOverride = null
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
            val cat = selectedCat[kind]?.takeIf { !isCatHidden(kind, it) } ?: visibleCategories(kind).firstOrNull()?.id ?: IptvProvider.ALL
            selectCategory(kind, cat)
        }
    }

    fun selectCategory(kind: ContentKind, id: String) {
        selectedCat[kind] = id
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val key = "$kind|$id"
            cache[key]?.let { setEntries(kind, it); return@launch }
            loading = true
            try {
                val list = provider?.entries(kind, id).orEmpty()
                cache[key] = list
                setEntries(kind, list)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setEntries(kind, emptyList())
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
        val key = needsPin(e)
        if (key != null) { withUnlock(key, L.t("هذا المسلسل مقفل", "This series is locked")) { openSeries(e) }; return }
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
                out += all.filter { it.title.contains(q, ignoreCase = true) && !isCatHidden(kind, it.categoryId) }.take(300)
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
        Session.zapList = list.filter { needsPin(it) == null }
        PlayerActivity.start(context, req, entry, if (entry == null) -1 else list.indexOf(entry))
    }

    fun play(context: Context, e: MediaEntry, list: List<MediaEntry> = listOf(e)) {
        val p = provider ?: return
        val key = needsPin(e)
        if (key != null) { withUnlock(key, L.t("هذه القناة مقفلة", "This item is locked")) { play(context, e, list) }; return }
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

    // ================================================================ app update
    var update by mutableStateOf<AppUpdater.Release?>(null); private set
    var updateDialog by mutableStateOf(false)
    var updateChecking by mutableStateOf(false); private set
    var updateProgress by mutableStateOf<Float?>(null); private set
    private var updateFile: java.io.File? = null

    init { checkUpdate(silent = true) }

    fun checkUpdate(silent: Boolean) {
        if (updateChecking) return
        updateChecking = true
        viewModelScope.launch {
            try {
                val r = AppUpdater.latest()
                val current = AppUpdater.currentVersionCode(ctx)
                if (r != null && r.versionCode > current) {
                    update = r; updateDialog = true
                } else if (!silent) {
                    toast = L.t("لديك أحدث نسخة", "You have the latest version") + " (${AppUpdater.currentVersionName(ctx)})"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!silent) toast = L.t("تعذر التحقق من التحديثات: ", "Update check failed: ") + e.message
            } finally {
                updateChecking = false
            }
        }
    }

    fun downloadAndInstall(context: Context) {
        val r = update ?: return
        if (!AppUpdater.canInstall(context)) {
            toast = L.t("فعّل \"السماح بالتثبيت من هذا المصدر\" ثم اضغط تحديث مرة أخرى",
                "Allow \"install from this source\", then press Update again")
            AppUpdater.openInstallPermission(context)
            return
        }
        updateFile?.takeIf { it.exists() }?.let { AppUpdater.install(context, it); return }
        if (updateProgress != null) return
        updateProgress = 0f
        viewModelScope.launch {
            try {
                val f = AppUpdater.download(ctx, r) { updateProgress = it }
                updateFile = f
                AppUpdater.install(context, f)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast = L.t("فشل تنزيل التحديث: ", "Update download failed: ") + e.message
            } finally {
                updateProgress = null
            }
        }
    }

    // ================================================================ parental control & admin mode
    var parental by mutableStateOf(ProfileStore.parental.value); private set
    var pinRequest by mutableStateOf<PinRequest?>(null)
    var itemMenu by mutableStateOf<MediaEntry?>(null)
    var categoryMenu by mutableStateOf<Pair<ContentKind, Category>?>(null)
    var appLocked by mutableStateOf(ProfileStore.settings.value.appLockEnabled)
    private val unlocked = HashSet<String>()
    private var rawEntries: List<MediaEntry> = emptyList()
    private val pid: String get() = activeProfile?.id ?: ""

    private fun setEntries(kind: ContentKind, list: List<MediaEntry>) {
        rawEntries = list
        entriesKind = kind
        entries = list.filter { !isCatHidden(kind, it.categoryId) }
    }

    private fun refreshParental() {
        parental = ProfileStore.parental.value
        entriesKind?.let { k -> entries = rawEntries.filter { !isCatHidden(k, it.categoryId) } }
    }

    fun catKey(kind: ContentKind, id: String) = ProfileStore.catKey(pid, kind, id)
    fun isCatLocked(kind: ContentKind, id: String) = parental.locked.contains(catKey(kind, id))
    fun isCatHidden(kind: ContentKind, id: String) = id.isNotEmpty() && parental.hidden.contains(catKey(kind, id))
    fun isChLocked(e: MediaEntry) = parental.locked.contains(ProfileStore.chKey(pid, e)) || isCatLocked(e.kind, e.categoryId)
    fun visibleCategories(kind: ContentKind) = categories[kind].orEmpty().filter { !isCatHidden(kind, it.id) }

    /** Key that still needs the PIN before [e] can be opened, or null. */
    private fun needsPin(e: MediaEntry): String? {
        val ck = ProfileStore.chKey(pid, e)
        val gk = catKey(e.kind, e.categoryId)
        return when {
            parental.locked.contains(ck) && ck !in unlocked -> ck
            parental.locked.contains(gk) && gk !in unlocked -> gk
            else -> null
        }
    }

    private fun withUnlock(key: String?, title: String, action: () -> Unit) {
        if (key == null) action() else pinRequest = PinRequest(title) { unlocked += key; action() }
    }

    fun requireParental(title: String, action: () -> Unit) { pinRequest = PinRequest(title, onSuccess = action) }

    /** Returns false when the PIN is wrong. */
    fun submitPin(pin: String): Boolean {
        val r = pinRequest ?: return false
        val ok = if (r.admin) Pins.isAdmin(pin) else ProfileStore.checkParentalPin(pin)
        if (!ok) return false
        pinRequest = null
        r.onSuccess()
        return true
    }

    fun openCategory(kind: ContentKind, id: String) {
        val key = catKey(kind, id)
        withUnlock(if (isCatLocked(kind, id) && key !in unlocked) key else null, L.t("هذه المجموعة مقفلة", "This group is locked")) {
            selectCategory(kind, id)
        }
    }

    fun toggleLockEntry(e: MediaEntry) {
        val k = ProfileStore.chKey(pid, e)
        if (parental.locked.contains(k)) {
            requireParental(L.t("إلغاء قفل: ", "Unlock: ") + e.title) { ProfileStore.setLocked(k, false); refreshParental() }
        } else {
            ProfileStore.setLocked(k, true); unlocked -= k; refreshParental()
            toast = L.t("تم قفل: ", "Locked: ") + e.title
        }
    }

    fun toggleLockCategory(kind: ContentKind, c: Category) {
        val k = catKey(kind, c.id)
        if (parental.locked.contains(k)) {
            requireParental(L.t("إلغاء قفل المجموعة: ", "Unlock group: ") + c.title) { ProfileStore.setLocked(k, false); refreshParental() }
        } else {
            ProfileStore.setLocked(k, true); unlocked -= k; refreshParental()
            toast = L.t("تم قفل المجموعة: ", "Group locked: ") + c.title
        }
    }

    fun hideCategory(kind: ContentKind, c: Category) {
        if (c.id == IptvProvider.ALL) return
        ProfileStore.setHidden(listOf(catKey(kind, c.id)), true)
        refreshParental()
        toast = L.t("تم إخفاء: ", "Hidden: ") + c.title
        if (selectedCat[kind] == c.id) selectCategory(kind, IptvProvider.ALL)
    }

    /** Applies the selection of the "hide groups" dialog for one content type. */
    fun setHiddenCategories(kind: ContentKind, hiddenIds: Set<String>) {
        val all = categories[kind].orEmpty().filter { it.id != IptvProvider.ALL }
        ProfileStore.setHidden(all.filter { it.id !in hiddenIds }.map { catKey(kind, it.id) }, false)
        ProfileStore.setHidden(all.filter { it.id in hiddenIds }.map { catKey(kind, it.id) }, true)
        refreshParental()
        if (selectedCat[kind] in hiddenIds) selectCategory(kind, IptvProvider.ALL)
    }

    fun ensureCategories(kind: ContentKind) {
        if (categories[kind] != null) return
        val p = provider ?: return
        viewModelScope.launch { categories[kind] = runCatching { p.categories(kind) }.getOrNull().orEmpty() }
    }

    fun unlockAllForSession() { unlocked += parental.locked }

    fun clearAllLocks() {
        requireParental(L.t("إلغاء كل الأقفال", "Remove all locks")) { ProfileStore.clearLocks(); refreshParental() }
    }

    /** Returns an error message, or null when the PIN was changed. */
    fun changePin(old: String, new: String, confirm: String): String? {
        if (!ProfileStore.checkParentalPin(old)) return L.t("الرقم السري الحالي غير صحيح", "Current PIN is wrong")
        if (new.length < 4 || !new.all { it.isDigit() }) return L.t("الرقم الجديد يجب أن يكون 4 أرقام على الأقل", "New PIN must be at least 4 digits")
        if (new != confirm) return L.t("التأكيد لا يطابق الرقم الجديد", "Confirmation does not match")
        ProfileStore.updateSettings { it.copy(parentalPin = new) }
        toast = L.t("تم تغيير الرقم السري", "PIN changed")
        return null
    }

    fun unlockApp(pin: String): Boolean = ProfileStore.checkParentalPin(pin).also { if (it) appLocked = false }

    fun setAppLock(enabled: Boolean) {
        requireParental(L.t("تأكيد بالرقم السري", "Confirm with PIN")) {
            ProfileStore.updateSettings { it.copy(appLockEnabled = enabled) }
        }
    }

    /** Admin (service) mode needs the admin PIN in both directions. */
    fun toggleAdminMode() {
        pinRequest = PinRequest(L.t("الرقم السري للإدارة", "Admin PIN"), admin = true) {
            ProfileStore.updateSettings { it.copy(adminHidden = !it.adminHidden) }
            if (ProfileStore.settings.value.adminHidden && section == Section.RECORDINGS) section = Section.LIVE
            toast = if (ProfileStore.settings.value.adminHidden) L.t("تم إخفاء قوائم التعديل والتسجيل", "Edit menus and recordings hidden")
            else L.t("تم إظهار قوائم التعديل والتسجيل", "Edit menus and recordings shown")
        }
    }

    // ================================================================ free packages
    fun addFreePackage(name: String, url: String) {
        if (ProfileStore.profiles.value.any { it.portalUrl == url }) { toast = L.t("هذه الباقة مضافة مسبقاً", "Already added"); return }
        ProfileStore.saveProfile(Profile(type = ProfileType.M3U, nickname = name, portalUrl = url))
        toast = L.t("تمت إضافة: ", "Added: ") + name
    }
}
