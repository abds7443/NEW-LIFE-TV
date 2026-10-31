package com.newlife.tv.data

import java.util.TimeZone
import java.util.UUID

enum class ProfileType { STALKER, XTREAM, M3U }

enum class ContentKind { LIVE, VOD, SERIES }

/**
 * One subscription (portal). Every field has a default so Gson can use the
 * generated no-arg constructor and old backups keep working.
 */
data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val type: ProfileType = ProfileType.STALKER,
    val nickname: String = "",
    val portalUrl: String = "",
    val username: String = "",
    val password: String = "",
    // ---- STB / MAG identity (Stalker portals) ----
    val mac: String = "",
    val serial: String = "",
    val deviceId1: String = "",
    val deviceId2: String = "",
    val signature: String = "",
    val stbModel: String = "MAG250",
    val userAgent: String = DeviceIdentity.DEFAULT_STB_UA,
    val playerUserAgent: String = "",
    val timezone: String = TimeZone.getDefault().id,
    val language: String = "en",
    // ---- misc ----
    val epgUrl: String = "",
    val outputFormat: String = "ts",
    val lastUsed: Long = 0L,
) {
    fun displayName(): String = nickname.ifBlank { portalUrl.ifBlank { "Portal" } }
}

data class Category(val id: String, val title: String, val kind: ContentKind)

data class MediaEntry(
    val id: String = "",
    val title: String = "",
    val logo: String = "",
    val categoryId: String = "",
    val kind: ContentKind = ContentKind.LIVE,
    val number: Int = 0,
    val cmd: String = "",
    val epgId: String = "",
    val extension: String = "",
    val plot: String = "",
    val rating: String = "",
    val year: String = "",
    val catchupDays: Int = 0,
    val catchupType: String = "",
    val catchupSource: String = "",
    val userAgent: String = "",
    val referer: String = "",
    val drmScheme: String = "",
    val drmLicense: String = "",
)

data class Season(
    val id: String,
    val title: String,
    val number: Int,
    val episodes: List<Episode>,
    val cmd: String = "",
)

data class Episode(
    val id: String,
    val title: String,
    val number: Int,
    val cmd: String = "",
    val extension: String = "",
    val plot: String = "",
    val image: String = "",
)

data class EpgProgram(
    val id: String = "",
    val title: String = "",
    val description: String = "",
    val start: Long = 0L,
    val end: Long = 0L,
) {
    val isNow: Boolean get() = System.currentTimeMillis() in start until end
    val isPast: Boolean get() = end <= System.currentTimeMillis()
    val progress: Float
        get() = if (end <= start) 0f
        else ((System.currentTimeMillis() - start).toFloat() / (end - start)).coerceIn(0f, 1f)
}

data class AccountInfo(val fields: List<Pair<String, String>>)

data class PlayRequest(
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
    val userAgent: String = "",
    val title: String = "",
    val isLive: Boolean = false,
    val drmScheme: String = "",
    val drmLicense: String = "",
)

data class AppSettings(
    val arabic: Boolean = true,
    val autoPip: Boolean = true,
    val externalPlayer: Boolean = false,
    /** auto = ExoPlayer then VLC, exo, vlc, external */
    val playerEngine: String = "auto",
    /** package of the preferred external player ("" = ask every time) */
    val externalPackage: String = "",
    val recordStorageIndex: Int = 0,
    val bufferSeconds: Int = 15,
    val resizeMode: Int = 0,
)

data class Favorite(val profileId: String = "", val entry: MediaEntry = MediaEntry())

data class ScheduledRecording(
    val id: String = UUID.randomUUID().toString(),
    val profileId: String = "",
    val entry: MediaEntry = MediaEntry(),
    val title: String = "",
    val startMs: Long = 0L,
    val endMs: Long = 0L,
    val status: String = STATUS_SCHEDULED,
) {
    companion object {
        const val STATUS_SCHEDULED = "scheduled"
        const val STATUS_RECORDING = "recording"
        const val STATUS_DONE = "done"
        const val STATUS_FAILED = "failed"
        const val STATUS_STOPPED = "stopped"
    }
}

data class Backup(
    val app: String = "NEW LIFE TV",
    val version: Int = 1,
    val profiles: List<Profile> = emptyList(),
    val favorites: List<Favorite> = emptyList(),
    val settings: AppSettings = AppSettings(),
)
