package com.shawn.zhaoye

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge to edge on every version rather than only where the system
        // forces it (target 35+): the canvas may run under the bars, and the
        // screens subtract the insets themselves. Without this the layout was
        // correct on one phone and correct on none.
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = DarkroomColours) {
                // The engine boots inside the view model, so it has to exist
                // before the splash starts or the launch animation would only
                // delay the wait it is covering. `rememberSaveable` keeps a
                // rotation from replaying it.
                val vm: DarkroomViewModel = viewModel()
                var splash by rememberSaveable { mutableStateOf(true) }
                Crossfade(targetState = splash, animationSpec = tween(340)) { opening ->
                    if (opening) {
                        SplashScreen(onDone = { splash = false })
                        return@Crossfade
                    }
                    // Two screens, no navigation library: the settings page is a
                    // leaf and back is the only transition it needs.
                    var settings by remember { mutableStateOf(false) }
                if (settings) {
                    // Without this the system back button leaves the app from
                    // the settings page instead of returning to the darkroom.
                    BackHandler { settings = false }
                    SettingsScreen(onBack = { settings = false })
                } else {
                    DarkroomScreen(
                        vm = vm,
                        debugImage = intent?.getStringExtra("image"),
                        onSettings = { settings = true },
                    )
                }
                    }
            }
        }
    }
}

// The catalogs the edge panel offers. Ids are the engine's own strings; the
// labels are what fits a phone. `carrier` defaults to "none" because upstream
// found a black band past the film's edge read as a defect (2026-10-04).
// "auto" is a decision, not a gate: it takes the shape closest to the frame
// so the picture is never trimmed to fit a hole it never had.
private val FORMATS = listOf(
    "auto" to "自动", "135" to "135", "135_half" to "135 半格",
    "120_645" to "645", "120_6x6" to "6×6", "120_6x7" to "6×7",
)
private val GATES = listOf(
    "auto" to "自动", "square" to "方", "rounded" to "圆角",
    "eared" to "耳", "shouldered" to "肩", "kicked" to "翘角",
)
private val HOLES = listOf("white" to "白（扫描）", "black" to "黑（印相）")
private val CARRIERS = listOf("none" to "无", "black" to "黑片夹", "open" to "亮片夹")
private val CORNERS = listOf("br" to "右下", "bl" to "左下", "tr" to "右上", "tl" to "左上")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DarkroomScreen(
    vm: DarkroomViewModel = viewModel(),
    debugImage: String? = null,
    onSettings: () -> Unit = {},
) {
    var picking by remember { mutableStateOf<Picking?>(null) }

    // `adb shell am start ... --es image <path>` renders that file on launch.
    androidx.compose.runtime.LaunchedEffect(debugImage) {
        val target = debugImage.orEmpty()
        if (target.isEmpty()) return@LaunchedEffect
        // A content:// URI takes the same road as a photo picked in 相册.
        if (target.startsWith("content://")) vm.open(Uri.parse(target))
        else vm.openFile(java.io.File(target))
    }

    // OpenDocument rather than PickVisualMedia: the photo picker only offers
    // what the system recognises as an image, and a .ARW is not that.
    val pickPhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) vm.open(uri)
    }

    // Edge to edge is mandatory from target 35, so the background is painted
    // first and the insets subtracted after it: the canvas still runs under
    // the status bar, the title starts below it.
    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {

        Header(
            vm = vm,
            onPick = { pickPhoto.launch(arrayOf("*/*")) },
            onExport = { vm.export(it) },
            onSettings = onSettings,
        )

        // Canvas
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF0A0908))
                .border(1.dp, Color(0xFF2A241E), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = vm.preview
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "冲印预览",
                    modifier = Modifier.fillMaxSize().padding(6.dp),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (vm.ready) "还没有底片" else vm.status,
                        color = Faint,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { pickPhoto.launch(arrayOf("*/*")) },
                        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Ink),
                    ) { Text("打开照片") }
                }
            }

            if (vm.busy) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xCC1B1815))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) { Text("冲印中…", color = Amber, fontSize = 11.sp) }
            }

            if (bitmap != null) {
                Text(
                    "${vm.film.name} · ${vm.paper.name}",
                    color = Color(0xFFD9D2C6),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xB3080707))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }

        StockRow(vm, onFilm = { picking = Picking.Film }, onPaper = { picking = Picking.Paper })
        EdgeRow(vm)

        val paramScroll = rememberScrollState()
        // Window-space y of the list and of each expandable block. Everything is
        // measured in the same space so scroll translation cancels out.
        var listTop by remember { mutableStateOf(-1f) }
        var edgeTop by remember { mutableStateOf(-1f) }
        var dateTop by remember { mutableStateOf(-1f) }
        var wasEdge by remember { mutableStateOf(vm.overscanOn) }
        var wasDate by remember { mutableStateOf(vm.dateOn) }
        LaunchedEffect(vm.overscanOn, vm.dateOn) {
            val edgeIn = vm.overscanOn && !wasEdge
            val dateIn = vm.dateOn && !wasDate
            wasEdge = vm.overscanOn
            wasDate = vm.dateOn
            // Only on the way in: closing a section should not move the list.
            if (edgeIn || dateIn) {
                kotlinx.coroutines.delay(90)
                // Land on the *first* row that just appeared rather than the very
                // end: jumping to the bottom hid 画幅 and 片门, which are what the
                // finger reaches for first after switching the frame edge on.
                val wanted = when {
                    edgeIn && edgeTop >= 0f && listTop >= 0f ->
                        paramScroll.value + (edgeTop - listTop)
                    dateIn && dateTop >= 0f && listTop >= 0f ->
                        paramScroll.value + (dateTop - listTop)
                    else -> paramScroll.maxValue.toFloat()
                }
                paramScroll.animateScrollTo(
                    wanted.coerceIn(0f, paramScroll.maxValue.coerceAtLeast(0).toFloat()).toInt(),
                )
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 250.dp)
                .onGloballyPositioned { listTop = it.positionInWindow().y }
                .verticalScroll(paramScroll)
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {

            ParamSlider(
                "曝光", vm.exposure, -2f..2f, String.format("%+.1f EV", vm.exposure),
            ) { v -> vm.update { vm.exposure = v } }
            ParamSlider(
                "M 滤色（洋红）", vm.mFilter, -1f..1f,
                String.format("%+.0f CC", vm.mFilter * DarkroomViewModel.FILTER_CC),
            ) { v -> vm.update { vm.mFilter = v } }
            ParamSlider(
                "Y 滤色（黄）", vm.yFilter, -1f..1f,
                String.format("%+.0f CC", vm.yFilter * DarkroomViewModel.FILTER_CC),
            ) { v -> vm.update { vm.yFilter = v } }
            ParamSlider(
                "颗粒", vm.grain, 0f..1f, String.format("%.2f", vm.grain),
            ) { v -> vm.update { vm.grain = v } }
            ParamSlider(
                "光晕", vm.halation, 0f..1f, String.format("%.2f", vm.halation),
            ) { v -> vm.update { vm.halation = v } }

            if (vm.overscanOn) {
              Column(Modifier.onGloballyPositioned { edgeTop = it.positionInWindow().y }) {
                ChoiceRow("画幅", FORMATS, vm.filmFormat) { vm.update { vm.filmFormat = it } }
                ChoiceRow("片门", GATES, vm.gate) { vm.update { vm.gate = it } }
                ChoiceRow("齿孔那儿看什么", HOLES, vm.holes) { vm.update { vm.holes = it } }
                ChoiceRow("片边之外", CARRIERS, vm.carrier) { vm.update { vm.carrier = it } }
                ChoiceRow(
                    "边缘字",
                    listOf(
                        vm.film.name.uppercase(java.util.Locale.US) to vm.film.name,
                        "KODAK" to "KODAK",
                        "PORTRA 400" to "PORTRA 400",
                        "FUJIFILM" to "FUJIFILM",
                        "" to "不印",
                    ),
                    vm.edgeText,
                ) { vm.update { vm.edgeText = it } }
                ParamSlider(
                    "边缘雾", vm.fog, 0f..2f, String.format("%.2f", vm.fog),
                ) { v -> vm.update { vm.fog = v } }
              }
            }

            // The toggles add rows *after* the sliders, where they belong: the
            // five sliders are what the finger wants most often and must not
            // move. Turning a toggle on scrolls to the far end so the new rows
            // are seen rather than silently appended off-screen.
            if (vm.dateOn) {
              Column(Modifier.onGloballyPositioned { dateTop = it.positionInWindow().y }) {
                DateRow(
                    vm.dateText, vm.dateFromPhoto, vm.dateMillis, vm.shootDateAvailable,
                    onPick = { vm.chooseDate(it) },
                    onUsePhoto = { vm.useShootDate() },
                )
                ChoiceRow("日期位置", CORNERS, vm.dateCorner) { vm.update { vm.dateCorner = it } }
              }
            }
        }

        Text(
            vm.status,
            color = if (vm.statusIsError) SafeRed else Faint,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            // Two lines of 11 sp plus padding: without a floor the weighted
            // canvas above ate the space and the second line was drawn past
            // the bottom of the screen.
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }

    when (picking) {
        Picking.Film -> StockSheet(
            title = "胶片",
            items = Stocks.films.map { it.id to it.name },
            selected = vm.filmId,
            onPick = { vm.importStock(film = it); picking = null },
            onDismiss = { picking = null },
        )
        Picking.Paper -> StockSheet(
            title = "相纸",
            items = Stocks.papers.map { it.id to it.name },
            selected = vm.paperId,
            onPick = { vm.importStock(paper = it); picking = null },
            onDismiss = { picking = null },
        )
        null -> Unit
    }
}

private enum class Picking { Film, Paper }

@Composable
private fun Header(
    vm: DarkroomViewModel,
    onPick: () -> Unit,
    onExport: (ExportTarget) -> Unit,
    onSettings: () -> Unit,
) {
    var exporting by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "照夜",
                color = Bone,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Serif,
            )
            Text("不是滤镜，是冲印", color = Faint, fontSize = 10.sp)
        }
        IconButton(onClick = onPick) {
            Icon(Icons.Default.Add, contentDescription = "打开照片", tint = Faint)
        }
        // Nothing loaded means nothing to export. A dimmed button says so;
        // a tap that only rewrites the 10 sp footer reads as a broken button.
        //
        // The tap opens a menu rather than saving: a phone has two ways to
        // "share" a print, and picking one for the user was wrong half the
        // time -- the file landed in the gallery with no way to send it.
        Box {
            IconButton(onClick = { exporting = true }, enabled = vm.hasFrame) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = "导出",
                    tint = if (vm.hasFrame) Amber else Faint,
                )
            }
            DropdownMenu(
                expanded = exporting,
                onDismissRequest = { exporting = false },
                modifier = Modifier.background(Panel),
            ) {
                DropdownMenuItem(
                    text = { Text("保存到相册", color = Bone, fontSize = 13.sp) },
                    onClick = {
                        exporting = false
                        onExport(ExportTarget.Gallery)
                    },
                )
                DropdownMenuItem(
                    text = { Text("系统分享", color = Bone, fontSize = 13.sp) },
                    onClick = {
                        exporting = false
                        onExport(ExportTarget.Share)
                    },
                )
            }
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Default.Settings, contentDescription = "设置", tint = Faint)
        }
    }
}

@Composable
private fun StockRow(vm: DarkroomViewModel, onFilm: () -> Unit, onPaper: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StockChip("胶片", vm.film.name, Modifier.weight(1f), onFilm)
        if (!vm.film.positive) StockChip("相纸", vm.paper.name, Modifier.weight(1f), onPaper)
        else StockChip("相纸", "反转片只扫描", Modifier.weight(1f), null)
    }
}

@Composable
private fun EdgeRow(vm: DarkroomViewModel) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ToggleChip(
            label = "片边",
            value = if (vm.overscanOn) {
                if (vm.filmFormat == "auto") "自动·${vm.effectiveFormatLabel}"
                else FORMATS.firstOrNull { it.first == vm.filmFormat }?.second ?: vm.filmFormat
            } else "关",
            on = vm.overscanOn,
            modifier = Modifier.weight(1f),
            onClick = { vm.toggleOverscan() },
        )
        ToggleChip(
            label = "日期后背",
            value = if (vm.dateOn) vm.dateText else "关",
            on = vm.dateOn,
            modifier = Modifier.weight(1f),
            onClick = { vm.toggleDate() },
        )
    }
}

@Composable
private fun ToggleChip(label: String, value: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (on) Color(0xFF2A2118) else Panel)
            .border(1.dp, if (on) Amber else Color(0xFF2A241E), RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = if (on) Amber else Faint, fontSize = 9.sp)
            Text(value, color = if (on) Bone else Faint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A one-of-N row that scrolls sideways, for values a slider cannot hold. */
@Composable
private fun ChoiceRow(label: String, options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = Faint, fontSize = 11.sp)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for ((id, name) in options) {
                val on = id == selected
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (on) Amber else Panel)
                        .clickable { onPick(id) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) { Text(name, color = if (on) Ink else Bone, fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun StockChip(label: String, value: String, modifier: Modifier, onClick: (() -> Unit)?) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Panel)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, color = Faint, fontSize = 9.sp)
        Text(
            value,
            color = if (onClick != null) Bone else Faint,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StockSheet(
    title: String,
    items: List<Pair<String, String>>,
    selected: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = Panel,
    ) {
        Text(
            title,
            color = Bone,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
        )
        HorizontalDivider(color = Color(0xFF2A241E))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
            items(items) { (id, name) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(id) }
                        .padding(horizontal = 20.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (id == selected) Amber else Color.Transparent),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        name,
                        color = if (id == selected) Amber else Bone,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    if (id == selected) Text("当前", color = Amber, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun ParamSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    reading: String,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Faint, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text(reading, color = Amber, fontSize = 11.sp)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Amber,
                activeTrackColor = Amber,
                inactiveTrackColor = Color(0xFF332C25),
            ),
            modifier = Modifier.fillMaxWidth().height(28.dp),
        )
    }
}

/**
 * The date the back prints, and where that date comes from: a frame whose EXIF
 * carries the camera's clock prints that; anything else starts on today and is
 * set by hand. A real back was wired to the shutter, so the file's own moment
 * wins whenever there is one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRow(
    dateText: String,
    fromPhoto: Boolean,
    millis: Long,
    shootAvailable: Boolean,
    onPick: (Long) -> Unit,
    onUsePhoto: () -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(if (fromPhoto) "日期 · 取自照片" else "日期 · 手选", color = Faint, fontSize = 11.sp)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Amber)
                    .clickable { picking = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) { Text("$dateText · 改", color = Ink, fontSize = 11.sp) }
            if (shootAvailable && !fromPhoto) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Panel)
                        .clickable { onUsePhoto() }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) { Text("用照片日期", color = Bone, fontSize = 11.sp) }
            }
        }
    }
    if (picking) {
        val state = rememberDatePickerState(initialSelectedDateMillis = millis)
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let(onPick)
                    picking = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { picking = false }) { Text("取消") }
            },
        ) { DatePicker(state = state) }
    }
}
