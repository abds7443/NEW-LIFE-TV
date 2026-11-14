package com.newlife.tv.multi

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.newlife.tv.data.ContentKind
import com.newlife.tv.data.DeviceIdentity
import com.newlife.tv.data.Favorite
import com.newlife.tv.data.L
import com.newlife.tv.data.PlayRequest
import com.newlife.tv.data.ProfileStore
import com.newlife.tv.net.Http
import com.newlife.tv.net.ProviderPool
import com.newlife.tv.net.Session
import com.newlife.tv.player.PlayerActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Channels chosen for the multi-view windows (kept while the app runs). */
object MultiView {
    const val MAX = 4
    val slots = arrayOfNulls<Favorite>(MAX)
    /** Window that has the sound (yellow frame). */
    var focus = 0
    /** Server of [Session.multiSource]. */
    var sourceOwner: String? = null
    /** Parental check supplied by the home screen. */
    var allowed: (Favorite) -> Boolean = { true }

    /** Puts [f] in the first empty window (or the selected one when all are full). */
    fun add(f: Favorite) {
        val i = slots.indexOfFirst { it == null }.takeIf { it >= 0 } ?: focus
        slots[i] = f
        focus = i
    }
}

/**
 * Up to four channels at once (2×2). Each window has its own player; only the window with the
 * yellow frame plays sound. Arrows move the frame, OK opens the channel list for that window,
 * long-press / Menu shows more options.
 */
@OptIn(UnstableApi::class)
class MultiViewActivity : ComponentActivity() {

    companion object {
        fun start(ctx: Context) {
            val i = Intent(ctx, MultiViewActivity::class.java)
            if (ctx !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }
        private const val YELLOW = 0xFFFFD400.toInt()
    }

    private val tiles = ArrayList<Tile>()
    private lateinit var clock: TextView
    private var clockJob: Job? = null

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        volumeControlStream = AudioManager.STREAM_MUSIC

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (row in 0 until 2) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (col in 0 until 2) {
                val t = Tile(row * 2 + col)
                tiles += t
                line.addView(t.frame, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            }
            grid.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        root.addView(grid, FrameLayout.LayoutParams(-1, -1))
        clock = TextView(this).apply {
            setTextColor(0xE6FFFFFF.toInt()); textSize = 13f; setTypeface(typeface, Typeface.BOLD)
            setBackgroundColor(0x88000000.toInt()); setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        root.addView(clock, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(6) })
        setContentView(root)

        // wire DPAD navigation between the four windows
        tiles.forEach { it.frame.id = View.generateViewId() }
        tiles.forEachIndexed { i, t ->
            t.frame.nextFocusRightId = tiles[if (i % 2 == 0) i + 1 else i].frame.id
            t.frame.nextFocusLeftId = tiles[if (i % 2 == 1) i - 1 else i].frame.id
            t.frame.nextFocusDownId = tiles[if (i < 2) i + 2 else i].frame.id
            t.frame.nextFocusUpId = tiles[if (i >= 2) i - 2 else i].frame.id
        }

        tiles.forEach { t -> MultiView.slots[t.idx]?.let { t.load(it) } }
        select(MultiView.focus.coerceIn(0, MultiView.MAX - 1))
        tiles[MultiView.focus].frame.requestFocus()
        startClock()
        if (MultiView.slots.all { it == null }) pickChannel(MultiView.focus)
    }

    private fun startClock() {
        clockJob?.cancel()
        clockJob = lifecycleScope.launch {
            while (true) {
                clock.text = L.dateTime(System.currentTimeMillis())
                delay(15_000)
            }
        }
    }

    /** Gives the sound and the yellow frame to window [i]. */
    private fun select(i: Int) {
        MultiView.focus = i
        tiles.forEach { it.setActive(it.idx == i) }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> { tileMenu(MultiView.focus); return true }
                KeyEvent.KEYCODE_CHANNEL_UP -> { zapTile(+1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> { zapTile(-1); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ------------------------------------------------------------------ channel choice
    private data class Choice(val label: String, val fav: Favorite)

    private fun choices(): List<Choice> {
        val out = ArrayList<Choice>()
        ProfileStore.favorites.value
            .filter { it.entry.kind == ContentKind.LIVE && ProfileStore.profile(it.profileId) != null && MultiView.allowed(it) }
            .forEach { f -> out += Choice("⭐ " + f.entry.title + "   —   " + (ProfileStore.profile(f.profileId)?.displayName() ?: ""), f) }
        val owner = MultiView.sourceOwner
        if (owner != null) Session.multiSource.forEach { e ->
            val f = Favorite(owner, e)
            if (MultiView.allowed(f)) out += Choice((if (e.number > 0) "${e.number}  " else "") + e.title, f)
        }
        return out
    }

    private fun pickChannel(i: Int) {
        val all = choices()
        if (all.isEmpty()) {
            Toast.makeText(this, L.t("أضف قنوات إلى المفضلة أو افتح قائمة قنوات أولاً", "Add channels to favorites or open a channel list first"), Toast.LENGTH_LONG).show()
            return
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(6), dp(12), 0) }
        val search = EditText(this).apply { hint = L.t("بحث…", "Search…"); setSingleLine() }
        val list = ListView(this)
        var shown = all
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList(all.map { it.label }))
        list.adapter = adapter
        box.addView(search)
        box.addView(list, LinearLayout.LayoutParams(-1, dp(360)))
        val dlg = AlertDialog.Builder(this)
            .setTitle(L.t("اختر قناة للشاشة ", "Choose a channel for window ") + (i + 1))
            .setView(box)
            .setNegativeButton(L.t("إلغاء", "Cancel"), null)
            .create()
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim().orEmpty()
                shown = if (q.isEmpty()) all else all.filter { it.label.contains(q, ignoreCase = true) }
                adapter.clear(); adapter.addAll(shown.map { it.label }); adapter.notifyDataSetChanged()
            }
        })
        list.setOnItemClickListener { _, _, pos, _ ->
            shown.getOrNull(pos)?.let { c ->
                MultiView.slots[i] = c.fav
                tiles[i].load(c.fav)
                select(i)
            }
            dlg.dismiss()
        }
        dlg.show()
        list.requestFocus()
    }

    private fun tileMenu(i: Int) {
        val f = MultiView.slots[i]
        val items = mutableListOf(L.t("تغيير القناة", "Change channel"))
        if (f != null) {
            items += L.t("ملء الشاشة", "Full screen")
            items += L.t("إعادة تحميل", "Reload")
            items += L.t("إزالة من هذه الشاشة", "Remove from this window")
        }
        items += L.t("إفراغ كل الشاشات", "Clear all windows")
        AlertDialog.Builder(this)
            .setTitle(L.t("الشاشة ", "Window ") + (i + 1))
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    L.t("تغيير القناة", "Change channel") -> pickChannel(i)
                    L.t("ملء الشاشة", "Full screen") -> f?.let { fullScreen(i, it) }
                    L.t("إعادة تحميل", "Reload") -> f?.let { tiles[i].load(it) }
                    L.t("إزالة من هذه الشاشة", "Remove from this window") -> { MultiView.slots[i] = null; tiles[i].clear() }
                    L.t("إفراغ كل الشاشات", "Clear all windows") -> tiles.forEach { MultiView.slots[it.idx] = null; it.clear() }
                }
            }
            .setNegativeButton(L.t("إغلاق", "Close"), null)
            .show()
    }

    /** Channel up/down inside the selected window, through the same list as the picker. */
    private fun zapTile(delta: Int) {
        val i = MultiView.focus
        val all = choices()
        if (all.isEmpty()) return
        val cur = MultiView.slots[i]
        val pos = all.indexOfFirst { it.fav.profileId == cur?.profileId && it.fav.entry.id == cur?.entry?.id }
        val next = all[((pos + delta) % all.size + all.size) % all.size].fav
        MultiView.slots[i] = next
        tiles[i].load(next)
    }

    private fun fullScreen(i: Int, f: Favorite) {
        val r = tiles[i].request ?: return
        tiles.forEach { it.release() }
        Session.zapList = emptyList(); Session.zapOwners = emptyList()
        PlayerActivity.start(this, r, f.entry, -1, f.profileId)
        finish()
    }

    override fun onStop() {
        super.onStop()
        tiles.forEach { it.release() }
    }

    override fun onRestart() {
        super.onRestart()
        tiles.forEach { t -> MultiView.slots[t.idx]?.let { t.load(it) } }
        select(MultiView.focus)
    }

    override fun onDestroy() {
        tiles.forEach { it.release() }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ one window
    private inner class Tile(val idx: Int) {
        val frame = FrameLayout(this@MultiViewActivity).apply {
            isFocusable = true
            isClickable = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        private val pv = PlayerView(this@MultiViewActivity).apply {
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            isFocusable = false
        }
        private val label = TextView(this@MultiViewActivity).apply {
            setTextColor(Color.WHITE); textSize = 12f; setBackgroundColor(0x99000000.toInt())
            setPadding(dp(8), dp(3), dp(8), dp(3)); maxLines = 1
        }
        private val hint = TextView(this@MultiViewActivity).apply {
            setTextColor(0xCCFFFFFF.toInt()); textSize = 16f; gravity = Gravity.CENTER
            text = "＋\n" + L.t("اضغط OK لاختيار قناة", "Press OK to choose a channel")
        }
        private val spinner = ProgressBar(this@MultiViewActivity).apply { visibility = View.GONE }
        private var player: ExoPlayer? = null
        private var loadJob: Job? = null
        private var retried = false
        var request: PlayRequest? = null; private set
        private var active = false

        init {
            frame.addView(pv, FrameLayout.LayoutParams(-1, -1))
            frame.addView(hint, FrameLayout.LayoutParams(-1, -1))
            frame.addView(spinner, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
            frame.addView(label, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
            label.visibility = View.GONE
            frame.setOnFocusChangeListener { _, has -> if (has) select(idx) }
            frame.setOnClickListener { if (MultiView.focus == idx || MultiView.slots[idx] == null) { select(idx); if (MultiView.slots[idx] == null) pickChannel(idx) else tileMenu(idx) } else select(idx) }
            frame.setOnLongClickListener { select(idx); tileMenu(idx); true }
            setActive(false)
        }

        fun setActive(a: Boolean) {
            active = a
            frame.background = GradientDrawable().apply {
                setColor(Color.BLACK)
                setStroke(if (a) dp(4) else dp(1), if (a) YELLOW else 0xFF333333.toInt())
            }
            player?.volume = if (a) 1f else 0f
            label.setTextColor(if (a) YELLOW else Color.WHITE)
        }

        fun load(f: Favorite) {
            loadJob?.cancel()
            releasePlayer()
            hint.visibility = View.GONE
            spinner.visibility = View.VISIBLE
            val server = ProfileStore.profile(f.profileId)?.displayName() ?: ""
            label.text = f.entry.title + "  •  " + server
            label.visibility = View.VISIBLE
            retried = false
            loadJob = lifecycleScope.launch {
                val r = runCatching { ProviderPool.resolve(this@MultiViewActivity, f.profileId, f.entry) }.getOrElse { ex ->
                    spinner.visibility = View.GONE
                    label.text = "❌ " + f.entry.title + " — " + (ex.message ?: "")
                    return@launch
                }
                request = r
                play(r, f)
            }
        }

        private fun play(r: PlayRequest, f: Favorite) {
            val ua = r.userAgent.ifBlank { DeviceIdentity.DEFAULT_PLAYER_UA }
            val http = OkHttpDataSource.Factory(Http.client).setUserAgent(ua).setDefaultRequestProperties(r.headers)
            val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
            )
            val msf = DefaultMediaSourceFactory(DefaultDataSource.Factory(this@MultiViewActivity, http), extractors)
            // four decoders at once: keep each window at 720p at most and use small buffers
            val selector = DefaultTrackSelector(this@MultiViewActivity).apply {
                setParameters(buildUponParameters().setMaxVideoSize(1280, 720))
            }
            val load = DefaultLoadControl.Builder().setBufferDurationsMs(4000, 15_000, 1500, 2500).build()
            val renderers = DefaultRenderersFactory(this@MultiViewActivity)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                .setEnableDecoderFallback(true)
            val p = ExoPlayer.Builder(this@MultiViewActivity, renderers)
                .setMediaSourceFactory(msf).setTrackSelector(selector).setLoadControl(load).build()
            val item = MediaItem.Builder().setUri(Uri.parse(r.url))
            mimeFor(r.url)?.let { item.setMimeType(it) }
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    spinner.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                    if (state == Player.STATE_ENDED) retry(f, null)
                }
                override fun onPlayerError(error: PlaybackException) {
                    if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                        p.seekToDefaultPosition(); p.prepare(); return
                    }
                    retry(f, error.errorCodeName)
                }
            })
            p.volume = if (active) 1f else 0f
            p.setMediaItem(item.build())
            p.playWhenReady = true
            p.prepare()
            pv.player = p
            player = p
        }

        /** One automatic retry with a fresh link, then the error is shown in the window. */
        private fun retry(f: Favorite, why: String?) {
            releasePlayer()
            if (retried) {
                spinner.visibility = View.GONE
                label.text = "❌ " + f.entry.title + (why?.let { " — $it" } ?: "")
                return
            }
            retried = true
            loadJob = lifecycleScope.launch {
                delay(2000)
                val r = runCatching { ProviderPool.resolve(this@MultiViewActivity, f.profileId, f.entry) }.getOrNull()
                if (r == null) { spinner.visibility = View.GONE; label.text = "❌ " + f.entry.title; return@launch }
                request = r
                play(r, f)
            }
        }

        private fun releasePlayer() {
            pv.player = null
            player?.release()
            player = null
        }

        fun release() {
            loadJob?.cancel()
            releasePlayer()
        }

        fun clear() {
            release()
            request = null
            spinner.visibility = View.GONE
            label.visibility = View.GONE
            hint.visibility = View.VISIBLE
        }
    }

    private fun mimeFor(url: String): String? {
        val u = url.lowercase()
        val path = u.substringBefore('?')
        return when {
            path.endsWith(".m3u8") || u.contains("extension=m3u8") || u.contains("output=m3u8") || u.contains("type=m3u8") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            else -> null
        }
    }
}
