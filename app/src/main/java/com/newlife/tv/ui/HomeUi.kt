package com.newlife.tv.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.newlife.tv.data.ContentKind
import com.newlife.tv.data.EpgProgram
import com.newlife.tv.data.L
import com.newlife.tv.data.MediaEntry
import com.newlife.tv.data.PlayRequest
import com.newlife.tv.data.ProfileStore
import com.newlife.tv.data.ScheduledRecording
import com.newlife.tv.player.PlayerActivity
import com.newlife.tv.record.RecordingScheduler
import com.newlife.tv.record.RecordingService
import com.newlife.tv.record.RecordingStore
import kotlinx.coroutines.delay
import java.io.File

private fun sectionIcon(s: Section): ImageVector = when (s) {
    Section.LIVE -> Icons.Filled.LiveTv
    Section.VOD -> Icons.Filled.Movie
    Section.SERIES -> Icons.Filled.VideoLibrary
    Section.GUIDE -> Icons.Filled.CalendarMonth
    Section.FAVORITES -> Icons.Filled.Favorite
    Section.SEARCH -> Icons.Filled.Search
    Section.RECORDINGS -> Icons.Filled.FiberManualRecord
    Section.SPEED -> Icons.Filled.Speed
    Section.ACCOUNT -> Icons.Filled.AccountCircle
    Section.SETTINGS -> Icons.Filled.Settings
}

private fun sectionLabel(s: Section): String = when (s) {
    Section.LIVE -> L.t("البث المباشر", "Live TV")
    Section.VOD -> L.t("الأفلام", "Movies (VOD)")
    Section.SERIES -> L.t("المسلسلات", "TV Series")
    Section.GUIDE -> L.t("دليل البرامج", "TV Guide (EPG)")
    Section.FAVORITES -> L.t("المفضلة", "Favorites")
    Section.SEARCH -> L.t("بحث", "Search")
    Section.RECORDINGS -> L.t("التسجيلات", "Recordings")
    Section.SPEED -> L.t("اختبار السرعة", "Speed test")
    Section.ACCOUNT -> L.t("الحساب والاتصال", "Account & connection")
    Section.SETTINGS -> L.t("الإعدادات", "Settings")
}

// ============================================================================ home shell

@Composable
fun HomeScreen(vm: MainViewModel) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(20_000); now = System.currentTimeMillis() } }

    Row(Modifier.fillMaxSize()) {
        // ---------------- navigation rail ----------------
        Column(
            Modifier.width(220.dp).fillMaxHeight().background(Palette.Surface).verticalScroll(rememberScrollState()).padding(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(6.dp)) {
                Text("NEW LIFE ", fontWeight = FontWeight.Black, fontSize = 18.sp)
                Text("TV", fontWeight = FontWeight.Black, fontSize = 18.sp, color = Palette.Gold)
            }
            Text(vm.activeProfile?.displayName() ?: "", color = Palette.Text2, fontSize = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
            Spacer(Modifier.height(10.dp))
            Section.entries.forEach { s ->
                RailItem(sectionIcon(s), sectionLabel(s), vm.section == s) { vm.selectSection(s) }
            }
            Spacer(Modifier.height(10.dp))
            RailItem(Icons.Filled.Refresh, L.t("تحديث", "Refresh"), false) { vm.refresh() }
            RailItem(Icons.Filled.Logout, L.t("تبديل / خروج", "Switch / log out"), false) { vm.logout() }
        }

        // ---------------- content ----------------
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(sectionLabel(vm.section), fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                val expiry = vm.account?.fields?.firstOrNull { it.first == L.t("تاريخ الانتهاء", "Expiry") }?.second
                if (expiry != null) Badge(L.t("ينتهي: ", "Expires: ") + expiry, Palette.Gold)
                Spacer(Modifier.width(12.dp))
                Text(L.hm(now), fontSize = 20.sp, color = Palette.Text2)
            }
            if (vm.loading || vm.connecting) LinearProgressIndicator(Modifier.fillMaxWidth())
            Box(Modifier.fillMaxSize()) {
                when (vm.section) {
                    Section.LIVE -> LiveSection(vm)
                    Section.VOD -> PosterSection(vm, ContentKind.VOD)
                    Section.SERIES -> PosterSection(vm, ContentKind.SERIES)
                    Section.GUIDE -> GuideSection(vm)
                    Section.FAVORITES -> FavoritesSection(vm)
                    Section.SEARCH -> SearchSection(vm)
                    Section.RECORDINGS -> RecordingsSection(vm)
                    Section.SPEED -> SpeedSection(vm)
                    Section.ACCOUNT -> AccountSection(vm)
                    Section.SETTINGS -> SettingsContent(vm)
                }
            }
        }
    }

    vm.vodDialog?.let { VodDialog(vm, it) }
}

@Composable
private fun RailItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).tvClick(selected = selected, onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (selected) Palette.Accent else Palette.Text2, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = if (selected) Palette.Accent else Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CategoryColumn(vm: MainViewModel, kind: ContentKind) {
    val cats = vm.categories[kind].orEmpty()
    val sel = vm.selectedCat[kind]
    LazyColumn(Modifier.width(230.dp).fillMaxHeight().background(Palette.Surface.copy(alpha = 0.5f)).padding(6.dp)) {
        items(cats) { c ->
            Text(
                c.title,
                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)
                    .tvClick(selected = c.id == sel) { vm.selectCategory(kind, c.id) }.padding(10.dp),
                color = if (c.id == sel) Palette.Accent else Color.White,
                fontWeight = if (c.id == sel) FontWeight.Bold else FontWeight.Normal,
                maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text, color = Palette.Text2) }
}

// ============================================================================ live

@Composable
private fun LiveSection(vm: MainViewModel) {
    val ctx = LocalContext.current
    val list = if (vm.entriesKind == ContentKind.LIVE) vm.entries else emptyList()
    Row(Modifier.fillMaxSize()) {
        CategoryColumn(vm, ContentKind.LIVE)
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (list.isEmpty() && !vm.loading) EmptyHint(L.t("لا توجد قنوات", "No channels"))
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                itemsIndexed(list) { i, e ->
                    val nowProg = vm.epg[e.id]?.firstOrNull { it.isNow }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            .tvClick(onLongClick = { vm.toggleFav(e) }, onFocus = { vm.focus(e) }) { vm.play(ctx, e, list) }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (e.number > 0) "${e.number}" else "${i + 1}", color = Palette.Text2, fontSize = 13.sp, modifier = Modifier.width(44.dp))
                        Logo(e.logo, 44.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            if (nowProg != null) Text(nowProg.title, color = Palette.Text2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (e.catchupDays > 0) Icon(Icons.Filled.History, null, tint = Palette.Text2, modifier = Modifier.size(16.dp))
                        if (vm.isFav(e)) Icon(Icons.Filled.Favorite, null, tint = Palette.Gold, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        ChannelPanel(vm, list)
    }
}

@Composable
private fun ChannelPanel(vm: MainViewModel, list: List<MediaEntry>) {
    val ctx = LocalContext.current
    val e = vm.focused
    Column(Modifier.width(340.dp).fillMaxHeight().background(Palette.Surface).padding(14.dp)) {
        if (e == null || e.kind != ContentKind.LIVE) {
            Text(L.t("اختر قناة لعرض معلومات البرامج", "Focus a channel to see its programs"), color = Palette.Text2)
            Spacer(Modifier.height(8.dp))
            Text(L.t("تلميح: ضغطة مطولة = إضافة للمفضلة", "Tip: long-press = add to favorites"), color = Palette.Text2, fontSize = 12.sp)
        } else ChannelPanelBody(vm, e, list)
    }
}

@Composable
private fun ChannelPanelBody(vm: MainViewModel, e: MediaEntry, list: List<MediaEntry>) {
    val ctx = LocalContext.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Logo(e.logo, 56.dp)
            Spacer(Modifier.width(10.dp))
            Text(e.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NButton(L.t("مشاهدة", "Watch"), Icons.Filled.PlayArrow, primary = true) { vm.play(ctx, e, list) }
            NButton(if (vm.isFav(e)) L.t("إزالة", "Unfav") else L.t("مفضلة", "Fav"),
                if (vm.isFav(e)) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder) { vm.toggleFav(e) }
            RecordNowButton(vm, e)
        }
        Spacer(Modifier.height(10.dp))
        val programs = vm.epg[e.id]
        when {
            programs == null -> CircularProgressIndicator(Modifier.size(24.dp))
            programs.isEmpty() -> Text(L.t("لا يوجد دليل برامج لهذه القناة", "No EPG for this channel"), color = Palette.Text2)
            else -> LazyColumn {
                items(programs.filter { !it.isPast }.take(12)) { p ->
                    Column(Modifier.padding(vertical = 5.dp)) {
                        Text("${L.hm(p.start)} - ${L.hm(p.end)}", color = if (p.isNow) Palette.Accent else Palette.Text2, fontSize = 12.sp)
                        Text(p.title, fontWeight = if (p.isNow) FontWeight.Bold else FontWeight.Normal, maxLines = 2)
                        if (p.isNow) {
                            LinearProgressIndicator(progress = { p.progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp))
                            if (p.description.isNotBlank()) Text(p.description, color = Palette.Text2, fontSize = 12.sp, maxLines = 4)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordNowButton(vm: MainViewModel, e: MediaEntry) {
    var ask by remember { mutableStateOf(false) }
    NButton(L.t("تسجيل", "Record"), Icons.Filled.FiberManualRecord) { ask = true }
    if (ask) {
        AlertDialog(
            onDismissRequest = { ask = false },
            title = { Text(L.t("تسجيل ${e.title}", "Record ${e.title}")) },
            text = {
                Column {
                    listOf(30, 60, 120, 180).forEach { m ->
                        TextButton(onClick = { vm.recordNow(e, m); ask = false }, modifier = Modifier.focusRing()) {
                            Text(if (m < 60) L.t("$m دقيقة", "$m minutes") else L.t("${m / 60} ساعة", "${m / 60} hour(s)"))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { ask = false }) { Text(L.t("إلغاء", "Cancel")) } },
        )
    }
}

// ============================================================================ VOD / Series grid

@Composable
private fun PosterSection(vm: MainViewModel, kind: ContentKind) {
    val list = if (vm.entriesKind == kind) vm.entries else emptyList()
    Row(Modifier.fillMaxSize()) {
        CategoryColumn(vm, kind)
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (list.isEmpty() && !vm.loading) EmptyHint(L.t("لا يوجد محتوى", "Nothing here"))
            PosterGrid(vm, list)
        }
    }
}

@Composable
private fun PosterGrid(vm: MainViewModel, list: List<MediaEntry>) {
    LazyVerticalGrid(GridCells.Adaptive(140.dp), Modifier.fillMaxSize().padding(8.dp)) {
        itemsIndexed(list) { _, e ->
            Column(
                Modifier.padding(5.dp).tvClick(onLongClick = { vm.toggleFav(e) }) {
                    if (e.kind == ContentKind.SERIES) vm.openSeries(e) else vm.vodDialog = e
                }.padding(5.dp)
            ) {
                Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(Palette.Surface2)) {
                    Icon(if (e.kind == ContentKind.SERIES) Icons.Filled.VideoLibrary else Icons.Filled.Movie, null,
                        tint = Palette.Text2, modifier = Modifier.align(Alignment.Center).size(36.dp))
                    if (e.logo.isNotBlank()) AsyncImage(e.logo, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    if (vm.isFav(e)) Icon(Icons.Filled.Favorite, null, tint = Palette.Gold, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp))
                    if (e.rating.isNotBlank() && e.rating != "0") Box(Modifier.align(Alignment.BottomStart).padding(4.dp)) { Badge("★ ${e.rating.take(3)}", Palette.Gold) }
                }
                Spacer(Modifier.height(4.dp))
                Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun VodDialog(vm: MainViewModel, e: MediaEntry) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = { vm.vodDialog = null },
        title = { Text(e.title) },
        text = {
            Row {
                if (e.logo.isNotBlank()) {
                    AsyncImage(e.logo, null, contentScale = ContentScale.Crop,
                        modifier = Modifier.width(120.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)))
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val meta = listOfNotNull(e.year.takeIf { it.isNotBlank() }, e.rating.takeIf { it.isNotBlank() }?.let { "★ $it" }).joinToString("  •  ")
                    if (meta.isNotBlank()) Text(meta, color = Palette.Gold)
                    if (e.plot.isNotBlank()) Text(e.plot, color = Palette.Text2, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NButton(L.t("تشغيل", "Play"), Icons.Filled.PlayArrow, primary = true) { vm.vodDialog = null; vm.play(ctx, e) }
                NButton(if (vm.isFav(e)) L.t("إزالة من المفضلة", "Unfavorite") else L.t("المفضلة", "Favorite"), Icons.Filled.Favorite) { vm.toggleFav(e) }
                NButton(L.t("تنزيل", "Download"), Icons.Filled.FiberManualRecord) { vm.recordNow(e, 600); vm.vodDialog = null }
            }
        },
        dismissButton = { TextButton(onClick = { vm.vodDialog = null }) { Text(L.t("إغلاق", "Close")) } },
    )
}

@Composable
fun SeriesScreen(vm: MainViewModel, e: MediaEntry) {
    val ctx = LocalContext.current
    var sel by remember(vm.seasons) { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row {
            Box(Modifier.width(150.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(Palette.Surface2)) {
                if (e.logo.isNotBlank()) AsyncImage(e.logo, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(e.title, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                val meta = listOfNotNull(e.year.takeIf { it.isNotBlank() }, e.rating.takeIf { it.isNotBlank() }?.let { "★ $it" }).joinToString("  •  ")
                if (meta.isNotBlank()) Text(meta, color = Palette.Gold)
                if (e.plot.isNotBlank()) Text(e.plot, color = Palette.Text2, maxLines = 5, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NButton(L.t("رجوع", "Back")) { vm.back() }
                    NButton(if (vm.isFav(e)) L.t("إزالة من المفضلة", "Unfavorite") else L.t("أضف للمفضلة", "Favorite"), Icons.Filled.Favorite) { vm.toggleFav(e) }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        if (vm.seasonsLoading) CircularProgressIndicator()
        val seasons = vm.seasons
        if (seasons.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(seasons) { i, s ->
                    FilterChip(selected = i == sel, onClick = { sel = i }, label = { Text(s.title) },
                        modifier = Modifier.focusRing(RoundedCornerShape(8.dp)))
                }
            }
            val season = seasons.getOrNull(sel) ?: seasons.first()
            LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
                items(season.episodes) { ep ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp).tvClick { vm.playEpisode(ctx, e, season, ep) }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("E${ep.number}", color = Palette.Accent, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp))
                        if (ep.image.isNotBlank()) { Logo(ep.image, 64.dp, fallback = Icons.Filled.Movie); Spacer(Modifier.width(10.dp)) }
                        Column(Modifier.weight(1f)) {
                            Text(ep.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (ep.plot.isNotBlank()) Text(ep.plot, color = Palette.Text2, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Filled.PlayArrow, null, tint = Palette.Text2)
                    }
                }
            }
        }
    }
}

// ============================================================================ EPG guide

@Composable
private fun GuideSection(vm: MainViewModel) {
    val ctx = LocalContext.current
    val list = if (vm.entriesKind == ContentKind.LIVE) vm.entries else emptyList()
    Row(Modifier.fillMaxSize()) {
        CategoryColumn(vm, ContentKind.LIVE)
        LazyColumn(Modifier.width(290.dp).fillMaxHeight().padding(horizontal = 6.dp)) {
            items(list) { e ->
                val nowProg = vm.epg[e.id]?.firstOrNull { it.isNow }
                LaunchedEffect(e.id) { vm.loadEpg(e) }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        .tvClick(selected = vm.guideChannel?.id == e.id, onFocus = { vm.guideChannel = e }) { vm.guideChannel = e }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Logo(e.logo, 36.dp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                        Text(nowProg?.title ?: "—", color = Palette.Text2, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        val ch = vm.guideChannel
        Column(Modifier.weight(1f).fillMaxHeight().background(Palette.Surface).padding(12.dp)) {
            if (ch == null) EmptyHint(L.t("اختر قناة لعرض جدول برامجها", "Pick a channel to see its schedule"))
            else GuidePrograms(vm, ch, list)
        }
    }
}

@Composable
private fun GuidePrograms(vm: MainViewModel, ch: MediaEntry, list: List<MediaEntry>) {
    val ctx = LocalContext.current
    Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Logo(ch.logo, 44.dp); Spacer(Modifier.width(10.dp))
                Text(ch.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                NButton(L.t("مشاهدة", "Watch"), Icons.Filled.PlayArrow, primary = true) { vm.play(ctx, ch, list) }
                Spacer(Modifier.width(6.dp))
                NButton(L.t("تحديث", "Reload"), Icons.Filled.Refresh) { vm.loadEpg(ch, force = true) }
            }
            val programs = vm.epg[ch.id]
            when {
                programs == null -> CircularProgressIndicator(Modifier.padding(16.dp))
                programs.isEmpty() -> EmptyHint(L.t("لا يوجد دليل برامج لهذه القناة", "No EPG for this channel"))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    var lastDay = ""
                    programs.forEach { p ->
                        val day = L.day(p.start)
                        if (day != lastDay) {
                            lastDay = day
                            item { Text(day, color = Palette.Gold, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)) }
                        }
                        item { ProgramRow(vm, ch, p) }
                    }
                }
            }
    }
}

@Composable
private fun ProgramRow(vm: MainViewModel, ch: MediaEntry, p: EpgProgram) {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp))
            .background(if (p.isNow) Palette.Surface2 else Color.Transparent).padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${L.hm(p.start)} - ${L.hm(p.end)}", color = if (p.isNow) Palette.Accent else Palette.Text2, fontSize = 13.sp)
            Spacer(Modifier.width(8.dp))
            if (p.isNow) Badge(L.t("يُعرض الآن", "ON NOW"))
            Spacer(Modifier.width(6.dp))
            Text("${(p.end - p.start) / 60000} " + L.t("د", "min"), color = Palette.Text2, fontSize = 11.sp)
        }
        Text(p.title, fontWeight = FontWeight.SemiBold)
        if (p.description.isNotBlank()) Text(p.description, color = Palette.Text2, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (p.isNow) LinearProgressIndicator(progress = { p.progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                p.isNow -> {
                    NButton(L.t("مشاهدة", "Watch"), Icons.Filled.PlayArrow) { vm.play(ctx, ch) }
                    NButton(L.t("تسجيل حتى النهاية", "Record to end"), Icons.Filled.FiberManualRecord) {
                        vm.recordNow(ch, (((p.end - System.currentTimeMillis()) / 60000).toInt() + 3).coerceAtLeast(5))
                    }
                    if (ch.catchupDays > 0) NButton(L.t("من البداية", "From start"), Icons.Filled.History) { vm.playCatchup(ctx, ch, p) }
                }
                p.isPast -> if (ch.catchupDays > 0) NButton(L.t("مشاهدة الإعادة", "Watch catch-up"), Icons.Filled.History) { vm.playCatchup(ctx, ch, p) }
                else -> NButton(L.t("جدولة تسجيل", "Schedule recording"), Icons.Filled.Schedule) { vm.scheduleRecording(ch, p) }
            }
        }
    }
}

// ============================================================================ favourites & search

@Composable
private fun EntryRow(vm: MainViewModel, e: MediaEntry, list: List<MediaEntry>) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).tvClick(onLongClick = { vm.toggleFav(e) }) {
            when (e.kind) {
                ContentKind.LIVE -> vm.play(ctx, e, list.filter { it.kind == ContentKind.LIVE })
                ContentKind.VOD -> vm.vodDialog = e
                ContentKind.SERIES -> vm.openSeries(e)
            }
        }.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Logo(e.logo, 44.dp, fallback = when (e.kind) {
            ContentKind.LIVE -> Icons.Filled.LiveTv; ContentKind.VOD -> Icons.Filled.Movie; ContentKind.SERIES -> Icons.Filled.VideoLibrary
        })
        Spacer(Modifier.width(10.dp))
        Text(e.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Badge(when (e.kind) {
            ContentKind.LIVE -> L.t("قناة", "Live"); ContentKind.VOD -> L.t("فيلم", "Movie"); ContentKind.SERIES -> L.t("مسلسل", "Series")
        })
        if (vm.isFav(e)) { Spacer(Modifier.width(6.dp)); Icon(Icons.Filled.Favorite, null, tint = Palette.Gold, modifier = Modifier.size(16.dp)) }
    }
}

@Composable
private fun FavoritesSection(vm: MainViewModel) {
    val favs by ProfileStore.favorites.collectAsState()
    val list = remember(favs, vm.activeProfile) { vm.favorites() }
    if (list.isEmpty()) { EmptyHint(L.t("المفضلة فارغة — اضغط مطولاً على أي قناة أو فيلم لإضافته", "No favorites — long-press any channel or movie to add it")); return }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        items(list) { e -> EntryRow(vm, e, list) }
    }
}

@Composable
private fun SearchSection(vm: MainViewModel) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(L.t("ابحث في القنوات والأفلام والمسلسلات", "Search channels, movies and series"), vm.searchQuery, Modifier.weight(1f)) { vm.searchQuery = it }
            Spacer(Modifier.width(8.dp))
            NButton(L.t("بحث", "Search"), Icons.Filled.Search, primary = true) { vm.search() }
        }
        if (vm.searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(vm.searchResults) { e -> EntryRow(vm, e, vm.searchResults) }
        }
    }
}

// ============================================================================ recordings

@Composable
private fun RecordingsSection(vm: MainViewModel) {
    val ctx = LocalContext.current
    val active by RecordingService.active.collectAsState()
    val schedules by ProfileStore.schedules.collectAsState()
    val settings by ProfileStore.settings.collectAsState()
    var files by remember { mutableStateOf(RecordingStore.list(ctx)) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(active.size, tick, settings.recordStorageIndex) { files = RecordingStore.list(ctx) }
    val volumes = remember { RecordingStore.volumes(ctx) }

    LazyColumn(Modifier.fillMaxSize().padding(14.dp)) {
        item {
            Text(L.t("مكان الحفظ", "Storage location"), color = Palette.Text2)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                volumes.forEachIndexed { i, f ->
                    val free = L.size(f.usableSpace)
                    FilterChip(
                        selected = settings.recordStorageIndex == i,
                        onClick = { ProfileStore.updateSettings { it.copy(recordStorageIndex = i) } },
                        label = { Text((if (i == 0) L.t("ذاكرة الجهاز", "Device storage") else L.t("بطاقة SD / USB $i", "SD card / USB $i")) + "  ($free " + L.t("متاح", "free") + ")") },
                        modifier = Modifier.focusRing(RoundedCornerShape(8.dp)),
                    )
                }
            }
            Text(RecordingStore.dir(ctx).absolutePath, color = Palette.Text2, fontSize = 11.sp)
            if (!RecordingScheduler.canExact(ctx) && Build.VERSION.SDK_INT >= 31) {
                Spacer(Modifier.height(6.dp))
                NButton(L.t("السماح بالتنبيهات الدقيقة لتسجيل المواعيد", "Allow exact alarms for scheduled recordings"), Icons.Filled.Schedule) {
                    runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + ctx.packageName))) }
                }
            }
        }
        if (active.isNotEmpty()) {
            item { SectionTitle(L.t("قيد التسجيل الآن", "Recording now")) }
            items(active.values.toList()) { a ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.FiberManualRecord, null, tint = Palette.Rec, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${L.size(a.bytes)} • " + L.t("ينتهي ", "ends ") + L.hm(a.endsAt), color = Palette.Text2, fontSize = 12.sp)
                    }
                    NButton(L.t("إيقاف", "Stop"), Icons.Filled.Stop, danger = true) { RecordingService.stop(ctx, a.id) }
                }
            }
        }
        val upcoming = schedules.filter { it.status == ScheduledRecording.STATUS_SCHEDULED && it.endMs > System.currentTimeMillis() }
        val history = schedules.filter { it !in upcoming }.takeLast(10).reversed()
        if (upcoming.isNotEmpty()) {
            item { SectionTitle(L.t("تسجيلات مجدولة", "Scheduled recordings")) }
            items(upcoming.sortedBy { it.startMs }) { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Schedule, null, tint = Palette.Gold, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${L.day(s.startMs)}  ${L.hm(s.startMs)} → ${L.hm(s.endMs)}", color = Palette.Text2, fontSize = 12.sp)
                    }
                    NButton(L.t("إلغاء", "Cancel"), Icons.Filled.Delete) { vm.cancelSchedule(s) }
                }
            }
        }
        if (history.isNotEmpty()) {
            item { SectionTitle(L.t("سجل الجدولة", "Schedule history")) }
            items(history) { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(s.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, color = Palette.Text2)
                    Badge(s.status, if (s.status == ScheduledRecording.STATUS_DONE) Palette.Accent else Palette.Danger)
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = { ProfileStore.removeSchedule(s.id) }) { Text("✕") }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(L.t("الملفات المسجلة", "Recorded files"), Modifier.weight(1f))
                NButton(L.t("تحديث", "Refresh"), Icons.Filled.Refresh) { tick++ }
            }
        }
        if (files.isEmpty()) item { Text(L.t("لا توجد تسجيلات بعد", "No recordings yet"), color = Palette.Text2) }
        items(files) { f -> RecordingFileRow(f) { tick++ } }
    }
}

@Composable
private fun RecordingFileRow(f: File, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).tvClick {
            PlayerActivity.start(ctx, PlayRequest(url = Uri.fromFile(f).toString(), title = f.nameWithoutExtension), null)
        }.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Movie, null, tint = Palette.Text2)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${L.size(f.length())} • ${L.dateTime(f.lastModified())}", color = Palette.Text2, fontSize = 12.sp)
        }
        NButton(L.t("حذف", "Delete"), Icons.Filled.Delete, danger = true) { confirm = true }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(L.t("حذف التسجيل؟", "Delete recording?")) },
            text = { Text(f.name) },
            confirmButton = { TextButton(onClick = { f.delete(); confirm = false; onChanged() }) { Text(L.t("حذف", "Delete"), color = Palette.Danger) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(L.t("إلغاء", "Cancel")) } },
        )
    }
}

// ============================================================================ account

@Composable
private fun AccountSection(vm: MainViewModel) {
    val p = vm.activeProfile
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionTitle(L.t("معلومات الاشتراك", "Subscription info"))
        vm.account?.fields?.forEach { (k, v) -> InfoRow(k, v) }
        if (p != null && p.mac.isNotBlank()) {
            SectionTitle(L.t("بيانات الجهاز المرسلة", "Device identity sent"))
            InfoRow("MAC", p.mac)
            InfoRow("Serial", p.serial)
            InfoRow("Device ID 1", p.deviceId1.take(24) + if (p.deviceId1.length > 24) "…" else "")
            InfoRow("Device ID 2", p.deviceId2.take(24) + if (p.deviceId2.length > 24) "…" else "")
            InfoRow("Model", p.stbModel)
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NButton(L.t("اختبار الاتصال", "Test connection"), Icons.Filled.NetworkCheck, primary = true) { vm.testConnection() }
            NButton(L.t("تحديث القوائم", "Reload lists"), Icons.Filled.Refresh) { vm.refresh() }
            if (p != null) NButton(L.t("تعديل الاشتراك", "Edit subscription"), Icons.Filled.Edit) { vm.push(Screen.Edit(p)) }
            NButton(L.t("تسجيل الخروج", "Log out"), Icons.Filled.Logout, danger = true) { vm.logout() }
        }
    }
}

// ============================================================================ speed test

@Composable
private fun SpeedCard(title: String, ping: Long?, mbps: Double?, live: Double?, running: Boolean, note: String, modifier: Modifier, button: @Composable () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Palette.Surface).padding(16.dp)) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (note.isNotBlank()) Text(note, color = Palette.Text2, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            val shown = if (running) live else mbps
            Text(shown?.let { "%.1f".format(it) } ?: "—", fontSize = 44.sp, fontWeight = FontWeight.Black,
                color = if (running) Palette.Gold else Palette.Accent)
            Spacer(Modifier.width(6.dp))
            Text("Mbps", color = Palette.Text2, modifier = Modifier.padding(bottom = 8.dp))
        }
        Text(L.t("زمن الاستجابة (Ping): ", "Latency (ping): ") + (ping?.let { "$it ms" } ?: "—"), color = Palette.Text2)
        if (running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
        Spacer(Modifier.height(10.dp))
        button()
    }
}

@Composable
private fun SpeedSection(vm: MainViewModel) {
    val ctx = LocalContext.current
    val network = remember { com.newlife.tv.net.SpeedTest.networkType(ctx) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        if (network.isNotBlank()) Text(L.t("نوع الاتصال: ", "Connection: ") + network, color = Palette.Text2)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            SpeedCard(
                L.t("سرعة الإنترنت", "Internet speed"), vm.netPing, vm.netMbps, vm.speedLive, vm.speedRunning == "net",
                L.t("تنزيل من خوادم اختبار عالمية", "Download from public test servers"), Modifier.weight(1f),
            ) {
                if (vm.speedRunning == "net") NButton(L.t("إيقاف", "Stop"), Icons.Filled.Stop, danger = true) { vm.stopSpeedTest() }
                else NButton(L.t("ابدأ الاختبار", "Start test"), Icons.Filled.Speed, primary = true, enabled = vm.speedRunning == null) { vm.testInternet() }
            }
            SpeedCard(
                L.t("سرعة السيرفر (الاشتراك)", "Server (subscription) speed"), vm.srvPing, vm.srvMbps, vm.speedLive, vm.speedRunning == "srv",
                if (vm.srvChannel.isNotBlank()) L.t("القناة المختبرة: ", "Tested channel: ") + vm.srvChannel
                else L.t("يقيس سرعة وصول بث قناة حقيقية من السيرفر", "Measures how fast a real channel arrives from the server"),
                Modifier.weight(1f),
            ) {
                if (vm.speedRunning == "srv") NButton(L.t("إيقاف", "Stop"), Icons.Filled.Stop, danger = true) { vm.stopSpeedTest() }
                else NButton(L.t("ابدأ الاختبار", "Start test"), Icons.Filled.Speed, primary = true, enabled = vm.speedRunning == null && vm.activeProfile != null) { vm.testServer() }
            }
        }
        vm.speedError?.let { Text("⚠ $it", color = Palette.Danger, modifier = Modifier.padding(top = 10.dp)) }

        SectionTitle(L.t("ماذا تستطيع أن تشاهد؟", "What can you watch?"), Modifier.padding(top = 16.dp))
        val net = vm.netMbps
        val srv = vm.srvMbps
        val best = listOfNotNull(net, srv).minOrNull()
        listOf("SD" to 3.0, "HD 720p" to 6.0, "Full HD 1080p" to 10.0, "4K UHD" to 25.0, "8K" to 60.0).forEach { (q, need) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(q, Modifier.width(160.dp), fontWeight = FontWeight.SemiBold)
                Text(L.t("يحتاج ", "needs ") + "≥ ${need.toInt()} Mbps", color = Palette.Text2, modifier = Modifier.width(170.dp))
                when {
                    best == null -> Text("—", color = Palette.Text2)
                    net != null && net < need -> Text(L.t("✗ الإنترنت غير كافٍ", "✗ internet too slow"), color = Palette.Danger)
                    else -> Text("✓", color = Palette.Accent, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            L.t(
                "ملاحظة: سرعة السيرفر في البث المباشر تقارب معدل بث القناة نفسها (البث يُرسل بسرعته الطبيعية)، لذا قيمة 4–10 Mbps طبيعية لقناة HD. إذا كانت أقل من ذلك مع إنترنت سريع فالمشكلة غالباً من السيرفر.",
                "Note: for live channels the server speed is close to the channel bitrate (live streams arrive in real time), so 4–10 Mbps is normal for an HD channel. If it is lower while your internet is fast, the server is usually the bottleneck.",
            ),
            color = Palette.Text2, fontSize = 12.sp,
        )
    }
}
