package com.newlife.tv.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newlife.tv.data.AppSettings
import com.newlife.tv.data.L
import com.newlife.tv.data.ProfileStore

@Composable
fun SettingsPage(vm: MainViewModel) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NButton(L.t("رجوع", "Back"), Icons.Filled.ArrowBack) { vm.back() }
            SectionTitle(L.t("الإعدادات", "Settings"))
        }
        SettingsContent(vm)
    }
}

@Composable
private fun <T> ChoiceRow(title: String, options: List<Pair<T, String>>, current: T, onPick: (T) -> Unit) {
    Text(title, color = Palette.Text2, modifier = Modifier.padding(top = 12.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == current,
                onClick = { onPick(value) },
                label = { Text(label) },
                modifier = Modifier.focusRing(RoundedCornerShape(8.dp)),
            )
        }
    }
}

@Composable
fun SettingsContent(vm: MainViewModel) {
    val s by ProfileStore.settings.collectAsState()
    fun update(t: (AppSettings) -> AppSettings) = ProfileStore.updateSettings(t)
    val yesNo = listOf(true to L.t("نعم", "Yes"), false to L.t("لا", "No"))

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        ChoiceRow(L.t("اللغة / Language", "Language / اللغة"), listOf(true to "العربية", false to "English"), s.arabic) { v -> update { it.copy(arabic = v) } }
        ChoiceRow(
            L.t("مشغل الفيديو", "Video player"),
            listOf(
                "auto" to L.t("تلقائي (ExoPlayer ثم VLC) — مستحسن", "Auto (ExoPlayer, then VLC) — recommended"),
                "vlc" to L.t("VLC (أقوى توافق مع القنوات)", "VLC (best IPTV compatibility)"),
                "exo" to "ExoPlayer",
                "external" to L.t("مشغل خارجي مثبت", "Installed external player"),
            ),
            if (s.externalPlayer) "external" else s.playerEngine,
        ) { v -> update { it.copy(playerEngine = v, externalPlayer = false) } }
        ChoiceRow(
            L.t("المشغل الخارجي المفضل", "Preferred external player"),
            com.newlife.tv.player.ExternalPlayer.KNOWN,
            s.externalPackage,
        ) { v -> update { it.copy(externalPackage = v) } }
        ChoiceRow(L.t("صورة في صورة تلقائياً عند الخروج", "Auto picture-in-picture on Home"), yesNo, s.autoPip) { v -> update { it.copy(autoPip = v) } }
        ChoiceRow(L.t("حجم التخزين المؤقت (ثانية)", "Buffer size (seconds)"), listOf(3, 8, 15, 30, 60).map { it to "$it" }, s.bufferSeconds) { v -> update { it.copy(bufferSeconds = v) } }
        ChoiceRow(
            L.t("أبعاد الصورة الافتراضية", "Default aspect"),
            listOf(0 to L.t("ملاءمة", "Fit"), 1 to L.t("ملء الشاشة", "Stretch"), 2 to L.t("تكبير", "Zoom"), 3 to L.t("عرض كامل", "Fit width")),
            s.resizeMode,
        ) { v -> update { it.copy(resizeMode = v) } }

        SectionTitle(L.t("النسخ الاحتياطي", "Backup"), Modifier.padding(top = 16.dp))
        Text(L.t("احفظ كل الاشتراكات (مع MAC والبيانات) والمفضلة في ملف، واسترجعها على جهاز آخر.",
            "Save all subscriptions (incl. MAC & device data) and favorites to a file and restore them on another device."), color = Palette.Text2)
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { BackupButtons(vm) }

        SectionTitle(L.t("حول التطبيق", "About"), Modifier.padding(top = 16.dp))
        Text("NEW LIFE TV 1.0.0", fontSize = 16.sp)
        Text(
            L.t(
                "مشغل IPTV يدعم بورتالات MAG/Stalker و Xtream Codes وقوائم M3U. لا يحتوي التطبيق على أي قنوات أو محتوى؛ استخدمه فقط مع اشتراكات تملك حق استخدامها.",
                "IPTV player for MAG/Stalker portals, Xtream Codes and M3U playlists. The app ships with no channels or content; use it only with subscriptions you are entitled to.",
            ),
            color = Palette.Text2,
        )
        Spacer(Modifier.height(40.dp))
    }
}
