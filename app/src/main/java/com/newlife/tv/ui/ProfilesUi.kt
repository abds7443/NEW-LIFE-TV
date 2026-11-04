package com.newlife.tv.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newlife.tv.data.DeviceIdentity
import com.newlife.tv.data.L
import com.newlife.tv.data.Profile
import com.newlife.tv.data.ProfileStore
import com.newlife.tv.data.ProfileType

private fun typeLabel(t: ProfileType) = when (t) {
    ProfileType.STALKER -> L.t("بورتال MAG / Stalker", "MAG / Stalker portal")
    ProfileType.XTREAM -> "Xtream Codes"
    ProfileType.M3U -> L.t("قائمة M3U", "M3U playlist")
}

@Composable
fun BackupButtons(vm: MainViewModel) {
    val ctx = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(ProfileStore.exportJson().toByteArray()) }
        }.onSuccess { vm.toast = L.t("تم حفظ النسخة الاحتياطية", "Backup saved") }
            .onFailure { vm.toast = L.t("فشل الحفظ: ", "Save failed: ") + it.message }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
            ProfileStore.importJson(text)
        }.onSuccess { vm.toast = L.t("تمت استعادة $it اشتراك", "Restored $it subscriptions") }
            .onFailure { vm.toast = L.t("ملف غير صالح: ", "Invalid file: ") + it.message }
    }
    NButton(L.t("نسخ احتياطي", "Backup"), Icons.Filled.Backup) { exportLauncher.launch("newlife_tv_backup.json") }
    NButton(L.t("استعادة", "Restore"), Icons.Filled.Restore) { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ProfilesScreen(vm: MainViewModel) {
    val profiles by ProfileStore.profiles.collectAsState()
    var confirmDelete by remember { mutableStateOf<Profile?>(null) }
    var showFree by remember { mutableStateOf(false) }
    val hide = adminHidden()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(20_000); now = System.currentTimeMillis() } }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("NEW LIFE ", fontSize = 32.sp, fontWeight = FontWeight.Black, color = Color.White)
                    Text("TV", fontSize = 32.sp, fontWeight = FontWeight.Black, color = Palette.Gold)
                }
                Text(L.t("اشتراكاتك — اختر اشتراكاً لتسجيل الدخول", "Your subscriptions — pick one to log in"), color = Palette.Text2)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(L.hm(now), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.Text2)
                Text(L.fullDate(now), fontSize = 12.sp, color = Palette.Text2)
            }
        }
        Spacer(Modifier.height(12.dp))
        // Buttons wrap onto several lines so every one is visible on phones in portrait.
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!hide) NButton(L.t("إضافة اشتراك", "Add subscription"), Icons.Filled.Add, primary = true) { vm.push(Screen.Edit(null)) }
            NButton(L.t("باقات مجانية", "Free packages"), Icons.Filled.Add) { showFree = true }
            if (!hide) BackupButtons(vm)
            NButton(L.t("الإعدادات", "Settings"), Icons.Filled.Settings) { vm.push(Screen.Settings) }
            NButton(if (hide) L.t("إظهار قوائم التعديل", "Show edit menus") else L.t("إخفاء قوائم التعديل", "Hide edit menus"),
                Icons.Filled.AdminPanelSettings) { vm.toggleAdminMode() }
            NButton(if (L.arabic) "English" else "العربية") { ProfileStore.updateSettings { it.copy(arabic = !it.arabic) } }
        }
        Spacer(Modifier.height(12.dp))

        if (vm.connecting) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.Surface).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(12.dp))
                Text(L.t("جارٍ الاتصال بـ ", "Connecting to ") + vm.connectingName + " …", Modifier.weight(1f))
                NButton(L.t("إلغاء", "Cancel"), Icons.Filled.Close, danger = true) { vm.cancelConnect() }
            }
            Spacer(Modifier.height(8.dp))
        }
        vm.error?.let {
            Text("⚠ $it", color = Palette.Danger, modifier = Modifier.padding(vertical = 6.dp))
        }

        if (profiles.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(L.t("لا توجد اشتراكات بعد", "No subscriptions yet"), fontSize = 20.sp)
                    Text(
                        L.t("أضف بورتال MAG (رابط + MAC) أو حساب Xtream أو رابط M3U", "Add a MAG portal (URL + MAC), an Xtream account or an M3U link"),
                        color = Palette.Text2,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!hide) NButton(L.t("إضافة اشتراك", "Add subscription"), Icons.Filled.Add, primary = true) { vm.push(Screen.Edit(null)) }
                        NButton(L.t("باقات مجانية", "Free packages"), Icons.Filled.Add) { showFree = true }
                    }
                }
            }
        } else {
            LazyVerticalGrid(GridCells.Adaptive(330.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(profiles.sortedByDescending { it.lastUsed }) { p ->
                    ProfileCard(p, vm, onDelete = { confirmDelete = p })
                }
            }
        }
    }

    if (showFree) FreePackagesDialog(vm) { showFree = false }

    confirmDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(L.t("حذف الاشتراك؟", "Delete subscription?")) },
            text = { Text(p.displayName()) },
            confirmButton = { TextButton(onClick = { ProfileStore.deleteProfile(p.id); confirmDelete = null }) { Text(L.t("حذف", "Delete"), color = Palette.Danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(L.t("إلغاء", "Cancel")) } },
        )
    }
}

@Composable
private fun ProfileCard(p: Profile, vm: MainViewModel, onDelete: () -> Unit) {
    val hideId = adminHidden()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.Surface).padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.displayName(), fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Badge(typeLabel(p.type), if (p.type == ProfileType.STALKER) Palette.Gold else Palette.Accent)
        }
        Spacer(Modifier.height(4.dp))
        Text(p.portalUrl, color = Palette.Text2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val detail = when (p.type) {
            ProfileType.STALKER -> if (hideId) "" else "MAC: ${p.mac}" + if (p.username.isNotBlank()) "  •  ${p.username}" else ""
            ProfileType.XTREAM -> L.t("المستخدم: ", "User: ") + p.username
            ProfileType.M3U -> if (p.epgUrl.isNotBlank()) "EPG ✓" else ""
        }
        if (detail.isNotBlank()) Text(detail, color = Palette.Text2, fontSize = 12.sp, maxLines = 1)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NButton(L.t("تسجيل الدخول", "Log in"), Icons.Filled.Login, primary = true, enabled = !vm.connecting) { vm.connect(p) }
            if (!hideId) {
                NButton(L.t("تعديل", "Edit"), Icons.Filled.Edit) { vm.push(Screen.Edit(p)) }
                NButton(L.t("نسخ", "Duplicate"), Icons.Filled.ContentCopy) {
                    ProfileStore.saveProfile(p.copy(id = java.util.UUID.randomUUID().toString(), nickname = p.displayName() + " (2)", lastUsed = 0))
                }
                NButton(L.t("حذف", "Delete"), Icons.Filled.Delete, danger = true) { onDelete() }
            }
        }
    }
}

// ============================================================================ editor

@Composable
fun ProfileEditor(vm: MainViewModel, initial: Profile?) {
    val ctx = LocalContext.current
    var p by remember(initial) { mutableStateOf(initial ?: Profile()) }
    var showLogin by remember(initial) { mutableStateOf(initial?.username?.isNotBlank() == true) }
    val macOk = p.type != ProfileType.STALKER || DeviceIdentity.isValidMac(DeviceIdentity.normalizeMac(p.mac))

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        p = p.copy(portalUrl = uri.toString())
    }

    fun finalProfile(): Profile? {
        var out = p.copy(portalUrl = p.portalUrl.trim(), nickname = p.nickname.trim())
        if (out.portalUrl.isBlank()) { vm.toast = L.t("أدخل عنوان البورتال / الرابط", "Enter the portal URL"); return null }
        if (out.type == ProfileType.STALKER) {
            if (!macOk) { vm.toast = L.t("عنوان MAC غير صالح (مثال 00:1A:79:12:34:56)", "Invalid MAC (e.g. 00:1A:79:12:34:56)"); return null }
            out = DeviceIdentity.derive(out, overwrite = false)
        }
        if (out.type == ProfileType.XTREAM && out.username.isBlank() && !out.portalUrl.contains("username=")) {
            vm.toast = L.t("أدخل اسم المستخدم وكلمة المرور", "Enter username and password"); return null
        }
        if (!showLogin && out.type == ProfileType.STALKER) out = out.copy(username = "", password = "")
        return out
    }

    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState())) {
        SectionTitle(if (initial == null) L.t("إضافة اشتراك جديد", "New subscription") else L.t("تعديل الاشتراك", "Edit subscription"))

        Text(L.t("نوع الاشتراك", "Subscription type"), color = Palette.Text2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProfileType.entries.forEach { t ->
                FilterChip(
                    selected = p.type == t,
                    onClick = {
                        p = p.copy(
                            type = t,
                            userAgent = if (t == ProfileType.STALKER) p.userAgent.ifBlank { DeviceIdentity.DEFAULT_STB_UA } else p.userAgent,
                        )
                    },
                    label = { Text(typeLabel(t)) },
                    modifier = Modifier.focusRing(RoundedCornerShape(8.dp)),
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        val wide = Modifier.fillMaxWidth()
        Field(L.t("اسم البورتال (Portal nickname)", "Portal nickname"), p.nickname, wide) { p = p.copy(nickname = it) }

        when (p.type) {
            ProfileType.STALKER -> {
                Field(L.t("رابط البورتال (Portal URL)", "Portal URL"), p.portalUrl, wide, keyboard = KeyboardType.Uri,
                    placeholder = "http://example.com:8080/c/") { p = p.copy(portalUrl = it) }

                SectionTitle(L.t("بيانات الجهاز", "Device identity"))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Field("MAC ID", p.mac, Modifier.weight(1f), isError = !macOk && p.mac.isNotBlank(), placeholder = "00:1A:79:XX:XX:XX") {
                        p = p.copy(mac = it.uppercase())
                    }
                    Spacer(Modifier.width(8.dp))
                    NButton(L.t("MAC عشوائي", "Random MAC"), Icons.Filled.Shuffle) {
                        p = DeviceIdentity.derive(p.copy(mac = DeviceIdentity.randomMac()), overwrite = true)
                    }
                }
                Field("Serial Number (SN)", p.serial, wide) { p = p.copy(serial = it.trim()) }
                Field("Device ID 1", p.deviceId1, wide) { p = p.copy(deviceId1 = it.trim()) }
                Field("Device ID 2", p.deviceId2, wide) { p = p.copy(deviceId2 = it.trim()) }
                Field("Signature", p.signature, wide) { p = p.copy(signature = it.trim()) }
                Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NButton(L.t("توليد SN و IDs من الـMAC", "Generate SN & IDs from MAC"), Icons.Filled.AutoFixHigh, enabled = macOk) {
                        p = DeviceIdentity.derive(p, overwrite = true)
                    }
                    NButton(L.t("مسح الحقول", "Clear fields"), Icons.Filled.Refresh) {
                        p = p.copy(serial = "", deviceId1 = "", deviceId2 = "", signature = "")
                    }
                }
                Text(L.t("طراز الجهاز (STB model)", "STB model"), color = Palette.Text2)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DeviceIdentity.STB_MODELS.forEach { m ->
                        FilterChip(selected = p.stbModel == m, onClick = { p = p.copy(stbModel = m) }, label = { Text(m) },
                            modifier = Modifier.focusRing(RoundedCornerShape(8.dp)))
                    }
                }
                Field(L.t("User Agent للبورتال", "Portal User-Agent"), p.userAgent, wide, singleLine = false) { p = p.copy(userAgent = it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(L.t("المنطقة الزمنية", "Timezone"), p.timezone, Modifier.weight(1f)) { p = p.copy(timezone = it.trim()) }
                    Field(L.t("اللغة", "Language"), p.language, Modifier.width(140.dp)) { p = p.copy(language = it.trim()) }
                }

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    FilterChip(selected = showLogin, onClick = { showLogin = !showLogin },
                        label = { Text(L.t("البورتال يطلب اسم مستخدم وكلمة مرور", "Portal requires login & password")) },
                        modifier = Modifier.focusRing(RoundedCornerShape(8.dp)))
                }
                if (showLogin) {
                    Field(L.t("اسم المستخدم", "Username"), p.username, wide) { p = p.copy(username = it.trim()) }
                    Field(L.t("كلمة المرور", "Password"), p.password, wide, password = true) { p = p.copy(password = it) }
                }
            }
            ProfileType.XTREAM -> {
                Field(L.t("رابط الخادم", "Server URL"), p.portalUrl, wide, keyboard = KeyboardType.Uri,
                    placeholder = "http://example.com:8080") { p = p.copy(portalUrl = it) }
                Field(L.t("اسم المستخدم", "Username"), p.username, wide) { p = p.copy(username = it.trim()) }
                Field(L.t("كلمة المرور", "Password"), p.password, wide, password = true) { p = p.copy(password = it) }
                Text(L.t("صيغة البث المباشر", "Live stream format"), color = Palette.Text2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("ts" to "MPEG-TS (.ts)", "m3u8" to "HLS (.m3u8)").forEach { (v, label) ->
                        FilterChip(selected = p.outputFormat == v, onClick = { p = p.copy(outputFormat = v) }, label = { Text(label) },
                            modifier = Modifier.focusRing(RoundedCornerShape(8.dp)))
                    }
                }
            }
            ProfileType.M3U -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Field(L.t("رابط قائمة M3U أو ملف", "M3U URL or file"), p.portalUrl, Modifier.weight(1f), keyboard = KeyboardType.Uri,
                        placeholder = "http://example.com/playlist.m3u") { p = p.copy(portalUrl = it) }
                    Spacer(Modifier.width(8.dp))
                    NButton(L.t("اختيار ملف", "Pick file"), Icons.Filled.FolderOpen) { filePicker.launch(arrayOf("*/*")) }
                }
            }
        }

        SectionTitle(L.t("خيارات إضافية", "Extra options"))
        Field("Player User-Agent", p.playerUserAgent, wide, placeholder = L.t("فارغ = الافتراضي", "empty = default")) { p = p.copy(playerUserAgent = it) }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("MAG" to DeviceIdentity.DEFAULT_STB_UA, "VLC" to "VLC/3.0.20 LibVLC/3.0.20", "okhttp" to "okhttp/4.12.0",
                "Chrome" to "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")
                .forEach { (name, ua) ->
                    FilterChip(selected = p.playerUserAgent == ua, onClick = { p = p.copy(playerUserAgent = ua) }, label = { Text(name) },
                        modifier = Modifier.focusRing(RoundedCornerShape(8.dp)))
                }
        }
        Field(L.t("رابط دليل البرامج EPG (XMLTV) — اختياري", "EPG (XMLTV) URL — optional"), p.epgUrl, wide, keyboard = KeyboardType.Uri) { p = p.copy(epgUrl = it.trim()) }

        Spacer(Modifier.height(16.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NButton(L.t("حفظ وتسجيل الدخول", "Save & log in"), Icons.Filled.Login, primary = true) {
                finalProfile()?.let { ProfileStore.saveProfile(it); vm.back(); vm.connect(it) }
            }
            NButton(L.t("حفظ", "Save"), Icons.Filled.Save) {
                finalProfile()?.let { ProfileStore.saveProfile(it); vm.toast = L.t("تم الحفظ", "Saved"); vm.back() }
            }
            NButton(L.t("إلغاء", "Cancel"), Icons.Filled.Close) { vm.back() }
        }
        Spacer(Modifier.height(40.dp))
    }
}
