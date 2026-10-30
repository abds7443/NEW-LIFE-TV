package com.newlife.tv.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow

/** Persists subscriptions, favourites, settings and scheduled recordings. */
object ProfileStore {
    private lateinit var prefs: SharedPreferences
    private val gson = Gson()

    val profiles = MutableStateFlow<List<Profile>>(emptyList())
    val favorites = MutableStateFlow<List<Favorite>>(emptyList())
    val settings = MutableStateFlow(AppSettings())
    val schedules = MutableStateFlow<List<ScheduledRecording>>(emptyList())

    @Synchronized
    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("newlife_tv", Context.MODE_PRIVATE)
        profiles.value = read("profiles", object : TypeToken<List<Profile>>() {}) ?: emptyList()
        favorites.value = read("favorites", object : TypeToken<List<Favorite>>() {}) ?: emptyList()
        settings.value = read("settings", object : TypeToken<AppSettings>() {}) ?: AppSettings()
        schedules.value = read("schedules", object : TypeToken<List<ScheduledRecording>>() {}) ?: emptyList()
        L.arabic = settings.value.arabic
    }

    private fun <T> read(key: String, type: TypeToken<T>): T? {
        val json = prefs.getString(key, null) ?: return null
        return runCatching { gson.fromJson<T>(json, type.type) }.getOrNull()
    }

    private fun write(key: String, value: Any) {
        prefs.edit().putString(key, gson.toJson(value)).apply()
    }

    // ---------------- profiles ----------------
    fun saveProfile(p: Profile) {
        val list = profiles.value.toMutableList()
        val i = list.indexOfFirst { it.id == p.id }
        if (i >= 0) list[i] = p else list.add(p)
        profiles.value = list
        write("profiles", list)
    }

    fun deleteProfile(id: String) {
        profiles.value = profiles.value.filterNot { it.id == id }
        favorites.value = favorites.value.filterNot { it.profileId == id }
        write("profiles", profiles.value)
        write("favorites", favorites.value)
    }

    fun touch(id: String) {
        profiles.value.firstOrNull { it.id == id }?.let { saveProfile(it.copy(lastUsed = System.currentTimeMillis())) }
    }

    fun profile(id: String): Profile? = profiles.value.firstOrNull { it.id == id }

    // ---------------- favourites ----------------
    private fun key(e: MediaEntry) = "${e.kind}|${e.id}"

    fun favoriteKeys(profileId: String): Set<String> =
        favorites.value.filter { it.profileId == profileId }.map { key(it.entry) }.toSet()

    fun isFavorite(profileId: String, e: MediaEntry) = favorites.value.any { it.profileId == profileId && key(it.entry) == key(e) }

    fun toggleFavorite(profileId: String, e: MediaEntry): Boolean {
        val exists = isFavorite(profileId, e)
        favorites.value = if (exists) favorites.value.filterNot { it.profileId == profileId && key(it.entry) == key(e) }
        else favorites.value + Favorite(profileId, e)
        write("favorites", favorites.value)
        return !exists
    }

    fun keyOf(e: MediaEntry) = key(e)

    // ---------------- settings ----------------
    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        settings.value = transform(settings.value)
        L.arabic = settings.value.arabic
        write("settings", settings.value)
    }

    // ---------------- schedules ----------------
    fun addSchedule(s: ScheduledRecording) {
        schedules.value = schedules.value + s
        write("schedules", schedules.value)
    }

    fun updateSchedule(id: String, status: String) {
        schedules.value = schedules.value.map { if (it.id == id) it.copy(status = status) else it }
        write("schedules", schedules.value)
    }

    fun removeSchedule(id: String) {
        schedules.value = schedules.value.filterNot { it.id == id }
        write("schedules", schedules.value)
    }

    // ---------------- backup ----------------
    fun exportJson(): String =
        gson.toJson(Backup(profiles = profiles.value, favorites = favorites.value, settings = settings.value))

    /** Merges a backup; returns number of profiles imported. */
    fun importJson(json: String): Int {
        val b = gson.fromJson(json, Backup::class.java) ?: return 0
        var n = 0
        b.profiles.forEach { saveProfile(it); n++ }
        val merged = (favorites.value + b.favorites).distinctBy { it.profileId + key(it.entry) }
        favorites.value = merged
        write("favorites", merged)
        return n
    }
}
