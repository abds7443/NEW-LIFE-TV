package com.newlife.tv.data

import java.security.MessageDigest
import kotlin.random.Random

/** Helpers for the STB identity values a Stalker/MAG portal checks. */
object DeviceIdentity {
    const val DEFAULT_STB_UA =
        "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"
    /** The phone's own Android player User-Agent (what MX Player and most players send). */
    val ANDROID_UA: String
        get() = System.getProperty("http.agent")?.takeIf { it.isNotBlank() }
            ?: "Dalvik/2.1.0 (Linux; U; Android ${android.os.Build.VERSION.RELEASE}; ${android.os.Build.MODEL} Build/${android.os.Build.ID})"

    val DEFAULT_PLAYER_UA: String get() = ANDROID_UA

    /** User-Agents tried one after another when a server refuses a stream. */
    fun playerUaCandidates(): List<String> = listOf(
        ANDROID_UA,
        "VLC/3.0.20 LibVLC/3.0.20",
        "Lavf/60.16.100",
        "Mozilla/5.0 (Linux; Android 13; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36",
        "IPTVSmartersPlayer",
        "okhttp/4.12.0",
        DEFAULT_STB_UA,
    ).distinct()

    val STB_MODELS = listOf(
        "MAG200", "MAG245", "MAG250", "MAG254", "MAG256", "MAG270", "MAG275",
        "MAG322", "MAG323", "MAG324", "MAG349", "MAG350", "MAG351", "MAG352",
        "MAG420", "MAG424", "MAG520", "MAG522", "MAG524", "AuraHD", "WR320",
    )

    val USER_AGENT_PRESETS = listOf(
        DEFAULT_STB_UA,
        "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG254 stbapp ver: 4 rev: 2721 Mobile Safari/533.3",
        "Mozilla/5.0 (Linux; Android 9; MAG524) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/80.0 Safari/537.36",
        "VLC/3.0.20 LibVLC/3.0.20",
        "okhttp/4.12.0",
        DEFAULT_PLAYER_UA,
    )

    private val MAC_REGEX = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")

    /** Accepts 001A79AABBCC, 00-1a-79-aa-bb-cc, etc. and returns 00:1A:79:AA:BB:CC. */
    fun normalizeMac(input: String): String {
        val hex = input.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }
        if (hex.length != 12) return input.trim().uppercase()
        return hex.chunked(2).joinToString(":")
    }

    fun isValidMac(mac: String): Boolean = MAC_REGEX.matches(mac.trim().uppercase())

    fun randomMac(prefix: String = "00:1A:79"): String {
        val tail = (1..3).joinToString(":") { "%02X".format(Random.nextInt(0, 256)) }
        return "$prefix:$tail"
    }

    fun serialFromMac(mac: String): String = md5(mac.uppercase()).uppercase().take(13)

    fun deviceIdFromMac(mac: String): String = sha256(mac.uppercase()).uppercase()

    fun signature(mac: String, serial: String, deviceId: String): String =
        sha256(mac.uppercase() + serial + deviceId).uppercase()

    /** Fills empty identity fields (or all of them when [overwrite]) from the MAC. */
    fun derive(p: Profile, overwrite: Boolean): Profile {
        val mac = normalizeMac(p.mac)
        if (!isValidMac(mac)) return p
        val sn = if (overwrite || p.serial.isBlank()) serialFromMac(mac) else p.serial
        val d1 = if (overwrite || p.deviceId1.isBlank()) deviceIdFromMac(mac) else p.deviceId1
        val d2 = if (overwrite || p.deviceId2.isBlank()) d1 else p.deviceId2
        val sig = if (overwrite || p.signature.isBlank()) signature(mac, sn, d1) else p.signature
        return p.copy(mac = mac, serial = sn, deviceId1 = d1, deviceId2 = d2, signature = sig)
    }

    fun randomHex(len: Int): String =
        (1..len).map { "0123456789ABCDEF"[Random.nextInt(16)] }.joinToString("")

    fun md5(s: String) = hash("MD5", s)
    fun sha1(s: String) = hash("SHA-1", s)
    fun sha256(s: String) = hash("SHA-256", s)

    private fun hash(alg: String, s: String): String =
        MessageDigest.getInstance(alg).digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** The device's own hardware MAC (works on most TV boxes; phones since Android 6 hide it). */
    fun realDeviceMac(): String {
        for (n in listOf("eth0", "wlan0")) {
            val v = runCatching { java.io.File("/sys/class/net/$n/address").readText().trim().uppercase() }.getOrNull()
            if (v != null && v.length == 17 && v != "02:00:00:00:00:00" && v != "00:00:00:00:00:00") return v
        }
        runCatching {
            for (ni in java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                val hw = ni.hardwareAddress ?: continue
                if (hw.size != 6) continue
                val v = hw.joinToString(":") { "%02X".format(it) }
                if (v != "02:00:00:00:00:00" && v != "00:00:00:00:00:00") return v
            }
        }
        return L.t("غير متاح على هذا الجهاز", "Not available on this device")
    }
}
