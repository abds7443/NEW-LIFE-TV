package com.newlife.tv.net

import android.content.Context
import com.newlife.tv.data.AccountInfo
import com.newlife.tv.data.Category
import com.newlife.tv.data.ContentKind
import com.newlife.tv.data.DeviceIdentity
import com.newlife.tv.data.EpgProgram
import com.newlife.tv.data.Episode
import com.newlife.tv.data.MediaEntry
import com.newlife.tv.data.PlayRequest
import com.newlife.tv.data.Profile
import com.newlife.tv.data.ProfileType
import com.newlife.tv.data.Season

/** Common interface for Stalker/MAG portals, Xtream Codes and M3U playlists. */
interface IptvProvider {
    val profile: Profile

    suspend fun connect(): AccountInfo
    suspend fun categories(kind: ContentKind): List<Category>
    suspend fun entries(kind: ContentKind, categoryId: String): List<MediaEntry>
    suspend fun seasons(series: MediaEntry): List<Season>
    suspend fun resolve(entry: MediaEntry): PlayRequest
    suspend fun resolveEpisode(series: MediaEntry, season: Season, episode: Episode): PlayRequest
    suspend fun epg(entry: MediaEntry): List<EpgProgram>
    suspend fun catchup(entry: MediaEntry, program: EpgProgram): PlayRequest? = null
    suspend fun keepAlive() {}

    val playerUa: String
        get() = Session.uaOverride ?: profile.playerUserAgent.ifBlank {
            if (profile.type == ProfileType.STALKER) profile.userAgent.ifBlank { DeviceIdentity.DEFAULT_STB_UA }
            else DeviceIdentity.DEFAULT_PLAYER_UA
        }

    companion object {
        const val ALL = "*"
    }
}

object ProviderFactory {
    fun create(ctx: Context, p: Profile): IptvProvider = when (p.type) {
        ProfileType.STALKER -> StalkerProvider(p)
        ProfileType.XTREAM -> XtreamProvider(p)
        ProfileType.M3U -> M3uProvider(ctx.applicationContext, p)
    }
}

object Session {
    @Volatile var provider: IptvProvider? = null
    @Volatile var zapList: List<MediaEntry> = emptyList()
    /** A User-Agent found to work for the current subscription during this session. */
    @Volatile var uaOverride: String? = null
}
