package com.newlife.tv.net

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun request(url: String, headers: Map<String, String>): Request {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> if (v.isNotEmpty()) b.header(k, v) }
        return b.build()
    }

    fun getString(url: String, headers: Map<String, String> = emptyMap(), c: OkHttpClient = client): String {
        c.newCall(request(url, headers)).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} ${r.message}")
            return r.body?.string() ?: ""
        }
    }

    fun getBytes(url: String, headers: Map<String, String> = emptyMap(), c: OkHttpClient = client): ByteArray {
        c.newCall(request(url, headers)).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} ${r.message}")
            return r.body?.bytes() ?: ByteArray(0)
        }
    }

    /** Resolves a (possibly relative) reference against a base URL. */
    fun resolve(base: String, ref: String): String {
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
        return base.toHttpUrlOrNull()?.resolve(ref)?.toString() ?: ref
    }
}

fun parseJson(text: String): JsonElement? =
    runCatching { JsonParser.parseString(text.trim().removePrefix("﻿")) }.getOrNull()

fun JsonElement?.obj(): JsonObject? = if (this != null && this.isJsonObject) this.asJsonObject else null
fun JsonElement?.arr(): JsonArray? = if (this != null && this.isJsonArray) this.asJsonArray else null

fun JsonObject?.s(key: String): String {
    val e = this?.get(key) ?: return ""
    return when {
        e.isJsonNull -> ""
        e.isJsonPrimitive -> e.asString
        else -> e.toString()
    }
}

fun JsonObject?.i(key: String, def: Int = 0): Int = s(key).toDoubleOrNull()?.toInt() ?: def
fun JsonObject?.l(key: String): Long = s(key).toDoubleOrNull()?.toLong() ?: 0L
