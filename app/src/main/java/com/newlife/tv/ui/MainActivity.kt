package com.newlife.tv.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.ContextCompat
import com.newlife.tv.data.L
import com.newlife.tv.data.ProfileStore

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProfileStore.init(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent { NewLifeTheme { AppRoot(vm) } }
    }
}

@Composable
fun AppRoot(vm: MainViewModel) {
    val ctx = LocalContext.current
    LaunchedEffect(vm.toast) {
        vm.toast?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); vm.toast = null }
    }
    val dir = if (L.arabic) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides dir) {
        Surface(Modifier.fillMaxSize(), color = Palette.Bg) {
            BackHandler(enabled = vm.stack.size > 1) { vm.back() }
            if (vm.updateDialog) UpdateDialog(vm)
            when (val s = vm.screen) {
                Screen.Profiles -> ProfilesScreen(vm)
                is Screen.Edit -> ProfileEditor(vm, s.profile)
                Screen.Home -> HomeScreen(vm)
                is Screen.SeriesDetail -> SeriesScreen(vm, s.entry)
                Screen.Settings -> SettingsPage(vm)
            }
        }
    }
}
