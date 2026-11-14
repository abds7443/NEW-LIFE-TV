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
import com.newlife.tv.data.L
import com.newlife.tv.data.ProfileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

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
        ProfileType.ARCHIVE -> ArchiveProvider(p)
    }
}

object Session {
    @Volatile var provider: IptvProvider? = null
    @Volatile var zapList: List<MediaEntry> = emptyList()
    /** A User-Agent found to work for the current subscription during this session. */
    @Volatile var uaOverride: String? = null
    /** Server (profile id) of each item of [zapList]; empty = all belong to the current server. */
    @Volatile var zapOwners: List<String> = emptyList()
    /** Channel list shown on the home screen, offered as a source in multi-view. */
    @Volatile var multiSource: List<MediaEntry> = emptyList()
}

/**
 * Keeps several servers connected at the same time, so favourites from any of them play
 * without switching subscription, and zapping can move from one server to another.
 */
object ProviderPool {
    private val map = ConcurrentHashMap<String, IptvProvider>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    fun put(p: IptvProvider) { map[p.profile.id] = p }
    fun peek(id: String?): IptvProvider? = id?.let { map[it] }
    fun remove(id: String) { map.remove(id) }
    fun connectedCount(): Int = map.size

    /** Connected provider of server [id], connecting it first if needed (or again when [fresh]). */
    suspend fun get(ctx: Context, id: String, fresh: Boolean = false): IptvProvider {
        if (!fresh) map[id]?.let { return it }
        val m = locks.getOrPut(id) { Mutex() }
        return m.withLock {
            val cur = map[id]
            if (!fresh && cur != null) return@withLock cur
            val profile = ProfileStore.profile(id) ?: throw IOException(L.t("السيرفر غير موجود", "Server not found"))
            val p = ProviderFactory.create(ctx, profile)
            p.connect()
            map[id] = p
            p
        }
    }

    /** Link of [e] on server [id]; when the session expired the server is reconnected once. */
    suspend fun resolve(ctx: Context, id: String, e: MediaEntry): PlayRequest {
        val p = get(ctx, id)
        return try {
            p.resolve(e)
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            get(ctx, id, fresh = true).resolve(e)
        }
    }
}
