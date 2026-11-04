package com.newlife.tv.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newlife.tv.data.L
import com.newlife.tv.data.ProfileStore
import com.newlife.tv.net.VpnManager

/** WireGuard VPN settings (shown only in the hidden "B" mode). */
@Composable
fun VpnSection(vm: MainViewModel) {
    val ctx = LocalContext.current
    val settings by ProfileStore.settings.collectAsState()
    val state by VpnManager.state.collectAsState()
    val active by VpnManager.activeName.collectAsState()
    val msg by VpnManager.message.collectAsState()
    var addDialog by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    var pastedName by remember { mutableStateOf("") }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) vm.vpnConnect()
        else vm.toast = L.t("لم تتم الموافقة على VPN", "VPN permission was not granted")
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val text = runCatching { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
        val name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "VPN"
        vm.addVpnConfig(name, text)?.let { vm.toast = it }
    }

    fun connect() {
        val intent = VpnManager.permissionIntent(ctx)
        if (intent != null) permission.launch(intent) else vm.vpnConnect()
    }

    SectionTitle("VPN (WireGuard)", Modifier.padding(top = 16.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.Surface).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.VpnLock, null, tint = if (state == VpnManager.State.ON) Palette.Accent else Palette.Text2)
            Spacer(Modifier.width(10.dp))
            Text(
                when (state) {
                    VpnManager.State.ON -> L.t("متصل", "Connected") + if (active.isNotBlank()) " — $active" else ""
                    VpnManager.State.CONNECTING -> L.t("جارٍ الاتصال…", "Connecting…")
                    VpnManager.State.ERROR -> L.t("خطأ: ", "Error: ") + msg
                    VpnManager.State.OFF -> L.t("غير متصل", "Disconnected")
                },
                fontWeight = FontWeight.Bold,
                color = when (state) {
                    VpnManager.State.ON -> Palette.Accent
                    VpnManager.State.ERROR -> Palette.Danger
                    else -> androidx.compose.ui.graphics.Color.White
                },
                modifier = Modifier.weight(1f),
            )
            if (state == VpnManager.State.CONNECTING) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        vm.vpnIp?.let { Text(L.t("عنوانك الظاهر: ", "Your public IP: ") + it, color = Palette.Text2, fontSize = 13.sp) }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state == VpnManager.State.ON) NButton(L.t("إيقاف VPN", "Turn VPN off"), Icons.Filled.VpnKey, danger = true) { vm.vpnDisconnect() }
            else NButton(L.t("تشغيل VPN", "Turn VPN on"), Icons.Filled.VpnKey, primary = true,
                enabled = settings.vpnConfigs.isNotEmpty() && state != VpnManager.State.CONNECTING) { connect() }
            NButton(L.t("فحص العنوان", "Check IP"), Icons.Filled.Public) { vm.checkVpnIp() }
            NButton(L.t("إعدادات VPN في النظام", "System VPN settings"), Icons.Filled.Settings) {
                runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_VPN_SETTINGS)) }
            }
        }

        Text(L.t("الخوادم", "Servers"), color = Palette.Text2, modifier = Modifier.padding(top = 12.dp))
        if (settings.vpnConfigs.isEmpty()) {
            Text(L.t("لا توجد خوادم. أضف ملف إعداد WireGuard (.conf) من مزود VPN أو من خادمك.",
                "No servers yet. Add a WireGuard (.conf) file from your VPN provider or your own server."), color = Palette.Text2, fontSize = 12.sp)
        }
        settings.vpnConfigs.forEach { c ->
            Row(Modifier.fillMaxWidth().tvClick { ProfileStore.updateSettings { it.copy(vpnSelected = c.id) } }.padding(4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = settings.vpnSelected == c.id, onClick = { ProfileStore.updateSettings { it.copy(vpnSelected = c.id) } },
                    colors = RadioButtonDefaults.colors(selectedColor = Palette.Accent))
                Text(c.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                NButton(L.t("حذف", "Delete"), Icons.Filled.Delete, danger = true) { vm.removeVpnConfig(c.id) }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NButton(L.t("استيراد ملف .conf", "Import .conf file"), Icons.Filled.FolderOpen) { filePicker.launch(arrayOf("*/*")) }
            NButton(L.t("لصق الإعداد", "Paste configuration"), Icons.Filled.Add) { addDialog = true }
        }

        Text(L.t("ما الذي يمر عبر VPN؟", "What goes through the VPN?"), color = Palette.Text2, modifier = Modifier.padding(top = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = settings.vpnAppOnly, onClick = { ProfileStore.updateSettings { it.copy(vpnAppOnly = true) } },
                label = { Text(L.t("NEW LIFE TV فقط (أسرع)", "NEW LIFE TV only (faster)")) }, modifier = Modifier.focusRing(RoundedCornerShape(8.dp)))
            FilterChip(selected = !settings.vpnAppOnly, onClick = { ProfileStore.updateSettings { it.copy(vpnAppOnly = false) } },
                label = { Text(L.t("كل الجهاز", "Whole device")) }, modifier = Modifier.focusRing(RoundedCornerShape(8.dp)))
        }
        Text(L.t("تشغيل تلقائي عند فتح البرنامج", "Connect automatically when the app opens"), color = Palette.Text2, modifier = Modifier.padding(top = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = settings.vpnAutoConnect, onClick = { ProfileStore.updateSettings { it.copy(vpnAutoConnect = true) } },
                label = { Text(L.t("نعم", "Yes")) }, modifier = Modifier.focusRing(RoundedCornerShape(8.dp)))
            FilterChip(selected = !settings.vpnAutoConnect, onClick = { ProfileStore.updateSettings { it.copy(vpnAutoConnect = false) } },
                label = { Text(L.t("لا", "No")) }, modifier = Modifier.focusRing(RoundedCornerShape(8.dp)))
        }
        Text(
            L.t("بعد تغيير الخادم أو الوضع، أوقف VPN وشغّله من جديد. لقطع الإنترنت عند انقطاع VPN استعمل \"VPN الدائم\" و\"حظر الاتصالات بدون VPN\" من إعدادات النظام.",
                "After changing the server or mode, turn the VPN off and on again. For a kill switch use \"Always-on VPN\" and \"Block connections without VPN\" in the system settings."),
            color = Palette.Text2, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp),
        )
    }

    if (addDialog) {
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { addDialog = false },
            title = { Text(L.t("لصق إعداد WireGuard", "Paste WireGuard configuration")) },
            text = {
                Column {
                    Field(L.t("الاسم (مثلاً: ألمانيا)", "Name (e.g. Germany)"), pastedName, Modifier.fillMaxWidth()) { pastedName = it }
                    Field("[Interface] … [Peer] …", pasted, Modifier.fillMaxWidth().height(220.dp), singleLine = false) { pasted = it }
                    error?.let { Text(it, color = Palette.Danger, fontSize = 12.sp) }
                }
            },
            confirmButton = {
                NButton(L.t("إضافة", "Add"), primary = true) {
                    error = vm.addVpnConfig(pastedName, pasted)
                    if (error == null) { addDialog = false; pasted = ""; pastedName = "" }
                }
            },
            dismissButton = { NButton(L.t("إلغاء", "Cancel")) { addDialog = false } },
        )
    }
}
