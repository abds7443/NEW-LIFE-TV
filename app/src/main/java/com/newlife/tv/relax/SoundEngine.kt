package com.newlife.tv.relax

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Real-time synthesizer for relaxing sounds. Everything is generated in the app
 * (no audio files, no internet): several sounds can play together, each with its own volume.
 */
object SoundEngine {
    enum class Sound(val icon: String, val ar: String, val en: String) {
        RAIN("🌧", "مطر", "Rain"),
        SEA("🌊", "أمواج البحر", "Sea waves"),
        WIND("🍃", "رياح", "Wind"),
        BIRDS("🐦", "طيور", "Birds"),
        STREAM("💧", "جدول ماء", "Stream"),
        FIRE("🔥", "نار هادئة", "Fireplace"),
        NIGHT("🦗", "ليل وصراصير", "Night crickets"),
        BROWN("🌫", "ضوضاء بنية (للنوم)", "Brown noise (sleep)"),
        DELTA("🎧", "نغمة دلتا للنوم العميق (سماعات)", "Delta tone for deep sleep (headphones)"),
    }

    private const val SR = 44100

    /** Target volume 0..1 of every active sound. */
    val levels = MutableStateFlow<Map<Sound, Float>>(emptyMap())
    val playing = MutableStateFlow(false)

    @Volatile private var masterTarget = 1f
    @Volatile private var masterStep = 0f
    @Volatile private var stopWhenSilent = false
    @Volatile private var running = false
    private var thread: Thread? = null

    fun setLevel(s: Sound, level: Float) {
        levels.value = if (level <= 0f) levels.value - s else levels.value + (s to level.coerceIn(0f, 1f))
        if (levels.value.isNotEmpty()) start()
    }

    fun toggle(s: Sound, defaultLevel: Float = 0.7f) = setLevel(s, if (levels.value.containsKey(s)) 0f else defaultLevel)

    /** Replaces the mix (used by presets). */
    fun setMix(mix: Map<Sound, Float>) {
        levels.value = mix.filterValues { it > 0f }
        if (levels.value.isNotEmpty()) start()
    }

    /** Smoothly changes the master volume to [target] over [ms] milliseconds. */
    fun fadeTo(target: Float, ms: Long, stopAtEnd: Boolean = false) {
        stopWhenSilent = stopAtEnd
        masterTarget = target.coerceIn(0f, 1f)
        masterStep = 1f / (ms.coerceAtLeast(50) * SR / 1000f)
    }

    fun stop() {
        running = false
        thread = null
        playing.value = false
        levels.value = emptyMap()
    }

    @Synchronized
    private fun start() {
        if (running) return
        running = true
        masterTarget = 1f
        masterStep = 1f / (2f * SR) // 2 s fade-in
        stopWhenSilent = false
        playing.value = true
        thread = Thread({ loop() }, "relax-audio").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    private fun loop() {
        val minBuf = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        @Suppress("DEPRECATION")
        val track = AudioTrack(
            AudioManager.STREAM_MUSIC, SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, 16384), AudioTrack.MODE_STREAM,
        )
        val gens = HashMap<Sound, Gen>()
        val frames = 1024
        val buf = ShortArray(frames * 2)
        var master = 0f
        try {
            track.play()
            while (running) {
                val lv = levels.value
                // keep generators of sounds being faded out until they are silent
                for (s in lv.keys) gens.getOrPut(s) { newGen(s) }
                val active = gens.entries.toList()
                for (i in 0 until frames) {
                    var l = 0f
                    var r = 0f
                    for ((s, g) in active) {
                        val target = lv[s] ?: 0f
                        g.gain += (target - g.gain) * 0.0005f
                        if (g.gain < 0.0005f && target == 0f) continue
                        g.next()
                        l += g.l * g.gain
                        r += g.r * g.gain
                    }
                    master = when {
                        master < masterTarget -> minOf(masterTarget, master + masterStep)
                        master > masterTarget -> maxOf(masterTarget, master - masterStep)
                        else -> master
                    }
                    buf[2 * i] = (soft(l * master) * 32000f).toInt().toShort()
                    buf[2 * i + 1] = (soft(r * master) * 32000f).toInt().toShort()
                }
                gens.entries.removeAll { (s, g) -> lv[s] == null && g.gain < 0.0005f }
                track.write(buf, 0, buf.size)
                if (stopWhenSilent && master <= 0.0001f) { stop(); break }
                if (lv.isEmpty() && gens.isEmpty()) { running = false; playing.value = false }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    private fun soft(x: Float): Float = x / (1f + abs(x))

    private fun newGen(s: Sound): Gen = when (s) {
        Sound.RAIN -> Rain()
        Sound.SEA -> Sea()
        Sound.WIND -> Wind()
        Sound.BIRDS -> Birds()
        Sound.STREAM -> Stream()
        Sound.FIRE -> Fire()
        Sound.NIGHT -> Night()
        Sound.BROWN -> BrownNoise()
        Sound.DELTA -> Delta()
    }

    // ===================================================================== generators
    private abstract class Gen {
        var l = 0f
        var r = 0f
        var gain = 0f
        var t = 0L
        val rnd = Random(System.nanoTime())
        fun white() = rnd.nextFloat() * 2f - 1f
        val sec: Float get() = t.toFloat() / SR
        abstract fun step()
        fun next() { step(); t++ }
    }

    private class Pink(private val rnd: Random) {
        private var b0 = 0f; private var b1 = 0f; private var b2 = 0f; private var b3 = 0f
        private var b4 = 0f; private var b5 = 0f; private var b6 = 0f
        fun next(): Float {
            val w = rnd.nextFloat() * 2f - 1f
            b0 = 0.99886f * b0 + w * 0.0555179f
            b1 = 0.99332f * b1 + w * 0.0750759f
            b2 = 0.96900f * b2 + w * 0.1538520f
            b3 = 0.86650f * b3 + w * 0.3104856f
            b4 = 0.55000f * b4 + w * 0.5329522f
            b5 = -0.7616f * b5 - w * 0.0168980f
            val p = b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362f
            b6 = w * 0.115926f
            return p * 0.11f
        }
    }

    private class Brown(private val rnd: Random) {
        private var last = 0f
        fun next(): Float {
            last = (last + 0.02f * (rnd.nextFloat() * 2f - 1f)) / 1.02f
            return last * 3.5f
        }
    }

    private class Rain : Gen() {
        val pl = Pink(rnd); val pr = Pink(rnd)
        var drop = 0f; var pan = 0.5f; var prevW = 0f
        override fun step() {
            if (drop < 0.01f && rnd.nextFloat() < 0.0009f) { drop = 0.4f + rnd.nextFloat() * 0.6f; pan = rnd.nextFloat() }
            val w = white(); val hp = w - prevW; prevW = w
            val d = hp * drop * 0.35f
            drop *= 0.994f
            l = pl.next() * 0.55f + d * (1f - pan)
            r = pr.next() * 0.55f + d * pan
        }
    }

    private class Sea : Gen() {
        val bl = Brown(rnd); val br = Brown(rnd)
        val period = 7.5f + rnd.nextFloat() * 3f
        var prevW = 0f
        private fun env(phase: Float): Float {
            val s = (sin(2 * PI * (sec / period + phase)).toFloat() + 1f) / 2f
            return 0.2f + 0.8f * s * s
        }
        override fun step() {
            val el = env(0f); val er = env(0.06f)
            val w = white(); val hiss = (w - prevW) * 0.5f; prevW = w
            l = bl.next() * 0.55f * el + hiss * el * el * el * 0.25f
            r = br.next() * 0.55f * er + hiss * er * er * er * 0.25f
        }
    }

    private class Wind : Gen() {
        val p = Pink(rnd)
        var low = 0f; var band = 0f; var low2 = 0f; var band2 = 0f
        override fun step() {
            val fc = 320f + 220f * sin(2 * PI * sec / 13.0).toFloat() + 140f * sin(2 * PI * sec / 5.3).toFloat()
            val f = 2f * sin(PI * fc / SR).toFloat()
            val x = p.next()
            val high = x - low - 0.6f * band; band += f * high; low += f * band
            val fc2 = fc * 1.4f
            val f2 = 2f * sin(PI * fc2 / SR).toFloat()
            val high2 = x - low2 - 0.6f * band2; band2 += f2 * high2; low2 += f2 * band2
            val gust = 0.55f + 0.45f * sin(2 * PI * sec / 11.0).toFloat()
            l = band * 2.2f * gust
            r = band2 * 2.0f * gust
        }
    }

    private class Birds : Gen() {
        val amb = Pink(rnd)
        var wait = SR / 2; var chirpsLeft = 0; var len = 0; var pos = 0
        var f0 = 3000f; var f1 = 4000f; var phase = 0.0; var pan = 0.5f
        override fun step() {
            var s = 0f
            if (len == 0) {
                if (--wait <= 0) {
                    if (chirpsLeft <= 0) { chirpsLeft = 2 + rnd.nextInt(6); pan = rnd.nextFloat(); f0 = 2400f + rnd.nextFloat() * 2600f }
                    len = (SR * (0.04f + rnd.nextFloat() * 0.11f)).toInt(); pos = 0
                    f1 = f0 + (rnd.nextFloat() - 0.4f) * 2200f
                    chirpsLeft--
                }
            } else {
                val p = pos.toFloat() / len
                val f = f0 + (f1 - f0) * p
                phase += 2 * PI * f / SR
                val e = sin(PI * p).toFloat()
                s = sin(phase).toFloat() * e * e * 0.3f
                if (++pos >= len) {
                    len = 0
                    wait = if (chirpsLeft > 0) (SR * (0.05f + rnd.nextFloat() * 0.12f)).toInt()
                    else (SR * (0.8f + rnd.nextFloat() * 3.2f)).toInt()
                }
            }
            val a = amb.next() * 0.05f
            l = s * (1f - pan) + a
            r = s * pan + a
        }
    }

    private class Stream : Gen() {
        val pl = Pink(rnd); val pr = Pink(rnd)
        var prevL = 0f; var prevR = 0f; var mod = 0.7f
        override fun step() {
            mod = (mod + (rnd.nextFloat() - 0.5f) * 0.002f).coerceIn(0.5f, 1f)
            val a = pl.next(); val b = pr.next()
            l = (a - prevL * 0.9f) * 1.1f * mod; prevL = a
            r = (b - prevR * 0.9f) * 1.1f * (1.5f - mod); prevR = b
        }
    }

    private class Fire : Gen() {
        val b = Brown(rnd)
        var crack = 0f; var pan = 0.5f
        override fun step() {
            if (crack < 0.01f && rnd.nextFloat() < 0.0005f) { crack = 0.3f + rnd.nextFloat() * 0.7f; pan = rnd.nextFloat() }
            val c = white() * crack * 0.5f
            crack *= 0.975f
            val rumble = b.next() * 0.35f * (0.85f + 0.15f * sin(2 * PI * sec / 3.7).toFloat())
            l = rumble + c * (1f - pan)
            r = rumble + c * pan
        }
    }

    private class Night : Gen() {
        val b = Brown(rnd)
        var ph1 = 0.0; var ph2 = 0.0
        override fun step() {
            fun cricket(rate: Float, offset: Float, phase: Double): Float {
                val cycle = ((sec + offset) % rate) / rate
                if (cycle > 0.35f) return 0f
                val trill = if (((sec * 32f) % 1f) < 0.5f) 1f else 0f
                return sin(phase).toFloat() * trill * 0.12f * sin(PI * cycle / 0.35f).toFloat()
            }
            ph1 += 2 * PI * 4600 / SR; ph2 += 2 * PI * 4150 / SR
            val bg = b.next() * 0.12f
            l = cricket(1.1f, 0f, ph1) + bg
            r = cricket(1.37f, 0.4f, ph2) + bg
        }
    }

    private class BrownNoise : Gen() {
        val bl = Brown(rnd); val br = Brown(rnd)
        override fun step() { l = bl.next() * 0.6f; r = br.next() * 0.6f }
    }

    /** 200 Hz left / 202.5 Hz right: the ear perceives a slow 2.5 Hz beat (delta range). */
    private class Delta : Gen() {
        var pl = 0.0; var pr = 0.0; var pad = 0.0
        override fun step() {
            pl += 2 * PI * 200.0 / SR; pr += 2 * PI * 202.5 / SR; pad += 2 * PI * 100.0 / SR
            val soft = sin(pad).toFloat() * 0.06f
            l = sin(pl).toFloat() * 0.22f + soft
            r = sin(pr).toFloat() * 0.22f + soft
        }
    }
}
