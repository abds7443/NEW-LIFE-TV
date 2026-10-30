package com.newlife.tv.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tiny bilingual helper: every UI string is written in Arabic and English. */
object L {
    var arabic by mutableStateOf(true)
    fun t(ar: String, en: String): String = if (arabic) ar else en

    fun hm(ms: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))
    fun day(ms: Long): String = SimpleDateFormat("EEE dd/MM", if (arabic) Locale("ar") else Locale.US).format(Date(ms))
    fun dateTime(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))

    fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
        else -> "${bytes / 1024} KB"
    }

    fun quality(height: Int): String = when {
        height >= 4320 -> "8K"
        height >= 2160 -> "4K UHD"
        height >= 1440 -> "QHD"
        height >= 1080 -> "FHD"
        height >= 720 -> "HD"
        height > 0 -> "SD"
        else -> ""
    }
}
