package com.newlife.tv.player

import android.app.Activity
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Rational
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import com.google.gson.Gson
import com.newlife.tv.data.ContentKind
import com.newlife.tv.data.DeviceIdentity
import com.newlife.tv.data.L
import com.newlife.tv.data.MediaEntry
import com.newlife.tv.data.PlayRequest
import com.newlife.tv.data.ProfileStore
import com.newlife.tv.net.Http
import com.newlife.tv.net.Session
import com.newlife.tv.net.StreamProbe
import com.newlife.tv.record.RecordingService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Full-screen player with two engines:
 *  - ExoPlayer (fast start, adaptive quality, DRM)
 *  - VLC (plays nearly every IPTV format and audio codec)
 * In "auto" mode it starts with ExoPlayer and switches to VLC by itself if the stream fails.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_REQ = "req"
        private const val EXTRA_ENTRY = "entry"
        private const val EXTRA_INDEX = "index"
        const val ENGINE_EXO = "exo"
        const val ENGINE_VLC = "vlc"
        private const val START_TIMEOUT_MS = 20_000L
        private val gson = Gson()

        fun start(ctx: Context, req: PlayRequest, entry: MediaEntry?, index: Int = -1) {
            val i = Intent(ctx, PlayerActivity::class.java)
                .putExtra(EXTRA_REQ, gson.toJson(req))
                .putExtra(EXTRA_ENTRY, entry?.let { gson.toJson(it) })
                .putExtra(EXTRA_INDEX, index)
            if (ctx !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }
    }

    // ------------------------------------------------------------------ engines
    private interface Engine {
        val name: String
        val view: View
        fun play(r: PlayRequest)
        fun release()
        fun setPlaying(play: Boolean)
        fun isPlaying(): Boolean
        fun seekBy(ms: Long)
        fun position(): Long
        fun duration(): Long
        fun setResize(idx: Int)
        fun videoSize(): Pair<Int, Int>
        fun tracks(kind: Int) // 0 = video, 1 = audio, 2 = subtitles
    }

    private lateinit var container: FrameLayout
    private lateinit var overlay: LinearLayout
    private lateinit var info: TextView
    private lateinit var zapInfo: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var firstButton: Button
    private var playButton: Button? = null
    private var recButton: Button? = null
    private var engineButton: Button? = null

    private var engine: Engine? = null
    private var engineKind = ENGINE_EXO
    private val tried = mutableSetOf<String>()
    private var forceHls = false
    private var uaSearched = false
    private var ready = false
    private var req: PlayRequest? = null
    private var entry: MediaEntry? = null
    private var index = -1
    private var retries = 0
    private var resizeIdx = 0
    private var qualityLabel = ""
    private var nowProgram = ""
    private var recordingId: String? = null
    private var keepAliveJob: Job? = null
    private var startJob: Job? = null
    private var hideJob: Job? = null
    private var tickJob: Job? = null
    private var hideZapJob: Job? = null
    private var errorDialog: AlertDialog? = null

    private val bufferMs get() = (ProfileStore.settings.value.bufferSeconds.coerceIn(2, 120) * 1000).coerceAtLeast(2500)
    private val resizeNames get() = listOf(L.t("ملاءمة", "Fit"), L.t("ملء الشاشة", "Stretch"), L.t("تكبير", "Zoom"), L.t("عرض كامل", "Fit width"))
    private val isLive get() = req?.isLive == true || (entry?.kind == ContentKind.LIVE && req?.url?.startsWith("file") != true)

    // ------------------------------------------------------------------ setup
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProfileStore.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        resizeIdx = ProfileStore.settings.value.resizeMode.coerceIn(0, 3)
        buildUi()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        container = FrameLayout(this)
        root.addView(container, FrameLayout.LayoutParams(-1, -1))

        // transparent layer that toggles the controls on tap (touch screens)
        val touch = View(this).apply {
            isFocusable = false
            setOnClickListener { if (this@PlayerActivity.overlay.visibility == View.VISIBLE) hideOverlay() else showOverlay() }
        }
        root.addView(touch, FrameLayout.LayoutParams(-1, -1))

        spinner = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(spinner, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))

        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC000000.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = View.GONE
        }
        info = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 15f; setTypeface(typeface, Typeface.BOLD); maxLines = 3
        }
        overlay.addView(info)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun add(label: String, action: () -> Unit): Button = Button(this).apply {
            text = label; isAllCaps = false; textSize = 13f
            setOnClickListener { scheduleHide(); action() }
            buttons.addView(this, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        }
        playButton = add("❚❚") { togglePlay() }
        firstButton = playButton!!
        add("-10s") { engine?.seekBy(-10_000) }
        add("+10s") { engine?.seekBy(10_000) }
        engineButton = add("VLC") { switchEngine() }
        add(L.t("خيارات", "Options")) { optionsDialog() }
        add(L.t("الجودة", "Quality")) { engine?.tracks(0) }
        add(L.t("الصوت", "Audio")) { engine?.tracks(1) }
        add(L.t("الترجمة", "Subtitles")) { engine?.tracks(2) }
        add(L.t("أبعاد الصورة", "Aspect")) { cycleResize() }
        add(L.t("صورة في صورة", "PiP")) { enterPip() }
        recButton = add(L.t("تسجيل", "Record")) { recordDialog() }
        add(L.t("مشغل خارجي", "External player")) { req?.let { ExternalPlayer.open(this, it) } }
        add(L.t("المفضلة", "Favorite")) { toggleFav() }
        add(L.t("فحص الرابط", "Check link")) { showErrorDialog(L.t("فحص يدوي للرابط", "Manual link check"), manual = true) }
        overlay.addView(HorizontalScrollView(this).apply { isFillViewport = true; addView(buttons) })
        root.addView(overlay, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        zapInfo = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 22f; setBackgroundColor(0xB3000000.toInt())
            setPadding(dp(20), dp(10), dp(20), dp(10)); visibility = View.GONE
        }
        root.addView(zapInfo, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(60) })
        setContentView(root)
    }

    private fun handleIntent(intent: Intent) {
        val r = intent.getStringExtra(EXTRA_REQ)?.let { gson.fromJson(it, PlayRequest::class.java) } ?: run { finish(); return }
        entry = intent.getStringExtra(EXTRA_ENTRY)?.let { gson.fromJson(it, MediaEntry::class.java) }
        index = intent.getIntExtra(EXTRA_INDEX, -1)
        newSession()
        startPlayback(r, null)
    }

    private fun newSession() {
        retries = 0; forceHls = false; uaSearched = false; tried.clear()
        errorDialog?.dismiss(); errorDialog = null
    }

    // ---------------------------------------------------------------- playback
    private fun preferredEngine(): String =
        if (ProfileStore.settings.value.playerEngine == ENGINE_VLC) ENGINE_VLC else ENGINE_EXO

    private fun startPlayback(r: PlayRequest, kind: String?) {
        req = r
        ready = false
        val k = kind ?: preferredEngine()
        engine?.release()
        engine = null
        container.removeAllViews()
        val e: Engine = try {
            if (k == ENGINE_VLC) VlcEngine() else ExoEngine()
        } catch (t: Throwable) {
            Toast.makeText(this, "${t.javaClass.simpleName}: ${t.message}", Toast.LENGTH_LONG).show()
            if (k == ENGINE_VLC) ExoEngine() else VlcEngine()
        }
        engineKind = if (e is VlcEngine) ENGINE_VLC else ENGINE_EXO
        tried += engineKind
        engine = e
        container.addView(e.view, FrameLayout.LayoutParams(-1, -1))
        engineButton?.text = if (engineKind == ENGINE_VLC) "ExoPlayer" else "VLC"
        spinner.visibility = View.VISIBLE
        qualityLabel = ""
        try {
            e.play(r)
            e.setResize(resizeIdx)
        } catch (t: Throwable) {
            onEngineError("${e.name}: ${t.message ?: t.javaClass.simpleName}", containerIssue = false)
            return
        }
        startJob?.cancel()
        startJob = lifecycleScope.launch {
            delay(START_TIMEOUT_MS)
            if (!ready) onEngineError(L.t("لم تبدأ الصورة خلال 20 ثانية", "No picture after 20 seconds") + " (${e.name})", containerIssue = true)
        }
        nowProgram = ""
        updateInfo()
        loadNowProgram()
        startKeepAlive()
    }

    private fun onReady() {
        if (ready) return
        ready = true
        startJob?.cancel()
        spinner.visibility = View.GONE
        updateInfo()
    }

    private fun onBuffering(buffering: Boolean) {
        spinner.visibility = if (buffering) View.VISIBLE else View.GONE
    }

    private fun onVideoSize(w: Int, h: Int) {
        qualityLabel = if (h > 0) "${w}×$h ${L.quality(h)}" else ""
        updateInfo()
        if (Build.VERSION.SDK_INT >= 26 && inPip()) runCatching { setPictureInPictureParams(pipParams()) }
    }

    /** Failure handling: HLS retry → other engine (auto mode) → fresh link for live → error dialog. */
    private fun onEngineError(msg: String, containerIssue: Boolean) {
        if (isFinishing || isDestroyed) return
        startJob?.cancel()
        val r = req ?: return
        spinner.visibility = View.GONE
        if (engineKind == ENGINE_EXO && containerIssue && !forceHls && !r.url.contains("m3u8", true) && r.url.startsWith("http")) {
            forceHls = true
            startPlayback(r, ENGINE_EXO)
            return
        }
        if (ProfileStore.settings.value.playerEngine == "auto") {
            val other = if (engineKind == ENGINE_EXO) ENGINE_VLC else ENGINE_EXO
            if (other !in tried) {
                Toast.makeText(this, L.t("جارٍ التشغيل بمشغل ", "Trying ") + (if (other == ENGINE_VLC) "VLC" else "ExoPlayer") + "…", Toast.LENGTH_SHORT).show()
                startPlayback(r, other)
                return
            }
        }
        val e = entry
        val provider = Session.provider
        // The server may refuse unknown players: look for a User-Agent it accepts (what makes MX/VLC work).
        if (!uaSearched && r.url.startsWith("http")) {
            uaSearched = true
            info.text = L.t("جارٍ تجربة إعدادات اتصال أخرى…", "Trying other connection settings…")
            spinner.visibility = View.VISIBLE
            lifecycleScope.launch {
                val ua = StreamProbe.findWorkingUa(r)
                if (ua == null) { spinner.visibility = View.GONE; continueAfterUa(msg, containerIssue); return@launch }
                applyUserAgent(ua)
            }
            return
        }
        continueAfterUa(msg, containerIssue)
    }

    private fun continueAfterUa(msg: String, containerIssue: Boolean) {
        val r = req ?: return
        val e = entry
        val provider = Session.provider
        if (isLive && retries < 2 && e != null && provider != null) {
            retries++
            info.text = L.t("إعادة طلب رابط جديد…", "Requesting a fresh link…") + " ($retries)"
            lifecycleScope.launch {
                delay(1200)
                val fresh = runCatching { provider.resolve(e) }.getOrNull() ?: r
                startPlayback(fresh, engineKind)
            }
            return
        }
        showErrorDialog(msg, manual = false)
    }

    /** Uses [ua] for this stream, remembers it for the subscription and restarts playback. */
    private fun applyUserAgent(ua: String) {
        val r = req ?: return
        Session.uaOverride = ua
        Session.provider?.profile?.let { p ->
            ProfileStore.profile(p.id)?.let { ProfileStore.saveProfile(it.copy(playerUserAgent = ua)) }
        }
        Toast.makeText(this, L.t("تم العثور على إعداد مناسب للخادم: ", "Found settings the server accepts: ") + ua.take(40), Toast.LENGTH_LONG).show()
        tried.clear(); retries = 0
        val e = entry
        val provider = Session.provider
        lifecycleScope.launch {
            val fresh = if (isLive && e != null && provider != null) runCatching { provider.resolve(e) }.getOrNull() else null
            startPlayback((fresh ?: r).copy(userAgent = ua), null)
        }
    }

    private fun optionsDialog() {
        val r = req ?: return
        val uas = com.newlife.tv.data.DeviceIdentity.playerUaCandidates()
        val labels = mutableListOf(L.t("تشغيل بمشغل VLC", "Play with VLC"), L.t("تشغيل بمشغل ExoPlayer", "Play with ExoPlayer"))
        labels += uas.map { "User-Agent: " + it.take(45) + (if (it == r.userAgent) "  ✓" else "") }
        AlertDialog.Builder(this)
            .setTitle(L.t("خيارات التشغيل", "Playback options"))
            .setItems(labels.toTypedArray()) { _, i ->
                when (i) {
                    0 -> { tried.clear(); startPlayback(r, ENGINE_VLC) }
                    1 -> { tried.clear(); startPlayback(r, ENGINE_EXO) }
                    else -> applyUserAgent(uas[i - 2])
                }
            }
            .setNegativeButton(L.t("إلغاء", "Cancel"), null)
            .show()
    }

    private fun showErrorDialog(msg: String, manual: Boolean) {
        val r = req ?: return
        errorDialog?.dismiss()
        val base = buildString {
            append(msg).append("\n\n")
            append(L.t("المشغل: ", "Engine: ")).append(engine?.name ?: "-").append("\n")
            append(L.t("الرابط: ", "Link: ")).append(r.url.take(160)).append("\n\n")
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle(if (manual) L.t("فحص رابط القناة", "Stream link check") else L.t("تعذر تشغيل القناة", "Cannot play this stream"))
            .setMessage(base + L.t("جارٍ فحص الرابط…", "Checking the link…"))
            .setPositiveButton(L.t("خيارات أخرى", "More options")) { _, _ -> optionsDialog() }
            .setNeutralButton(L.t("مشغل خارجي", "External player")) { _, _ -> ExternalPlayer.open(this, r) }
            .setNegativeButton(if (manual) L.t("إغلاق", "Close") else L.t("رجوع", "Back")) { _, _ -> if (!manual) finish() }
            .show()
        errorDialog = dlg
        lifecycleScope.launch {
            val probe = StreamProbe.probe(r)
            if (dlg.isShowing) dlg.setMessage(base + probe)
        }
    }

    private fun switchEngine() {
        val r = req ?: return
        tried.clear(); retries = 0; forceHls = false
        startPlayback(r, if (engineKind == ENGINE_VLC) ENGINE_EXO else ENGINE_VLC)
    }

    private fun togglePlay() {
        val e = engine ?: return
        e.setPlaying(!e.isPlaying())
        playButton?.text = if (e.isPlaying()) "❚❚" else "▶"
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    }

    private fun updateInfo() {
        val e = entry
        val num = if (e != null && e.kind == ContentKind.LIVE && e.number > 0) "${e.number}. " else ""
        val eng = engine
        val pos = if (eng != null && !isLive && eng.duration() > 0) "${fmt(eng.position())} / ${fmt(eng.duration())}" else null
        val parts = listOfNotNull(
            num + (req?.title ?: e?.title ?: ""),
            nowProgram.takeIf { it.isNotBlank() },
            qualityLabel.takeIf { it.isNotBlank() },
            pos,
            eng?.name,
            if (recordingId != null) "● REC" else null,
        )
        info.text = parts.joinToString("   •   ")
    }

    private fun loadNowProgram() {
        val e = entry ?: return
        if (e.kind != ContentKind.LIVE || req?.isLive != true) return
        val provider = Session.provider ?: return
        lifecycleScope.launch {
            val list = runCatching { provider.epg(e) }.getOrDefault(emptyList())
            val now = list.firstOrNull { it.isNow }
            val next = list.firstOrNull { it.start >= (now?.end ?: System.currentTimeMillis()) }
            nowProgram = listOfNotNull(
                now?.let { "▶ ${L.hm(it.start)} ${it.title}" },
                next?.let { L.t("التالي", "Next") + ": ${L.hm(it.start)} ${it.title}" },
            ).joinToString("  |  ")
            updateInfo()
        }
    }

    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        val provider = Session.provider ?: return
        if (req?.isLive != true) return
        keepAliveJob = lifecycleScope.launch {
            while (isActive) {
                delay(60_000)
                runCatching { provider.keepAlive() }
            }
        }
    }

    // ------------------------------------------------------------------ overlay
    private fun showOverlay() {
        if (inPip()) return
        overlay.visibility = View.VISIBLE
        playButton?.text = if (engine?.isPlaying() == false) "▶" else "❚❚"
        updateInfo()
        firstButton.requestFocus()
        scheduleHide()
        tickJob?.cancel()
        tickJob = lifecycleScope.launch { while (isActive) { delay(1000); updateInfo() } }
    }

    private fun hideOverlay() {
        overlay.visibility = View.GONE
        hideJob?.cancel(); tickJob?.cancel()
    }

    private fun scheduleHide() {
        hideJob?.cancel()
        hideJob = lifecycleScope.launch { delay(7000); hideOverlay() }
    }

    // ------------------------------------------------------------------ zapping & keys
    private fun zap(delta: Int) {
        val list = Session.zapList
        val cur = entry ?: return
        if (list.isEmpty() || cur.kind != ContentKind.LIVE) return
        val pos = if (index in list.indices && list[index].id == cur.id) index else list.indexOfFirst { it.id == cur.id }
        val next = ((pos + delta) % list.size + list.size) % list.size
        val e = list[next]
        zapInfo.text = (if (e.number > 0) "${e.number}  " else "") + e.title
        zapInfo.visibility = View.VISIBLE
        hideZapJob?.cancel()
        hideZapJob = lifecycleScope.launch { delay(2500); zapInfo.visibility = View.GONE }
        val provider = Session.provider ?: return
        lifecycleScope.launch {
            val r = runCatching { provider.resolve(e) }.getOrNull()
            if (r == null) {
                Toast.makeText(this@PlayerActivity, L.t("تعذر فتح القناة", "Cannot open channel"), Toast.LENGTH_SHORT).show()
                return@launch
            }
            entry = e; index = next
            newSession()
            startPlayback(r, null)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && !inPip()) {
            val key = event.keyCode
            if (overlay.visibility == View.VISIBLE) {
                scheduleHide()
                if (key == KeyEvent.KEYCODE_BACK) { hideOverlay(); return true }
                return super.dispatchKeyEvent(event)
            }
            val live = entry?.kind == ContentKind.LIVE && isLive
            when (key) {
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_DPAD_UP -> if (live) { zap(+1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_DPAD_DOWN -> if (live) { zap(-1); return true }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> if (!live) { engine?.seekBy(-10_000); showOverlay(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (!live) { engine?.seekBy(10_000); showOverlay(); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> { showOverlay(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> { togglePlay(); return true }
                KeyEvent.KEYCODE_PROG_RED -> { recordDialog(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ------------------------------------------------------------------ actions
    private fun cycleResize() {
        resizeIdx = (resizeIdx + 1) % 4
        engine?.setResize(resizeIdx)
        ProfileStore.updateSettings { it.copy(resizeMode = resizeIdx) }
        Toast.makeText(this, resizeNames[resizeIdx], Toast.LENGTH_SHORT).show()
    }

    private fun toggleFav() {
        val e = entry ?: return
        val pid = Session.provider?.profile?.id ?: return
        val added = ProfileStore.toggleFavorite(pid, e)
        Toast.makeText(this, if (added) L.t("أضيفت إلى المفضلة", "Added to favorites") else L.t("أزيلت من المفضلة", "Removed from favorites"), Toast.LENGTH_SHORT).show()
    }

    private fun recordDialog() {
        val r = req ?: return
        if (r.url.startsWith("file:") || r.url.startsWith("/")) return
        recordingId?.let { id ->
            if (RecordingService.active.value.containsKey(id)) {
                RecordingService.stop(this, id)
                recordingId = null
                recButton?.text = L.t("تسجيل", "Record")
                updateInfo()
                Toast.makeText(this, L.t("تم إيقاف التسجيل", "Recording stopped"), Toast.LENGTH_SHORT).show()
                return
            }
        }
        val opts = intArrayOf(15, 30, 60, 90, 120, 180, 360)
        val labels = opts.map { if (it < 60) L.t("$it دقيقة", "$it min") else L.t("${it / 60.0} ساعة", "${it / 60.0} h").replace(".0", "") }
        AlertDialog.Builder(this)
            .setTitle(L.t("مدة التسجيل", "Recording duration"))
            .setItems(labels.toTypedArray()) { _, which ->
                recordingId = RecordingService.startNow(this, r, r.title, opts[which])
                recButton?.text = L.t("إيقاف التسجيل", "Stop REC")
                updateInfo()
                Toast.makeText(this, L.t("بدأ التسجيل", "Recording started"), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(L.t("إلغاء", "Cancel"), null)
            .show()
    }

    // --------------------------------------------------------------------- PiP
    private fun inPip(): Boolean = Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode

    private fun pipSupported() = Build.VERSION.SDK_INT >= 26 &&
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun pipParams(): PictureInPictureParams {
        val (w, h) = engine?.videoSize() ?: (0 to 0)
        var ratio = if (w > 0 && h > 0) w.toFloat() / h else 16f / 9f
        ratio = ratio.coerceIn(0.42f, 2.38f)
        return PictureInPictureParams.Builder().setAspectRatio(Rational((ratio * 1000).toInt(), 1000)).build()
    }

    private fun enterPip() {
        if (!pipSupported()) {
            Toast.makeText(this, L.t("الجهاز لا يدعم صورة في صورة", "Picture-in-picture not supported"), Toast.LENGTH_SHORT).show()
            return
        }
        hideOverlay()
        runCatching { enterPictureInPictureMode(pipParams()) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (ProfileStore.settings.value.autoPip && engine?.isPlaying() == true && pipSupported()) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) { hideOverlay(); zapInfo.visibility = View.GONE }
    }

    override fun onStart() {
        super.onStart()
        if (ready) engine?.setPlaying(true)
    }

    override fun onStop() {
        super.onStop()
        if (!inPip()) engine?.setPlaying(false)
    }

    override fun onDestroy() {
        startJob?.cancel(); keepAliveJob?.cancel()
        errorDialog?.dismiss()
        engine?.release()
        engine = null
        super.onDestroy()
    }

    // ================================================================ ExoPlayer
    private inner class ExoEngine : Engine {
        override val name = "ExoPlayer"
        private val pv = PlayerView(this@PlayerActivity).apply {
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            keepScreenOn = true
        }
        private var p: ExoPlayer? = null
        override val view: View get() = pv

        private val modes = intArrayOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_FILL,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM, AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH,
        )

        override fun play(r: PlayRequest) {
            val ua = r.userAgent.ifBlank { DeviceIdentity.DEFAULT_PLAYER_UA }
            val http = OkHttpDataSource.Factory(Http.client).setUserAgent(ua).setDefaultRequestProperties(r.headers)
            val dataSource = DefaultDataSource.Factory(this@PlayerActivity, http)
            val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
            )
            val msf = DefaultMediaSourceFactory(dataSource, extractors)
            val item = MediaItem.Builder().setUri(Uri.parse(r.url))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(r.title).build())
            (if (forceHls) MimeTypes.APPLICATION_M3U8 else mimeFor(r.url))?.let { item.setMimeType(it) }
            if (r.drmLicense.isNotBlank()) {
                val scheme = r.drmScheme.lowercase()
                val uuid = when {
                    scheme.contains("clearkey") -> C.CLEARKEY_UUID
                    scheme.contains("playready") -> C.PLAYREADY_UUID
                    else -> C.WIDEVINE_UUID
                }
                if (r.drmLicense.startsWith("http")) {
                    item.setDrmConfiguration(MediaItem.DrmConfiguration.Builder(uuid).setLicenseUri(r.drmLicense).setLicenseRequestHeaders(r.headers).build())
                } else clearKeyJson(r.drmLicense)?.let { json ->
                    val mgr = DefaultDrmSessionManager.Builder()
                        .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                        .build(LocalMediaDrmCallback(json.toByteArray()))
                    msf.setDrmSessionManagerProvider { mgr }
                    item.setDrmConfiguration(MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID).build())
                }
            }
            val minBuf = bufferMs
            val load = DefaultLoadControl.Builder()
                .setBufferDurationsMs(minBuf, maxOf(minBuf * 2, 50_000), 1500, minOf(3000, minBuf))
                .build()
            val renderers = DefaultRenderersFactory(this@PlayerActivity)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                .setEnableDecoderFallback(true)
            val player = ExoPlayer.Builder(this@PlayerActivity, renderers).setMediaSourceFactory(msf).setLoadControl(load).build()
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_READY -> { onBuffering(false); onReady() }
                        Player.STATE_BUFFERING -> onBuffering(true)
                        Player.STATE_ENDED -> if (isLive) onEngineError(L.t("انتهى البث من الخادم", "Stream ended by the server"), false)
                        else -> Unit
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                        player.seekToDefaultPosition(); player.prepare(); return
                    }
                    val containerIssue = error.errorCode in setOf(
                        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
                    )
                    onEngineError("ExoPlayer: ${error.errorCodeName}\n${error.cause?.message ?: error.message ?: ""}", containerIssue)
                }

                override fun onTracksChanged(tracks: Tracks) {
                    // Audio or video present but no decoder for it (e.g. AC3 on some devices) -> let VLC try.
                    val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                    val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                    if ((audio.isNotEmpty() && audio.none { it.isSupported }) || (video.isNotEmpty() && video.none { it.isSupported })) {
                        onEngineError(L.t("صيغة الصوت/الصورة غير مدعومة في ExoPlayer", "Audio/video codec not supported by ExoPlayer"), false)
                    }
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) = onVideoSize(videoSize.width, videoSize.height)
            })
            pv.player = player
            p = player
            player.setMediaItem(item.build())
            player.prepare()
            player.playWhenReady = true
        }

        override fun release() { pv.player = null; p?.release(); p = null }
        override fun setPlaying(play: Boolean) { p?.playWhenReady = play }
        override fun isPlaying() = p?.playWhenReady == true
        override fun seekBy(ms: Long) { p?.let { it.seekTo((it.currentPosition + ms).coerceAtLeast(0)) } }
        override fun position() = p?.currentPosition ?: 0L
        override fun duration() = p?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        override fun setResize(idx: Int) { pv.resizeMode = modes[idx.coerceIn(0, modes.size - 1)] }
        override fun videoSize(): Pair<Int, Int> = p?.videoSize?.let { it.width to it.height } ?: (0 to 0)

        override fun tracks(kind: Int) {
            val player = p ?: return
            val type = when (kind) { 0 -> C.TRACK_TYPE_VIDEO; 1 -> C.TRACK_TYPE_AUDIO; else -> C.TRACK_TYPE_TEXT }
            val title = when (kind) { 0 -> L.t("الجودة / الدقة", "Video quality"); 1 -> L.t("مسار الصوت", "Audio track"); else -> L.t("الترجمة", "Subtitles") }
            TrackSelectionDialogBuilder(this@PlayerActivity, title, player, type)
                .setAllowAdaptiveSelections(type == C.TRACK_TYPE_VIDEO)
                .build()
                .show()
        }
    }

    // ===================================================================== VLC
    private inner class VlcEngine : Engine {
        override val name = "VLC"
        private val layout = VLCVideoLayout(this@PlayerActivity)
        private val libVlc = LibVLC(this@PlayerActivity, arrayListOf(
            "--http-reconnect",
            "--network-caching=$bufferMs",
            "--live-caching=$bufferMs",
            "--avcodec-skiploopfilter=1",
            "--audio-time-stretch",
            "--no-stats",
        ))
        private val mp = MediaPlayer(libVlc)
        private var attached = false
        override val view: View get() = layout

        private val scales = arrayOf(
            MediaPlayer.ScaleType.SURFACE_BEST_FIT, MediaPlayer.ScaleType.SURFACE_FILL,
            MediaPlayer.ScaleType.SURFACE_FIT_SCREEN, MediaPlayer.ScaleType.SURFACE_16_9,
        )

        override fun play(r: PlayRequest) {
            if (!attached) { mp.attachViews(layout, null, true, false); attached = true }
            mp.setEventListener(MediaPlayer.EventListener { ev ->
                runOnUiThread {
                    when (ev.type) {
                        MediaPlayer.Event.Playing -> { onBuffering(false); onReady() }
                        MediaPlayer.Event.Buffering -> onBuffering(ev.buffering < 100f && !ready)
                        MediaPlayer.Event.Vout -> runCatching { val (w, h) = videoSize(); if (w > 0) onVideoSize(w, h) }
                        MediaPlayer.Event.EncounteredError -> onEngineError(L.t("VLC لم يستطع فتح البث", "VLC could not open the stream"), false)
                        MediaPlayer.Event.EndReached -> if (isLive) onEngineError(L.t("انتهى البث من الخادم", "Stream ended by the server"), false)
                        else -> Unit
                    }
                }
            })
            val media = Media(libVlc, Uri.parse(r.url))
            media.setHWDecoderEnabled(true, false)
            media.addOption(":network-caching=$bufferMs")
            media.addOption(":http-user-agent=" + r.userAgent.ifBlank { DeviceIdentity.DEFAULT_PLAYER_UA })
            r.headers["Referer"]?.let { media.addOption(":http-referrer=$it") }
            mp.setMedia(media)
            media.release()
            mp.play()
        }

        override fun release() {
            runCatching { mp.setEventListener(null) }
            runCatching { mp.stop() }
            runCatching { if (attached) mp.detachViews() }
            runCatching { mp.release() }
            runCatching { libVlc.release() }
        }

        override fun setPlaying(play: Boolean) { if (play) mp.play() else mp.pause() }
        override fun isPlaying() = mp.isPlaying
        override fun seekBy(ms: Long) { if (mp.isSeekable) mp.setTime((mp.time + ms).coerceAtLeast(0)) }
        override fun position() = mp.time.coerceAtLeast(0)
        override fun duration() = mp.length.coerceAtLeast(0)
        override fun setResize(idx: Int) { mp.setVideoScale(scales[idx.coerceIn(0, scales.size - 1)]) }

        override fun videoSize(): Pair<Int, Int> {
            val t = runCatching { mp.currentVideoTrack }.getOrNull() ?: return 0 to 0
            return t.width to t.height
        }

        override fun tracks(kind: Int) {
            if (kind == 0) {
                val (w, h) = videoSize()
                Toast.makeText(this@PlayerActivity,
                    (if (h > 0) "${w}×$h ${L.quality(h)}\n" else "") +
                        L.t("اختيار الجودة يدويًا متاح في ExoPlayer", "Manual quality selection is available in ExoPlayer"),
                    Toast.LENGTH_LONG).show()
                return
            }
            val list = (if (kind == 1) mp.audioTracks else mp.spuTracks)?.toList().orEmpty()
            if (list.isEmpty()) {
                Toast.makeText(this@PlayerActivity, L.t("لا توجد مسارات", "No tracks"), Toast.LENGTH_SHORT).show(); return
            }
            AlertDialog.Builder(this@PlayerActivity)
                .setTitle(if (kind == 1) L.t("مسار الصوت", "Audio track") else L.t("الترجمة", "Subtitles"))
                .setItems(list.map { it.name ?: "#${it.id}" }.toTypedArray()) { _, i ->
                    if (kind == 1) mp.setAudioTrack(list[i].id) else mp.setSpuTrack(list[i].id)
                }
                .show()
        }
    }

    // ------------------------------------------------------------------ helpers
    private fun mimeFor(url: String): String? {
        val u = url.lowercase()
        val path = u.substringBefore('?')
        return when {
            path.endsWith(".m3u8") || u.contains("extension=m3u8") || u.contains("output=m3u8") || u.contains("type=m3u8") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") || u.contains("format=mpd") -> MimeTypes.APPLICATION_MPD
            path.endsWith(".ism/manifest") || path.endsWith(".isml/manifest") -> MimeTypes.APPLICATION_SS
            else -> null
        }
    }

    /** "kid:key[,kid:key]" (hex) -> ClearKey JSON for local licensing. */
    private fun clearKeyJson(spec: String): String? {
        val pairs = spec.split(',').mapNotNull { p ->
            val kid = p.substringBefore(':').trim(); val key = p.substringAfter(':', "").trim()
            if (kid.length == 32 && key.length == 32) kid to key else null
        }
        if (pairs.isEmpty()) return null
        fun b64(hex: String): String {
            val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        }
        val keys = pairs.joinToString(",") { (kid, key) -> "{\"kty\":\"oct\",\"k\":\"${b64(key)}\",\"kid\":\"${b64(kid)}\"}" }
        return "{\"keys\":[$keys],\"type\":\"temporary\"}"
    }
}

/** Opens a stream in VLC / MX Player / Just Player or any installed video player. */
object ExternalPlayer {
    val KNOWN = listOf(
        "" to "Ask / اسأل كل مرة",
        "org.videolan.vlc" to "VLC",
        "com.mxtech.videoplayer.ad" to "MX Player",
        "com.mxtech.videoplayer.pro" to "MX Player Pro",
        "com.brouken.player" to "Just Player",
        "is.xyz.mpv" to "mpv",
    )

    fun open(ctx: Context, req: PlayRequest) {
        val ua = req.userAgent.ifBlank { DeviceIdentity.DEFAULT_PLAYER_UA }
        val headers = ArrayList<String>().apply {
            add("User-Agent"); add(ua)
            req.headers.forEach { (k, v) -> add(k); add(v) }
        }
        val base = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(req.url), "video/*")
            .putExtra("title", req.title)
            .putExtra("headers", headers.toTypedArray())
            .putExtra("http-user-agent", ua)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pkg = ProfileStore.settings.value.externalPackage
        if (pkg.isNotBlank()) {
            try { ctx.startActivity(Intent(base).setPackage(pkg)); return } catch (_: ActivityNotFoundException) {
                Toast.makeText(ctx, L.t("المشغل المختار غير مثبت", "Selected player is not installed"), Toast.LENGTH_SHORT).show()
            }
        }
        try {
            ctx.startActivity(Intent.createChooser(base, req.title.ifBlank { "NEW LIFE TV" }).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, L.t("لا يوجد مشغل خارجي مثبت", "No external player installed"), Toast.LENGTH_LONG).show()
        }
    }
}
