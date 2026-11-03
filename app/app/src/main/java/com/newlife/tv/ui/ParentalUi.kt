package com.newlife.tv.ui

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.newlife.tv.data.ContentKind
import com.newlife.tv.data.L
import com.newlife.tv.data.ProfileStore
import com.newlife.tv.net.IptvProvider

private fun kindLabel(k: ContentKind) = when (k) {
    ContentKind.LIVE -> "LIVE"
    ContentKind.VOD -> "VOD"
    ContentKind.SERIES -> "SERIES"
}

// ============================================================================ PIN

/** Asks for the parental PIN (or admin PIN) for the pending [MainViewModel.pinRequest]. */
@Composable
fun PinDialog(vm: MainViewModel) {
    val req = vm.pinRequest ?: return
    var pin by remember(req) { mutableStateOf("") }
    var error by remember(req) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { vm.pinRequest = null },
        icon = { Icon(Icons.Filled.Lock, null, tint = Palette.Gold) },
        title = { Text(req.title) },
        text = {
            Column {
                Text(
                    if (req.admin) L.t("أدخل الرقم السري للإدارة", "Enter the admin PIN")
                    else L.t("أدخل الرقم السري للرقابة الأبوية", "Enter the parental PIN"),
                    color = Palette.Text2,
                )
                Spacer(Modifier.height(8.dp))
                Field(L.t("الرقم السري", "PIN"), pin, Modifier.fillMaxWidth(), password = true, keyboard = KeyboardType.NumberPassword, isError = error) {
                    pin = it.filter { c -> c.isDigit() }.take(8); error = false
                }
                if (error) Text(L.t("الرقم السري غير صحيح", "Wrong PIN"), color = Palette.Danger)
            }
        },
        confirmButton = { NButton(L.t("موافق", "OK"), primary = true) { if (!vm.submitPin(pin)) { error = true; pin = "" } } },
        dismissButton = { NButton(L.t("إلغاء", "Cancel")) { vm.pinRequest = null } },
    )
}

@Composable
fun ChangePinDialog(vm: MainViewModel, onClose: () -> Unit) {
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    fun digits(s: String) = s.filter { it.isDigit() }.take(8)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Parental Control — " + L.t("تغيير الرقم السري", "Change PIN")) },
        text = {
            Column {
                Field(L.t("الرقم السري الحالي", "Current PIN"), old, Modifier.fillMaxWidth(), password = true, keyboard = KeyboardType.NumberPassword) { old = digits(it) }
                Field(L.t("الرقم السري الجديد", "New PIN"), new, Modifier.fillMaxWidth(), password = true, keyboard = KeyboardType.NumberPassword) { new = digits(it) }
                Field(L.t("تأكيد الرقم الجديد", "Confirm PIN"), confirm, Modifier.fillMaxWidth(), password = true, keyboard = KeyboardType.NumberPassword) { confirm = digits(it) }
                error?.let { Text(it, color = Palette.Danger) }
                Text(L.t("الرقم الافتراضي 0000", "Default PIN is 0000"), color = Palette.Text2, fontSize = 12.sp)
            }
        },
        confirmButton = { NButton(L.t("حفظ", "Save"), primary = true) { error = vm.changePin(old, new, confirm); if (error == null) onClose() } },
        dismissButton = { NButton(L.t("إلغاء", "Cancel")) { onClose() } },
    )
}

// ============================================================================ item menus (long press)

@Composable
private fun MenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().tvClick(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Palette.Accent)
        Spacer(Modifier.width(12.dp))
        Text(text)
    }
}

@Composable
fun ItemMenuDialog(vm: MainViewModel) {
    val e = vm.itemMenu ?: return
    val cat = vm.categories[e.kind]?.firstOrNull { it.id == e.categoryId }
    val close = { vm.itemMenu = null }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                MenuRow(Icons.Filled.Favorite, if (vm.isFav(e)) L.t("إزالة من المفضلة", "Remove from favorites") else L.t("إضافة إلى المفضلة", "Add to favorites")) { vm.toggleFav(e); close() }
                val chLocked = vm.parental.locked.contains(ProfileStore.chKey(vm.activeProfile?.id ?: "", e))
                MenuRow(if (chLocked) Icons.Filled.LockOpen else Icons.Filled.Lock,
                    if (chLocked) L.t("إلغاء قفل هذه القناة", "Unlock this item") else L.t("قفل هذه القناة", "Lock this item")) { close(); vm.toggleLockEntry(e) }
                if (cat != null && cat.id != IptvProvider.ALL) {
                    val gLocked = vm.isCatLocked(e.kind, cat.id)
                    MenuRow(if (gLocked) Icons.Filled.LockOpen else Icons.Filled.Lock,
                        (if (gLocked) L.t("إلغاء قفل المجموعة: ", "Unlock group: ") else L.t("قفل المجموعة: ", "Lock group: ")) + cat.title) { close(); vm.toggleLockCategory(e.kind, cat) }
                    MenuRow(Icons.Filled.VisibilityOff, L.t("إخفاء المجموعة: ", "Hide group: ") + cat.title) { close(); vm.hideCategory(e.kind, cat) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = close) { Text(L.t("إغلاق", "Close")) } },
    )
}

@Composable
fun CategoryMenuDialog(vm: MainViewModel) {
    val (kind, c) = vm.categoryMenu ?: return
    val close = { vm.categoryMenu = null }
    val locked = vm.isCatLocked(kind, c.id)
    AlertDialog(
        onDismissRequest = close,
        title = { Text(c.title) },
        text = {
            Column {
                MenuRow(if (locked) Icons.Filled.LockOpen else Icons.Filled.Lock,
                    if (locked) L.t("إلغاء قفل المجموعة", "Unlock group") else L.t("قفل المجموعة", "Lock group")) { close(); vm.toggleLockCategory(kind, c) }
                if (c.id != IptvProvider.ALL) MenuRow(Icons.Filled.VisibilityOff, L.t("إخفاء المجموعة", "Hide group")) { close(); vm.hideCategory(kind, c) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = close) { Text(L.t("إغلاق", "Close")) } },
    )
}

// ============================================================================ hide groups

/** "Select categories you want to hide" with LIVE / VOD / SERIES tabs. */
@Composable
fun HideCategoriesDialog(vm: MainViewModel, onClose: () -> Unit) {
    var kind by remember { mutableStateOf(ContentKind.LIVE) }
    LaunchedEffect(kind) { vm.ensureCategories(kind) }
    val cats = vm.categories[kind].orEmpty().filter { it.id != IptvProvider.ALL }
    var selected by remember(kind, cats.size) {
        mutableStateOf(cats.filter { vm.isCatHidden(kind, it.id) }.map { it.id }.toSet())
    }
    var q by remember(kind) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(L.t("اختر الباقات التي تريد إخفاءها", "Select categories you want to hide")) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ContentKind.entries.forEach { k ->
                        FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(kindLabel(k)) },
                            modifier = Modifier.focusRing(RoundedCornerShape(8.dp)))
                    }
                }
                Field(L.t("بحث", "Search"), q, Modifier.fillMaxWidth()) { q = it }
                Text("${selected.size} / ${cats.size} " + L.t("مخفية", "hidden"), color = Palette.Text2, fontSize = 12.sp)
                val shown = if (q.isBlank()) cats else cats.filter { it.title.contains(q.trim(), true) }
                if (cats.isEmpty()) Text(L.t("جارٍ التحميل… (يجب تسجيل الدخول لاشتراك)", "Loading… (log in to a subscription first)"), color = Palette.Text2)
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown) { c ->
                        val on = c.id in selected
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp)).background(Palette.Surface2.copy(alpha = 0.4f))
                                .tvClick { selected = if (on) selected - c.id else selected + c.id }.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(c.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Checkbox(checked = on, onCheckedChange = { selected = if (on) selected - c.id else selected + c.id },
                                colors = CheckboxDefaults.colors(checkedColor = Palette.Accent))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NButton(L.t("نعم", "YES"), primary = true) { vm.setHiddenCategories(kind, selected); onClose() }
                NButton(L.t("تحديد الكل", "SELECT ALL")) { selected = if (selected.size == cats.size) emptySet() else cats.map { it.id }.toSet() }
                NButton(L.t("إلغاء", "CANCEL")) { onClose() }
            }
        },
    )
}

// ============================================================================ app lock

fun biometricAvailable(ctx: android.content.Context): Boolean =
    BiometricManager.from(ctx).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

fun showBiometric(activity: FragmentActivity, onSuccess: () -> Unit) {
    val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onSuccess() }
    })
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle("NEW LIFE TV")
        .setSubtitle(L.t("افتح البرنامج ببصمة الإصبع أو الوجه", "Unlock with fingerprint or face"))
        .setNegativeButtonText(L.t("استخدام الرقم السري", "Use PIN"))
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        .build()
    runCatching { prompt.authenticate(info) }
}

/** Full-screen lock shown at start-up when "app lock" is on. */
@Composable
fun LockScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val settings by ProfileStore.settings.collectAsState()
    val bioAvailable = remember { biometricAvailable(ctx) }
    val canBio = settings.biometricEnabled && bioAvailable
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(canBio) {
        if (canBio) (ctx as? FragmentActivity)?.let { showBiometric(it) { vm.appLocked = false } }
    }
    Box(Modifier.fillMaxSize().background(Palette.Bg), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 420.dp).padding(24.dp).clip(RoundedCornerShape(16.dp)).background(Palette.Surface).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.Lock, null, tint = Palette.Gold, modifier = Modifier.padding(8.dp))
            Row {
                Text("NEW LIFE ", fontWeight = FontWeight.Black, fontSize = 26.sp)
                Text("TV", fontWeight = FontWeight.Black, fontSize = 26.sp, color = Palette.Gold)
            }
            Text(L.t("البرنامج مقفل", "The app is locked"), color = Palette.Text2)
            Spacer(Modifier.height(12.dp))
            Field(L.t("الرقم السري", "PIN"), pin, Modifier.fillMaxWidth(), password = true, keyboard = KeyboardType.NumberPassword, isError = error) {
                pin = it.filter { c -> c.isDigit() }.take(8); error = false
            }
            if (error) Text(L.t("الرقم السري غير صحيح", "Wrong PIN"), color = Palette.Danger)
            Spacer(Modifier.height(12.dp))
            NButton(L.t("فتح", "Unlock"), Icons.Filled.LockOpen, primary = true, modifier = Modifier.fillMaxWidth()) {
                if (!vm.unlockApp(pin)) { error = true; pin = "" }
            }
            if (canBio) {
                Spacer(Modifier.height(8.dp))
                NButton(L.t("بصمة الإصبع / الوجه", "Fingerprint / face"), Icons.Filled.Fingerprint, modifier = Modifier.fillMaxWidth()) {
                    (ctx as? FragmentActivity)?.let { showBiometric(it) { vm.appLocked = false } }
                }
            }
        }
    }
}

// ============================================================================ free packages

/** Public, legal playlists maintained by the iptv-org / Free-TV communities. */
val FREE_PACKAGES = listOf(
    Triple("🌍", "iptv-org — " + "All channels / كل القنوات", "https://iptv-org.github.io/iptv/index.m3u"),
    Triple("🗺", "iptv-org — By country / حسب الدولة", "https://iptv-org.github.io/iptv/index.country.m3u"),
    Triple("🎬", "iptv-org — By category / حسب التصنيف", "https://iptv-org.github.io/iptv/index.category.m3u"),
    Triple("🗣", "iptv-org — By language / حسب اللغة", "https://iptv-org.github.io/iptv/index.language.m3u"),
    Triple("🇸🇦", "Arabic channels / القنوات العربية", "https://iptv-org.github.io/iptv/languages/ara.m3u"),
    Triple("🇦🇪", "UAE / الإمارات", "https://iptv-org.github.io/iptv/countries/ae.m3u"),
    Triple("🇸🇦", "Saudi Arabia / السعودية", "https://iptv-org.github.io/iptv/countries/sa.m3u"),
    Triple("🇪🇬", "Egypt / مصر", "https://iptv-org.github.io/iptv/countries/eg.m3u"),
    Triple("🇰🇼", "Kuwait / الكويت", "https://iptv-org.github.io/iptv/countries/kw.m3u"),
    Triple("📺", "Free-TV (quality list)", "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8"),
)

@Composable
fun FreePackagesDialog(vm: MainViewModel, onClose: () -> Unit) {
    val profiles by ProfileStore.profiles.collectAsState()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(L.t("باقات مجانية", "Free packages")) },
        text = {
            Column {
                Text(
                    L.t("قوائم قنوات عامة ومجانية يجمعها مجتمع iptv-org وFree-TV. بعض القنوات قد تتوقف من مصدرها.",
                        "Public free channel lists maintained by the iptv-org and Free-TV communities. Some streams may go offline at the source."),
                    color = Palette.Text2, fontSize = 12.sp,
                )
                LazyColumn(Modifier.heightIn(max = 400.dp).padding(top = 6.dp)) {
                    items(FREE_PACKAGES) { (icon, name, url) ->
                        val added = profiles.any { it.portalUrl == url }
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(icon, fontSize = 20.sp, modifier = Modifier.width(34.dp))
                            Text(name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (added) Text("✓", color = Palette.Accent, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp))
                            else NButton(L.t("إضافة", "Add")) { vm.addFreePackage(name, url) }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose) { Text(L.t("إغلاق", "Close")) } },
    )
}

@Composable
fun LockBadge() {
    Icon(Icons.Filled.Lock, null, tint = Palette.Gold, modifier = Modifier.padding(horizontal = 2.dp).width(16.dp))
}
