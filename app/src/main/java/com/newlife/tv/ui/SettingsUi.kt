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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
        ChoiceRow(L.t("إظهار الساعة والتاريخ أثناء المشاهدة", "Show clock & date while watching"), yesNo, s.playerClock) { v -> update { it.copy(playerClock = v) } }
        ChoiceRow(L.t("حجم التخزين المؤقت (ثانية)", "Buffer size (seconds)"), listOf(3, 8, 15, 30, 60).map { it to "$it" }, s.bufferSeconds) { v -> update { it.copy(bufferSeconds = v) } }
        ChoiceRow(
            L.t("أبعاد الصورة الافتراضية", "Default aspect"),
            listOf(0 to L.t("ملاءمة", "Fit"), 1 to L.t("ملء الشاشة", "Stretch"), 2 to L.t("تكبير", "Zoom"), 3 to L.t("عرض كامل", "Fit width")),
            s.resizeMode,
        ) { v -> update { it.copy(resizeMode = v) } }

        // ---------------- parental control ----------------
        SectionTitle(L.t("الرقابة الأبوية (Parental Control)", "Parental control"), Modifier.padding(top = 16.dp))
        Text(
            L.t("اضغط مطولاً على أي قناة أو مجموعة لقفلها أو إخفائها. القنوات والمجموعات المقفلة تحتاج الرقم السري لفتحها.",
                "Long-press any channel or group to lock or hide it. Locked items need the PIN to open."),
            color = Palette.Text2, fontSize = 12.sp,
        )
        var changePin by remember { mutableStateOf(false) }
        var hideDialog by remember { mutableStateOf(false) }
        val ctx2 = androidx.compose.ui.platform.LocalContext.current
        val bio = remember { biometricAvailable(ctx2) }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NButton(L.t("تغيير الرقم السري", "Change PIN")) { changePin = true }
            NButton(L.t("إخفاء الباقات", "Hide groups")) {
                if (vm.activeProfile == null) vm.toast = L.t("سجّل الدخول إلى اشتراك أولاً", "Log in to a subscription first")
                else vm.requireParental(L.t("إخفاء الباقات", "Hide groups")) { hideDialog = true }
            }
            NButton(L.t("إلغاء كل الأقفال", "Remove all locks")) { vm.clearAllLocks() }
        }
        Text(L.t("مقفل: ", "Locked: ") + vm.parental.locked.size + "   •   " + L.t("مخفي: ", "Hidden: ") + vm.parental.hidden.size, color = Palette.Text2, fontSize = 12.sp)
        ChoiceRow(L.t("قفل البرنامج عند الفتح", "Lock the app at start"), yesNo, s.appLockEnabled) { v -> if (v != s.appLockEnabled) vm.setAppLock(v) }
        if (s.appLockEnabled) {
            if (bio) ChoiceRow(L.t("الفتح ببصمة الإصبع أو الوجه", "Unlock with fingerprint or face"), yesNo, s.biometricEnabled) { v -> update { it.copy(biometricEnabled = v) } }
            else Text(L.t("هذا الجهاز لا يدعم البصمة أو الوجه، سيُستخدم الرقم السري.", "No fingerprint/face on this device; the PIN will be used."), color = Palette.Text2, fontSize = 12.sp)
        }
        if (changePin) ChangePinDialog(vm) { changePin = false }
        if (hideDialog) HideCategoriesDialog(vm) { hideDialog = false }

        // ---------------- channel check ----------------
        SectionTitle(L.t("فحص القنوات", "Channel check"), Modifier.padding(top = 16.dp))
        Text(L.t("زر \"فحص القنوات\" فوق قائمة القنوات يجرّب كل قنوات المجموعة. اختر ماذا يحدث للقنوات المتوقفة:",
            "The \"Check channels\" button above the channel list tests every channel of the group. Choose what happens to dead channels:"),
            color = Palette.Text2, fontSize = 12.sp)
        ChoiceRow(L.t("القنوات المتوقفة", "Dead channels"),
            listOf("mark" to L.t("تمييز ✅ / ❌", "Mark ✅ / ❌"), "hide" to L.t("إخفاء المتوقفة", "Hide dead ones")),
            s.deadChannelMode) { v -> vm.setDeadMode(v) }
        Row(Modifier.padding(vertical = 6.dp)) { NButton(L.t("مسح نتائج الفحص", "Clear check results")) { vm.clearHealth() } }

        if (!s.adminHidden) {
        VpnSection(vm)

        SectionTitle(L.t("النسخ الاحتياطي", "Backup"), Modifier.padding(top = 16.dp))
        Text(L.t("احفظ كل الاشتراكات (مع MAC والبيانات) والمفضلة في ملف، واسترجعها على جهاز آخر.",
            "Save all subscriptions (incl. MAC & device data) and favorites to a file and restore them on another device."), color = Palette.Text2)
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { BackupButtons(vm) }
        }

        SectionTitle(L.t("تحديث البرنامج", "App update"), Modifier.padding(top = 16.dp))
        val ctx = androidx.compose.ui.platform.LocalContext.current
        Text(L.t("النسخة الحالية: ", "Current version: ") + com.newlife.tv.net.AppUpdater.currentVersionName(ctx), color = Palette.Text2)
        vm.update?.let { Text(L.t("متوفر: ", "Available: ") + it.name, color = Palette.Gold) }
        vm.updateProgress?.let { androidx.compose.material3.LinearProgressIndicator(progress = { it }, modifier = Modifier.padding(vertical = 6.dp)) }
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NButton(L.t("التحقق من التحديثات", "Check for updates"), enabled = !vm.updateChecking) { vm.checkUpdate(silent = false) }
            if (vm.update != null) NButton(L.t("تحديث الآن", "Update now"), primary = true) { vm.downloadAndInstall(ctx) }
        }

        SectionTitle(L.t("حول التطبيق", "About"), Modifier.padding(top = 16.dp))
        Text("NEW LIFE TV", fontSize = 16.sp)
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

@Composable
fun UpdateDialog(vm: MainViewModel) {
    val r = vm.update ?: return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (vm.updateProgress == null) vm.updateDialog = false },
        title = { Text(L.t("يتوفر تحديث جديد", "A new update is available")) },
        text = {
            Column {
                Text(r.name, color = Palette.Gold)
                Text(L.t("النسخة الحالية: ", "Current: ") + com.newlife.tv.net.AppUpdater.currentVersionName(ctx), color = Palette.Text2)
                if (r.size > 0) Text(L.t("الحجم: ", "Size: ") + L.size(r.size), color = Palette.Text2)
                if (r.notes.isNotBlank()) Text(r.notes.take(400), color = Palette.Text2, fontSize = 13.sp)
                vm.updateProgress?.let {
                    androidx.compose.material3.LinearProgressIndicator(progress = { it }, modifier = Modifier.padding(top = 10.dp))
                    Text("${(it * 100).toInt()}%", color = Palette.Text2)
                }
                Text(
                    L.t("سيتم التثبيت فوق النسخة الحالية مع الاحتفاظ بالاشتراكات والإعدادات.",
                        "It installs over the current version and keeps your subscriptions and settings."),
                    color = Palette.Text2, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            NButton(L.t("تحديث الآن", "Update now"), primary = true, enabled = vm.updateProgress == null) { vm.downloadAndInstall(ctx) }
        },
        dismissButton = {
            NButton(L.t("لاحقاً", "Later"), enabled = vm.updateProgress == null) { vm.updateDialog = false }
        },
    )
}
