package com.newlife.tv.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newlife.tv.data.L
import com.newlife.tv.relax.SoundEngine
import com.newlife.tv.relax.SoundEngine.Sound
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

private enum class Scene { RAIN, SEA, FOREST, STREAM, FIRE, NIGHT, CLOUDS, SLEEP }

private fun sceneFor(s: Sound?, sleep: Boolean): Scene = when (s) {
    Sound.RAIN -> Scene.RAIN
    Sound.SEA -> Scene.SEA
    Sound.BIRDS -> Scene.FOREST
    Sound.STREAM -> Scene.STREAM
    Sound.FIRE -> Scene.FIRE
    Sound.NIGHT -> Scene.NIGHT
    Sound.WIND -> Scene.CLOUDS
    else -> if (sleep) Scene.SLEEP else Scene.NIGHT
}

/** Pseudo-random 0..1 from an index (stable positions for particles). */
private fun h(i: Int, salt: Int = 0): Float {
    var x = i * 374761393 + salt * 668265263
    x = (x xor (x ushr 13)) * 1274126177
    return ((x xor (x ushr 16)) and 0x7fffffff) / 2147483647f
}

/**
 * Relax (sleep = false) or Sleep (sleep = true) screen: synthesized nature sounds,
 * matching animated scenery, breathing guide, timer with fade-out and dimmed warm light for sleep.
 */
@Composable
fun RelaxScreen(vm: MainViewModel, sleep: Boolean) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val levels by SoundEngine.levels.collectAsState()
    var controls by remember { mutableStateOf(true) }
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var breathing by remember { mutableStateOf(sleep) }
    var timerMin by remember { mutableStateOf(if (sleep) 30 else 0) }
    var endAt by remember { mutableLongStateOf(0L) }
    var dark by remember { mutableStateOf(false) }
    var brightness by remember { mutableFloatStateOf(if (sleep) 0.12f else -1f) }
    var finished by remember { mutableStateOf(false) }

    // start with a preset
    LaunchedEffect(Unit) {
        SoundEngine.setMix(
            if (sleep) mapOf(Sound.SEA to 0.45f, Sound.BROWN to 0.25f)
            else mapOf(Sound.RAIN to 0.7f, Sound.BIRDS to 0.35f)
        )
    }
    // keep screen on, apply brightness, restore and stop on exit
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            (ctx as? Activity)?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = -1f } }
            SoundEngine.stop()
        }
    }
    LaunchedEffect(brightness, dark) {
        (ctx as? Activity)?.window?.let { w ->
            w.attributes = w.attributes.apply { screenBrightness = if (dark) 0.01f else brightness }
        }
    }
    // timer
    LaunchedEffect(timerMin) {
        finished = false
        endAt = if (timerMin > 0) System.currentTimeMillis() + timerMin * 60_000L else 0L
    }
    LaunchedEffect(endAt) {
        if (endAt == 0L) return@LaunchedEffect
        while (System.currentTimeMillis() < endAt) delay(1000)
        SoundEngine.fadeTo(0f, if (sleep) 120_000 else 30_000, stopAtEnd = true)
        finished = true
        if (sleep) dark = true
    }
    // auto-hide controls
    LaunchedEffect(lastTouch, controls) {
        if (controls) { delay(9000); if (System.currentTimeMillis() - lastTouch >= 8500) controls = false }
    }
    BackHandler { if (controls) vm.back() else controls = true }

    val t by produceState(0f) {
        val start = System.nanoTime()
        while (true) withFrameNanos { value = (it - start) / 1e9f }
    }
    val main = levels.maxByOrNull { it.value }?.key
    val scene = sceneFor(main, sleep)

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                lastTouch = System.currentTimeMillis(); controls = !controls; if (dark) dark = false
            }
    ) {
        if (!dark) {
            Canvas(Modifier.fillMaxSize()) { drawScene(scene, t) }
            if (sleep) Box(Modifier.fillMaxSize().background(Color(0x33FF6A00))) // warm night light
        }

        // clock
        val now = (t * 0).toLong() + System.currentTimeMillis()
        Column(Modifier.align(Alignment.TopCenter).padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(L.hm(now), fontSize = if (dark) 28.sp else 44.sp, fontWeight = FontWeight.Light,
                color = Color.White.copy(alpha = if (dark) 0.25f else 0.85f))
            if (!dark) Text(L.fullDate(now), fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f))
            if (endAt > 0 && !finished) {
                val left = ((endAt - now) / 1000).coerceAtLeast(0)
                Text("⏱ %d:%02d".format(left / 60, left % 60), fontSize = 13.sp, color = Color.White.copy(alpha = if (dark) 0.25f else 0.7f))
            }
            if (finished) Text(L.t("تصبح على خير 🌙", "Good night 🌙"), color = Color.White.copy(alpha = 0.4f))
        }

        if (breathing && !dark) BreathingGuide(t, sleep, Modifier.align(Alignment.Center))

        AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Column(
                Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xCC0B1020))
                    .verticalScroll(rememberScrollState()).padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (sleep) L.t("🌙 النوم العميق", "🌙 Deep sleep") else L.t("🌿 استرخاء", "🌿 Relax"),
                        fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    NButton(L.t("شاشة مظلمة", "Dark screen"), Icons.Filled.DarkMode) { dark = true; controls = false }
                    Spacer(Modifier.width(6.dp))
                    NButton(L.t("خروج", "Exit"), Icons.Filled.Close) { vm.back() }
                }
                Text(L.t("الأصوات (يمكن دمج أكثر من صوت)", "Sounds (mix several)"), color = Palette.Text2, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Sound.entries.forEach { s ->
                        FilterChip(selected = levels.containsKey(s), onClick = { lastTouch = System.currentTimeMillis(); SoundEngine.toggle(s) },
                            label = { Text(s.icon + " " + L.t(s.ar, s.en)) }, modifier = Modifier.focusRing(RoundedCornerShape(8.dp)))
                    }
                }
                levels.entries.sortedBy { it.key.ordinal }.forEach { (s, v) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.icon, modifier = Modifier.width(30.dp))
                        Slider(value = v, onValueChange = { lastTouch = System.currentTimeMillis(); SoundEngine.setLevel(s, it.coerceAtLeast(0.02f)) },
                            modifier = Modifier.weight(1f).height(32.dp),
                            colors = SliderDefaults.colors(thumbColor = Palette.Gold, activeTrackColor = Palette.Accent))
                    }
                }
                Text(L.t("مؤقت الإيقاف (يخفت الصوت تدريجياً)", "Sleep timer (sound fades out)"), color = Palette.Text2, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 15, 30, 45, 60, 90, 120).forEach { m ->
                        FilterChip(selected = timerMin == m, onClick = { lastTouch = System.currentTimeMillis(); timerMin = m },
                            label = { Text(if (m == 0) L.t("بدون", "Off") else L.t("$m د", "$m min")) },
                            modifier = Modifier.focusRing(RoundedCornerShape(8.dp)))
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = breathing, onClick = { breathing = !breathing },
                        label = { Text(if (sleep) L.t("🫁 تنفس 4-7-8", "🫁 4-7-8 breathing") else L.t("🫁 تنفس مربع 4-4-4-4", "🫁 Box breathing")) },
                        modifier = Modifier.focusRing(RoundedCornerShape(8.dp)))
                    listOf(-1f to L.t("☀ إضاءة عادية", "☀ Normal light"), 0.35f to L.t("🔅 خافتة", "🔅 Dim"), 0.12f to L.t("🌑 خافتة جداً", "🌑 Very dim"))
                        .forEach { (b, label) ->
                            FilterChip(selected = brightness == b, onClick = { brightness = b }, label = { Text(label) },
                                modifier = Modifier.focusRing(RoundedCornerShape(8.dp)))
                        }
                }
                if (sleep) Text(
                    L.t("نصائح: ضع الهاتف بعيداً عن السرير، واستعمل سماعات لنغمة دلتا، وتنفّس ببطء مع الدائرة. هذه الأدوات تساعد على الاسترخاء وليست علاجاً طبياً.",
                        "Tips: keep the phone away from the bed, use headphones for the delta tone, and breathe slowly with the circle. These tools help you relax; they are not a medical treatment."),
                    color = Palette.Text2, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

// ============================================================================ breathing

@Composable
private fun BreathingGuide(t: Float, sleep: Boolean, modifier: Modifier) {
    // sleep: 4-7-8 (inhale 4, hold 7, exhale 8); relax: box breathing 4-4-4-4
    val phases = if (sleep) listOf(4f to "in", 7f to "hold", 8f to "out") else listOf(4f to "in", 4f to "hold", 4f to "out", 4f to "hold2")
    val total = phases.sumOf { it.first.toDouble() }.toFloat()
    var x = t % total
    var name = "in"; var p = 0f
    for ((d, n) in phases) { if (x < d) { name = n; p = x / d; break }; x -= d }
    val scale = when (name) { "in" -> p; "out" -> 1f - p; "hold" -> 1f; else -> 0f }
    val label = when (name) {
        "in" -> L.t("شهيق", "Breathe in")
        "out" -> L.t("زفير", "Breathe out")
        else -> L.t("احبس النفس", "Hold")
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.width(240.dp).height(240.dp)) {
            val r = size.minDimension / 2f * (0.45f + 0.5f * scale)
            drawCircle(Brush.radialGradient(listOf(Color(0x5500D1B2), Color(0x1100D1B2)), center, r), r, center)
            drawCircle(Color(0x88FFFFFF), r, center, style = Stroke(2.dp.toPx()))
        }
        Text(label, color = Color.White.copy(alpha = 0.9f), fontSize = 20.sp)
    }
}

// ============================================================================ scenes

private fun DrawScope.sky(c1: Color, c2: Color, c3: Color? = null) =
    drawRect(Brush.verticalGradient(if (c3 == null) listOf(c1, c2) else listOf(c1, c2, c3)))

private fun DrawScope.drawScene(scene: Scene, t: Float) {
    val w = size.width; val hgt = size.height
    when (scene) {
        Scene.RAIN -> {
            sky(Color(0xFF0B1426), Color(0xFF1C2B45), Color(0xFF2A3B55))
            for (i in 0 until 160) {
                val speed = 0.55f + h(i, 1) * 0.6f
                val x = h(i) * w + ((t * 40f * speed) % w) * 0.08f
                val y = ((h(i, 2) + t * speed) % 1f) * (hgt + 60f) - 30f
                drawLine(Color(0x66B9D7FF), Offset(x, y), Offset(x - 6f, y + 26f + 20f * speed), strokeWidth = 1.6f)
            }
            for (i in 0 until 6) {
                val cx = h(i, 7) * w; val cy = hgt * (0.85f + 0.12f * h(i, 8))
                val pr = ((t * 0.7f + h(i, 9)) % 1f)
                drawCircle(Color(0x44B9D7FF).copy(alpha = 0.4f * (1f - pr)), 8f + pr * 40f, Offset(cx, cy), style = Stroke(1.5f))
            }
        }
        Scene.SEA -> {
            sky(Color(0xFF13274F), Color(0xFFF08A4B), Color(0xFFFFC371))
            drawCircle(Color(0xCCFFE3A3), hgt * 0.07f, Offset(w * 0.7f, hgt * 0.42f))
            for (layer in 0 until 4) {
                val base = hgt * (0.5f + layer * 0.12f)
                val amp = 10f + layer * 8f
                val path = Path().apply {
                    moveTo(0f, hgt)
                    var x = 0f
                    while (x <= w) {
                        lineTo(x, base + amp * sin((x / w * 4f * PI + t * (0.5f + layer * 0.25f) + layer).toDouble()).toFloat()); x += 12f
                    }
                    lineTo(w, hgt); close()
                }
                drawPath(path, Color(0xFF0E4D74).copy(alpha = 0.55f + layer * 0.12f))
            }
        }
        Scene.FOREST -> {
            sky(Color(0xFF0F3D2E), Color(0xFF2E7D4F), Color(0xFF9CCC65))
            for (i in 0 until 5) {
                val a = (i * 0.25f + sin(t * 0.1 + i).toFloat() * 0.03f)
                val p = Path().apply {
                    moveTo(w * 0.15f, 0f); lineTo(w * (0.15f + a), hgt); lineTo(w * (0.25f + a), hgt); close()
                }
                drawPath(p, Color(0x14FFFFE0))
            }
            for (i in 0 until 9) {
                val x = h(i) * w; val th = hgt * (0.35f + h(i, 3) * 0.3f)
                drawRect(Color(0xFF2B1D14), Offset(x, hgt - th * 0.4f), Size(10f, th * 0.4f))
                drawCircle(Color(0xFF1B5E20).copy(alpha = 0.85f), th * 0.25f, Offset(x + 5f, hgt - th * 0.45f))
            }
            for (i in 0 until 5) {
                val bx = ((t * (0.04f + h(i, 4) * 0.03f) + h(i, 5)) % 1.2f) * w - w * 0.1f
                val by = hgt * (0.15f + h(i, 6) * 0.25f) + 8f * sin(t * 3 + i.toDouble()).toFloat()
                val wing = 10f + 4f * sin(t * 8 + i.toDouble()).toFloat()
                drawLine(Color(0xCC1B1B1B), Offset(bx - 14f, by - wing), Offset(bx, by), 3f)
                drawLine(Color(0xCC1B1B1B), Offset(bx, by), Offset(bx + 14f, by - wing), 3f)
            }
        }
        Scene.STREAM -> {
            sky(Color(0xFF0D3B47), Color(0xFF1C6E7D), Color(0xFF6FC3C9))
            for (i in 0 until 50) {
                val y = h(i) * hgt
                val len = 60f + h(i, 1) * 160f
                val x = ((h(i, 2) + t * (0.08f + h(i, 3) * 0.12f)) % 1.3f) * w - len
                drawLine(Color(0x55FFFFFF), Offset(x, y), Offset(x + len, y + 6f * sin(t + i.toDouble()).toFloat()), 2.5f)
            }
        }
        Scene.FIRE -> {
            sky(Color(0xFF0A0503), Color(0xFF1A0B05))
            val flicker = 0.85f + 0.15f * sin(t * 9.0).toFloat() * sin(t * 3.7).toFloat()
            drawCircle(Brush.radialGradient(listOf(Color(0xCCFF8A00), Color(0x66FF3D00), Color.Transparent),
                Offset(w / 2, hgt * 0.8f), hgt * 0.5f * flicker), hgt * 0.5f * flicker, Offset(w / 2, hgt * 0.8f))
            drawCircle(Brush.radialGradient(listOf(Color(0xEEFFD54F), Color.Transparent),
                Offset(w / 2, hgt * 0.82f), hgt * 0.16f * flicker), hgt * 0.16f * flicker, Offset(w / 2, hgt * 0.82f))
            for (i in 0 until 40) {
                val p = (h(i) + t * (0.15f + h(i, 1) * 0.2f)) % 1f
                val x = w / 2 + (h(i, 2) - 0.5f) * w * 0.3f + 20f * sin(t * 2 + i.toDouble()).toFloat()
                drawCircle(Color(0xFFFFB74D).copy(alpha = 1f - p), 2.5f, Offset(x, hgt * 0.8f - p * hgt * 0.7f))
            }
        }
        Scene.NIGHT, Scene.SLEEP -> {
            if (scene == Scene.SLEEP) sky(Color(0xFF020308), Color(0xFF070B1A)) else sky(Color(0xFF050A1F), Color(0xFF0D1B3D), Color(0xFF1A2A4F))
            for (i in 0 until 180) {
                val tw = 0.5f + 0.5f * sin(t * (0.5f + h(i, 3) * 2f) + i.toDouble()).toFloat()
                drawCircle(Color.White.copy(alpha = (if (scene == Scene.SLEEP) 0.35f else 0.8f) * tw), 1f + h(i, 4) * 1.8f,
                    Offset(h(i) * w, h(i, 1) * hgt * 0.85f))
            }
            drawCircle(Color(0xFFF5F3CE).copy(alpha = if (scene == Scene.SLEEP) 0.35f else 0.9f), hgt * 0.06f, Offset(w * 0.78f, hgt * 0.2f))
            if (scene == Scene.NIGHT) {
                val p = Path().apply {
                    moveTo(0f, hgt); var x = 0f
                    while (x <= w) { lineTo(x, hgt * 0.82f + 30f * sin(x / w * 7.0).toFloat()); x += 20f }
                    lineTo(w, hgt); close()
                }
                drawPath(p, Color(0xFF050608))
            }
        }
        Scene.CLOUDS -> {
            sky(Color(0xFF4A6FA5), Color(0xFF9DB9E0), Color(0xFFDCE8F5))
            for (i in 0 until 8) {
                val cw = w * (0.25f + h(i) * 0.25f)
                val x = ((h(i, 1) + t * (0.01f + h(i, 2) * 0.02f)) % 1.4f) * w - cw
                val y = hgt * (0.1f + h(i, 3) * 0.6f)
                for (k in 0 until 4) drawCircle(Color.White.copy(alpha = 0.55f), cw * (0.18f + 0.05f * k), Offset(x + k * cw * 0.22f, y - (k % 2) * 18f))
            }
        }
    }
}
