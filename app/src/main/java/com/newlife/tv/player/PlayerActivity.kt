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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
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
import com.newlife.tv.record.RecordingService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_REQ = "req"
        private const val EXTRA_ENTRY = "entry"
        private const val EXTRA_INDEX = "index"
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

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var topBar: LinearLayout
    private lateinit var info: TextView
    private lateinit var zapInfo: TextView
    private var recButton: Button? = null

    private var req: PlayRequest? = null
    private var entry: MediaEntry? = null
    private var index = -1
    private var retries = 0
    private var resizeIdx = 0
    private var qualityLabel = ""
    private var nowProgram = ""
    private var recordingId: String? = null
    private var keepAliveJob: Job? = null
    private var hideZapJob: Job? = null

    private val resizeModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH,
    )
    private val resizeNames get() = listOf(L.t("ملاءمة", "Fit"), L.t("ملء الشاشة", "Stretch"), L.t("تكبير", "Zoom"), L.t("عرض كامل", "Fit width"))

    // ------------------------------------------------------------------ setup
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProfileStore.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        resizeIdx = ProfileStore.settings.value.resizeMode.coerceIn(0, resizeModes.size - 1)
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
        playerView = PlayerView(this).apply {
            useController = true
            controllerShowTimeoutMs = 5000
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            keepScreenOn = true
            resizeMode = resizeModes[resizeIdx]
        }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xB3000000.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = View.GONE
        }
        info = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 16f; setTypeface(typeface, Typeface.BOLD); maxLines = 2
        }
        topBar.addView(info)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun add(label: String, action: () -> Unit): Button = Button(this).apply {
            text = label; isAllCaps = false; textSize = 13f
            setOnClickListener { action() }
            buttons.addView(this, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        }
        add(L.t("الجودة", "Quality")) { trackDialog(C.TRACK_TYPE_VIDEO, L.t("الجودة / الدقة", "Video quality")) }
        add(L.t("الصوت", "Audio")) { trackDialog(C.TRACK_TYPE_AUDIO, L.t("مسار الصوت", "Audio track")) }
        add(L.t("الترجمة", "Subtitles")) { trackDialog(C.TRACK_TYPE_TEXT, L.t("الترجمة", "Subtitles")) }
        add(L.t("أبعاد الصورة", "Aspect")) { cycleResize() }
        add(L.t("صورة في صورة", "PiP")) { enterPip() }
        recButton = add(L.t("تسجيل", "Record")) { recordDialog() }
        add(L.t("مشغل خارجي", "External player")) { req?.let { ExternalPlayer.open(this, it) } }
        add(L.t("المفضلة", "Favorite")) { toggleFav() }
        topBar.addView(HorizontalScrollView(this).apply { isFillViewport = true; addView(buttons) })
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        zapInfo = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 22f; setBackgroundColor(0xB3000000.toInt())
            setPadding(dp(20), dp(10), dp(20), dp(10)); visibility = View.GONE
        }
        root.addView(zapInfo, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(90) })

        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
            topBar.visibility = if (v == View.VISIBLE && !inPip()) View.VISIBLE else View.GONE
        })
        setContentView(root)
    }

    private fun handleIntent(intent: Intent) {
        val r = intent.getStringExtra(EXTRA_REQ)?.let { gson.fromJson(it, PlayRequest::class.java) } ?: run { finish(); return }
        entry = intent.getStringExtra(EXTRA_ENTRY)?.let { gson.fromJson(it, MediaEntry::class.java) }
        index = intent.getIntExtra(EXTRA_INDEX, -1)
        retries = 0
        play(r)
    }

    // ---------------------------------------------------------------- playback
    private fun play(r: PlayRequest) {
        req = r
        releasePlayer()
        val settings = ProfileStore.settings.value
        val ua = r.userAgent.ifBlank { DeviceIdentity.DEFAULT_PLAYER_UA }

        val http = OkHttpDataSource.Factory(Http.client).setUserAgent(ua).setDefaultRequestProperties(r.headers)
        val dataSource = DefaultDataSource.Factory(this, http)
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
        val msf = DefaultMediaSourceFactory(dataSource, extractors)

        val item = MediaItem.Builder().setUri(Uri.parse(r.url)).setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder().setTitle(r.title).build()
        )
        mimeFor(r.url)?.let { item.setMimeType(it) }
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

        val minBuf = (settings.bufferSeconds.coerceIn(2, 120) * 1000).coerceAtLeast(2500)
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBuf, maxOf(minBuf * 2, 50_000), 1500, minOf(3000, minBuf))
            .build()
        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)

        val p = ExoPlayer.Builder(this, renderers).setMediaSourceFactory(msf).setLoadControl(load).build()
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    p.seekToDefaultPosition(); p.prepare(); return
                }
                onError(error)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                qualityLabel = if (videoSize.height > 0) "${videoSize.width}×${videoSize.height} ${L.quality(videoSize.height)}" else ""
                updateInfo()
                if (Build.VERSION.SDK_INT >= 26 && inPip()) runCatching { setPictureInPictureParams(pipParams()) }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) retries = 0
            }
        })
        playerView.player = p
        player = p
        p.setMediaItem(item.build())
        p.prepare()
        p.playWhenReady = true
        nowProgram = ""
        updateInfo()
        loadNowProgram()
        startKeepAlive()
    }

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

    private fun onError(error: PlaybackException) {
        val e = entry
        val provider = Session.provider
        if (retries < 3 && e != null && provider != null && (req?.isLive == true || e.kind == ContentKind.LIVE)) {
            retries++
            info.text = L.t("إعادة المحاولة…", "Retrying…") + " ($retries)"
            lifecycleScope.launch {
                delay(1500)
                val fresh = runCatching { provider.resolve(e) }.getOrNull() ?: req
                fresh?.let { play(it) }
            }
            return
        }
        Toast.makeText(this, L.t("تعذر التشغيل: ", "Playback error: ") + error.errorCodeName, Toast.LENGTH_LONG).show()
        playerView.showController()
    }

    private fun updateInfo() {
        val e = entry
        val num = if (e != null && e.kind == ContentKind.LIVE && e.number > 0) "${e.number}. " else ""
        val parts = listOfNotNull(
            num + (req?.title ?: e?.title ?: ""),
            nowProgram.takeIf { it.isNotBlank() },
            qualityLabel.takeIf { it.isNotBlank() },
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

    private fun releasePlayer() {
        keepAliveJob?.cancel()
        player?.release()
        player = null
    }

    // ------------------------------------------------------------------ zapping
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
            entry = e; index = next; retries = 0
            play(r)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && !inPip()) {
            val live = entry?.kind == ContentKind.LIVE
            val controllerShown = playerView.isControllerFullyVisible
            when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP -> if (live) { zap(+1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> if (live) { zap(-1); return true }
                KeyEvent.KEYCODE_DPAD_UP -> if (live && !controllerShown) { zap(+1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> if (live && !controllerShown) { zap(-1); return true }
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> { playerView.showController(); return true }
                KeyEvent.KEYCODE_PROG_RED -> { recordDialog(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ------------------------------------------------------------------ actions
    private fun trackDialog(type: Int, title: String) {
        val p = player ?: return
        TrackSelectionDialogBuilder(this, title, p, type)
            .setShowDisabledOption(type == C.TRACK_TYPE_TEXT)
            .setAllowAdaptiveSelections(type == C.TRACK_TYPE_VIDEO)
            .build()
            .show()
    }

    private fun cycleResize() {
        resizeIdx = (resizeIdx + 1) % resizeModes.size
        playerView.resizeMode = resizeModes[resizeIdx]
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
        val f = player?.videoFormat
        var ratio = if (f != null && f.width > 0 && f.height > 0) f.width.toFloat() / f.height else 16f / 9f
        ratio = ratio.coerceIn(0.42f, 2.38f)
        return PictureInPictureParams.Builder().setAspectRatio(Rational((ratio * 1000).toInt(), 1000)).build()
    }

    private fun enterPip() {
        if (!pipSupported()) {
            Toast.makeText(this, L.t("الجهاز لا يدعم صورة في صورة", "Picture-in-picture not supported"), Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { enterPictureInPictureMode(pipParams()) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (ProfileStore.settings.value.autoPip && player?.isPlaying == true && pipSupported()) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        playerView.useController = !isInPictureInPictureMode
        if (isInPictureInPictureMode) { topBar.visibility = View.GONE; zapInfo.visibility = View.GONE }
    }

    override fun onStart() {
        super.onStart()
        player?.playWhenReady = true
    }

    override fun onStop() {
        super.onStop()
        if (!inPip()) player?.playWhenReady = false
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }
}

/** Opens a stream in VLC / MX Player / any installed video player. */
object ExternalPlayer {
    fun open(ctx: Context, req: PlayRequest) {
        val ua = req.userAgent.ifBlank { DeviceIdentity.DEFAULT_PLAYER_UA }
        val headers = ArrayList<String>().apply {
            add("User-Agent"); add(ua)
            req.headers.forEach { (k, v) -> add(k); add(v) }
        }
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(req.url), "video/*")
            .putExtra("title", req.title)
            .putExtra("headers", headers.toTypedArray())
            .putExtra("http-user-agent", ua)
        try {
            ctx.startActivity(Intent.createChooser(i, req.title.ifBlank { "NEW LIFE TV" }).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, L.t("لا يوجد مشغل خارجي مثبت", "No external player installed"), Toast.LENGTH_LONG).show()
        }
    }
}
