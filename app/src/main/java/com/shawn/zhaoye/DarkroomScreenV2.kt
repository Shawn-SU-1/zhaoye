package com.shawn.zhaoye

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale
import kotlin.math.roundToInt

private enum class V2Picker { Film, Paper }
private enum class CanvasPhase { Empty, Loading, Ready }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DarkroomScreenV2(
    vm: DarkroomViewModel = viewModel(),
    debugImage: String? = null,
    debugExportLongEdge: Int? = null,
    debugExport: Boolean = false,
    debugOverscan: Boolean? = null,
    debugForceTiled: Boolean = false,
    onSettings: () -> Unit = {},
) {
    var picking by remember { mutableStateOf<V2Picker?>(null) }
    var comparing by rememberSaveable { mutableStateOf(false) }
    var compareProgress by rememberSaveable { mutableStateOf(0.5f) }
    var compareHapticBucket by remember { mutableStateOf(1) }
    var zoomScale by remember { mutableStateOf(1f) }
    var group by rememberSaveable { mutableStateOf(0) }
    var exportTarget by remember { mutableStateOf<ExportTarget?>(null) }
    val haptics = rememberZhaoyeHaptics()

    LaunchedEffect(vm.status, vm.statusIsError) {
        if (vm.statusIsError) haptics.perform(HapticCue.Error)
    }

    LaunchedEffect(zoomScale, vm.preview) {
        if (zoomScale > 1.5f && !comparing) {
            kotlinx.coroutines.delay(350)
            vm.requestInspection()
        } else {
            vm.releaseInspection()
        }
    }
    LaunchedEffect(comparing, vm.preview, vm.overscanOn) {
        if (comparing && vm.overscanOn) vm.requestComparison() else vm.releaseComparison()
    }

    LaunchedEffect(debugImage, debugExportLongEdge, debugExport, debugOverscan, debugForceTiled) {
        debugExportLongEdge?.let(vm::chooseExportLongEdge)
        vm.debugForceTiledExport = debugForceTiled
        debugOverscan?.let { enabled ->
            if (vm.overscanOn != enabled) vm.toggleOverscan()
        }
        val target = debugImage.orEmpty()
        if (target.isEmpty()) return@LaunchedEffect
        if (target.startsWith("content://")) vm.open(Uri.parse(target))
        else vm.openFile(java.io.File(target))
        if (debugExport) {
            while (vm.busy || vm.preview == null) kotlinx.coroutines.delay(100)
            vm.export()
        }
    }

    val context = LocalContext.current
    val hasSystemPicker = remember {
        ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(context)
    }
    val pickSystem = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) vm.open(uri) }
    val pickGallery = rememberLauncherForActivityResult(PickFromGallery()) { uri ->
        if (uri != null) vm.open(uri)
    }
    val pickFile = rememberLauncherForActivityResult(OpenRawFile()) { uri ->
        if (uri != null) vm.open(uri)
    }
    val openGallery: () -> Unit = {
        comparing = false
        if (hasSystemPicker) {
            pickSystem.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        } else {
            runCatching { pickGallery.launch(galleryIntent()) }
                .onFailure { pickFile.launch(arrayOf("image/*")) }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        V2Header()

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
            val comparisonReady = !vm.overscanOn || vm.comparisonPreview != null
            val phase = when {
                bitmap != null -> CanvasPhase.Ready
                vm.busy || vm.hasFrame -> CanvasPhase.Loading
                else -> CanvasPhase.Empty
            }
            AnimatedContent(
                targetState = phase,
                transitionSpec = {
                    (
                        fadeIn(tween(220, delayMillis = 30)) +
                            scaleIn(tween(220, easing = FastOutSlowInEasing), initialScale = 0.985f)
                        ) togetherWith (
                        fadeOut(tween(150)) +
                            scaleOut(tween(150, easing = FastOutSlowInEasing), targetScale = 1.008f)
                        )
                },
                label = "darkroom-canvas",
            ) { state ->
                when (state) {
                    CanvasPhase.Ready -> {
                        val readyBitmap = vm.preview
                        if (readyBitmap != null) {
                            val previewBitmap = if (comparing && vm.overscanOn) {
                                vm.comparisonPreview ?: readyBitmap
                            } else {
                                vm.inspectionPreview ?: readyBitmap
                            }
                            ZoomablePreview(
                                bitmap = previewBitmap,
                                originalBitmap = vm.originalPreview.takeIf {
                                    comparing && comparisonReady
                                },
                                compareProgress = compareProgress.takeIf {
                                    comparing && comparisonReady
                                },
                                longPressBitmap = vm.originalPreview,
                                colorFilter = null,
                                modifier = Modifier.fillMaxSize().padding(6.dp),
                                onScaleChanged = { zoomScale = it },
                            )
                        }
                    }
                    CanvasPhase.Loading -> DarkroomLoading()
                    CanvasPhase.Empty -> {
                        EmptyDarkroom(vm, openGallery) { pickFile.launch(arrayOf("*/*")) }
                    }
                }
            }

            AnimatedContent(
                targetState = vm.busy && bitmap != null,
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
                transitionSpec = {
                    (
                        fadeIn(tween(130)) +
                            slideInVertically(tween(160), initialOffsetY = { -it / 4 })
                        ) togetherWith (
                        fadeOut(tween(100)) +
                            slideOutVertically(tween(120), targetOffsetY = { -it / 5 })
                        )
                },
                label = "busy-badge-visibility",
            ) { visible ->
                if (visible) BusyBadge(scrubbing = vm.isScrubbing)
                else Spacer(Modifier.size(0.dp))
            }

            if (bitmap != null) {
                Text(
                    if (vm.film.positive) "${vm.film.name} · 直接扫描"
                    else "${vm.film.name} · ${vm.paper.name}",
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
                if (!comparing) {
                    Text(
                        if (zoomScale > 1.01f) {
                            String.format(Locale.US, "%.1f× · 双击继续", zoomScale)
                        } else {
                            "双指缩放 · 双击放大"
                        },
                        color = Color(0xFFBDB4A8),
                        fontSize = 10.sp,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(10.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xA6080707))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                } else if (comparisonReady) {
                    CompareLabel("原图", Alignment.TopStart)
                    CompareLabel("成片", Alignment.TopEnd)
                } else {
                    Text(
                        if (vm.comparisonLoading) "正在准备无片边对比…" else "对比准备失败",
                        color = Amber,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(10.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xC4080707))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }

        if (comparing && vm.originalPreview != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("原图", color = Faint, fontSize = 10.sp)
                Slider(
                    value = compareProgress,
                    onValueChange = {
                        val bucket = (it * 2f).roundToInt()
                        if (bucket != compareHapticBucket) {
                            haptics.perform(HapticCue.Detent)
                            compareHapticBucket = bucket
                        }
                        compareProgress = it
                    },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = Amber,
                        activeTrackColor = Amber,
                        inactiveTrackColor = Color(0xFF332C25),
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .padding(horizontal = 8.dp),
                )
                Text("成片", color = Faint, fontSize = 10.sp)
            }
        }

        AnimatedContent(
            targetState = vm.status to vm.statusIsError,
            transitionSpec = {
                (
                    fadeIn(tween(150)) +
                        slideInVertically(tween(160), initialOffsetY = { it / 3 })
                    ) togetherWith (
                    fadeOut(tween(100)) +
                        slideOutVertically(tween(120), targetOffsetY = { -it / 4 })
                    )
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 34.dp),
            label = "status-line",
        ) { (text, isError) ->
            Text(
                text,
                color = if (isError) SafeRed else Faint,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 5.dp),
            )
        }

        EditorDock(
            vm = vm,
            group = group,
            enabled = vm.hasFrame,
            onGroup = { group = it },
            onFilm = { picking = V2Picker.Film },
            onPaper = { picking = V2Picker.Paper },
        )
        V2BottomNavigation(
            canUseImage = vm.hasFrame,
            canCompare = vm.hasFrame && vm.originalPreview != null,
            comparing = comparing,
            onOpen = openGallery,
            onOriginal = { comparing = !comparing },
            onSave = { exportTarget = ExportTarget.Gallery },
            onShare = { exportTarget = ExportTarget.Share },
            onSettings = onSettings,
        )
    }

    exportTarget?.let { target ->
        ExportOptionsSheet(
            vm = vm,
            initialTarget = target,
            onExport = {
                exportTarget = null
                vm.export(it)
            },
            onDismiss = { exportTarget = null },
        )
    }

    vm.exportProgress?.let { progress ->
        ExportProgressDialog(progress)
    }

    when (picking) {
        V2Picker.Film -> StockSheet(
            title = "胶片",
            items = Stocks.films.map { it.id to it.name },
            selected = vm.filmId,
            onPick = {
                vm.importStock(film = it)
                picking = null
            },
            onDismiss = { picking = null },
        )
        V2Picker.Paper -> StockSheet(
            title = "相纸",
            items = Stocks.papers.map { it.id to it.name },
            selected = vm.paperId,
            onPick = {
                vm.importStock(paper = it)
                picking = null
            },
            onDismiss = { picking = null },
        )
        null -> Unit
    }
}

@Composable
private fun V2Header() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
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
    }
}

@Composable
private fun EmptyDarkroom(
    vm: DarkroomViewModel,
    onGallery: () -> Unit,
    onFile: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (vm.ready) "还没有底片" else vm.status, color = Faint, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onGallery,
            colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Ink),
        ) { Text("从相册选") }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onFile) {
            Text("选 RAW 或其他文件", color = Faint, fontSize = 11.sp)
        }
    }
}

@Composable
private fun BoxScope.CompareLabel(text: String, alignment: Alignment) {
    Text(
        text,
        color = Amber,
        fontSize = 11.sp,
        modifier = Modifier
            .align(alignment)
            .padding(10.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xC4080707))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun EditorDock(
    vm: DarkroomViewModel,
    group: Int,
    enabled: Boolean,
    onGroup: (Int) -> Unit,
    onFilm: () -> Unit,
    onPaper: () -> Unit,
) {
    val groups = listOf("光影", "质感", "胶片", "片边")
    val haptics = rememberZhaoyeHaptics()
    Column(
        Modifier
            .fillMaxWidth()
            .height(270.dp)
            .background(Color(0xFF100E0C)),
    ) {
        HorizontalDivider(color = Color(0xFF2A241E))
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            groups.forEachIndexed { index, label ->
                val active = group == index
                val background by animateColorAsState(
                    targetValue = if (active) Color(0xFF30251A) else Color.Transparent,
                    animationSpec = tween(160),
                    label = "dock-group-background",
                )
                val tint by animateColorAsState(
                    targetValue = when {
                        active -> Amber
                        enabled -> Bone
                        else -> Color(0xFF5D554D)
                    },
                    animationSpec = tween(160),
                    label = "dock-group-tint",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .clickable(enabled = enabled) {
                            if (group != index) haptics.perform(HapticCue.Selection)
                            onGroup(index)
                        }
                        .padding(horizontal = 4.dp, vertical = 7.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(background)
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = tint,
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    )
                }
            }
        }
        HorizontalDivider(color = Color(0xFF211D19))
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp),
        ) {
            when (group) {
                0 -> {
                    DockHeading("基础影调", onReset = vm::resetLook)
                    ParamSlider(
                        "曝光", vm.exposure, -2f..2f,
                        String.format(Locale.US, "%+.1f EV", vm.exposure),
                        onChange = { v -> vm.updateLive { vm.exposure = v } },
                        onFinished = vm::finishAdjustment,
                        resetValue = 0f,
                    )
                    ParamSlider(
                        "M 滤色（洋红）", vm.mFilter, -1f..1f,
                        String.format(Locale.US, "%+.0f CC", vm.mFilter * DarkroomViewModel.FILTER_CC),
                        onChange = { v -> vm.updateLive { vm.mFilter = v } },
                        onFinished = vm::finishAdjustment,
                        resetValue = 0f,
                    )
                    ParamSlider(
                        "Y 滤色（黄）", vm.yFilter, -1f..1f,
                        String.format(Locale.US, "%+.0f CC", vm.yFilter * DarkroomViewModel.FILTER_CC),
                        onChange = { v -> vm.updateLive { vm.yFilter = v } },
                        onFinished = vm::finishAdjustment,
                        resetValue = 0f,
                    )
                }
                1 -> {
                    DockHeading("颗粒与光晕")
                    ParamSlider(
                        "颗粒", vm.grain, 0f..1f, String.format(Locale.US, "%.2f", vm.grain),
                        onChange = { v -> vm.updateLive { vm.grain = v } },
                        onFinished = vm::finishAdjustment,
                        resetValue = 0.35f,
                    )
                    ParamSlider(
                        "光晕", vm.halation, 0f..1f,
                        String.format(Locale.US, "%.2f", vm.halation),
                        onChange = { v -> vm.updateLive { vm.halation = v } },
                        onFinished = vm::finishAdjustment,
                        resetValue = 0.45f,
                    )
                    Text(
                        "拖动时保留上一帧，松手后由真实光谱引擎重绘。",
                        color = Faint,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                2 -> {
                    DockHeading("胶片与相纸")
                    StockRow(vm, onFilm = onFilm, onPaper = onPaper)
                    Text(
                        "胶片与相纸会改变底片响应，松手后直接生成正式预览。",
                        color = Faint,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                else -> {
                    DockHeading("片边与日期")
                    EdgeRow(vm)
                    if (vm.overscanOn) {
                        ChoiceRow("画幅", FORMATS, vm.filmFormat) {
                            vm.update { vm.filmFormat = it }
                        }
                        ChoiceRow("片门", GATES, vm.gate) {
                            vm.update { vm.gate = it }
                        }
                        ChoiceRow("齿孔那儿看什么", HOLES, vm.holes) {
                            vm.update { vm.holes = it }
                        }
                        ChoiceRow("片边之外", CARRIERS, vm.carrier) {
                            vm.update { vm.carrier = it }
                        }
                        ChoiceRow(
                            "边缘字",
                            listOf(
                                vm.film.name.uppercase(Locale.US) to vm.film.name,
                                "KODAK" to "KODAK",
                                "PORTRA 400" to "PORTRA 400",
                                "FUJIFILM" to "FUJIFILM",
                                "" to "不印",
                            ),
                            vm.edgeText,
                        ) { vm.update { vm.edgeText = it } }
                        ParamSlider(
                            "边缘雾", vm.fog, 0f..2f,
                            String.format(Locale.US, "%.2f", vm.fog),
                            onChange = { v -> vm.updateLive { vm.fog = v } },
                            onFinished = vm::finishAdjustment,
                            resetValue = 1f,
                        )
                    }
                    if (vm.dateOn) {
                        DateRow(
                            vm.dateText,
                            vm.dateFromPhoto,
                            vm.dateMillis,
                            vm.shootDateAvailable,
                            onPick = vm::chooseDate,
                            onUsePhoto = vm::useShootDate,
                        )
                        ChoiceRow("日期位置", CORNERS, vm.dateCorner) {
                            vm.update { vm.dateCorner = it }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun DarkroomLoading() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val transition = rememberInfiniteTransition(label = "darkroom-loading")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(1700, easing = LinearEasing),
            ),
            label = "darkroom-loading-angle",
        )
        Canvas(Modifier.size(34.dp)) {
            drawCircle(
                color = Color(0xFF493B2D),
                radius = size.minDimension / 2f - 2.dp.toPx(),
                style = Stroke(width = 2.dp.toPx()),
            )
            rotate(angle) {
                drawArc(
                    color = Amber,
                    startAngle = -78f,
                    sweepAngle = 92f,
                    useCenter = false,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("正在冲洗底片…", color = Faint, fontSize = 11.sp, letterSpacing = 1.sp)
    }
}

@Composable
private fun BusyBadge(scrubbing: Boolean) {
    val transition = rememberInfiniteTransition(label = "busy-badge")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "busy-badge-pulse",
    )
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xCC1B1815))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(5.dp)
                .clip(RoundedCornerShape(50))
                .background(Amber.copy(alpha = pulse)),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (scrubbing) "快速预览" else "正在冲印",
            color = Amber,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun V2BottomNavigation(
    canUseImage: Boolean,
    canCompare: Boolean,
    comparing: Boolean,
    onOpen: () -> Unit,
    onOriginal: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onSettings: () -> Unit,
) {
    HorizontalDivider(color = Color(0xFF2A241E))
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(Color(0xFF090807))
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        V2NavItem(Icons.Default.Add, "打开", true, false, Modifier.weight(1f), onOpen)
        V2NavItem(
            Icons.Default.Compare,
            "原图",
            canCompare,
            comparing,
            Modifier.weight(1f),
            onOriginal,
        )
        V2NavItem(Icons.Default.Save, "保存", canUseImage, false, Modifier.weight(1f), onSave)
        V2NavItem(Icons.Default.Share, "分享", canUseImage, false, Modifier.weight(1f), onShare)
        V2NavItem(Icons.Default.Settings, "设置", true, false, Modifier.weight(1f), onSettings)
    }
}

@Composable
private fun V2NavItem(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    active: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val haptics = rememberZhaoyeHaptics()
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val tint by animateColorAsState(
        targetValue = when {
            active -> Amber
            enabled -> Bone
            else -> Color(0xFF5D554D)
        },
        animationSpec = tween(150),
        label = "nav-tint",
    )
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "nav-press-scale",
    )
    val activeScale by animateFloatAsState(
        targetValue = if (active) 1.06f else 1f,
        animationSpec = spring(
            dampingRatio = 0.82f,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "nav-active-scale",
    )
    val indicator by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = spring(
            dampingRatio = 0.86f,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "nav-indicator",
    )
    Column(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                enabled = enabled,
            ) {
                haptics.perform(HapticCue.Selection)
                onClick()
            }
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer {
                    scaleX = activeScale
                    scaleY = activeScale
                },
        )
        Spacer(Modifier.height(2.dp))
        Text(label, color = tint, fontSize = 10.sp)
        Spacer(Modifier.height(2.dp))
        Box(
            Modifier
                .width(18.dp)
                .height(2.dp)
                .graphicsLayer { alpha = indicator }
                .clip(RoundedCornerShape(50))
                .background(Amber),
        )
    }
}

@Composable
private fun DockHeading(text: String, onReset: (() -> Unit)? = null) {
    val haptics = rememberZhaoyeHaptics()
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Amber, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        if (onReset != null) {
            Text(
                "重置",
                color = Faint,
                fontSize = 10.sp,
                modifier = Modifier
                    .clickable {
                        haptics.perform(HapticCue.Selection)
                        onReset()
                    }
                    .padding(6.dp),
            )
        }
    }
}

@Composable
private fun BottomActionBar(
    canAdjust: Boolean,
    canCompare: Boolean,
    canExport: Boolean,
    comparing: Boolean,
    onOpen: () -> Unit,
    onAdjust: () -> Unit,
    onCompare: () -> Unit,
    onExport: () -> Unit,
) {
    HorizontalDivider(color = Color(0xFF2A241E))
    Row(
        Modifier
            .fillMaxWidth()
            .height(68.dp)
            .background(Color(0xFF12100E))
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BottomAction(Icons.Default.Add, "打开", true, false, Modifier.weight(1f), onOpen)
        BottomAction(Icons.Default.Tune, "调整", canAdjust, false, Modifier.weight(1f), onAdjust)
        BottomAction(
            Icons.Default.Compare,
            if (comparing) "退出对比" else "对比",
            canCompare,
            comparing,
            Modifier.weight(1f),
            onCompare,
        )
        BottomAction(Icons.Default.Share, "导出", canExport, false, Modifier.weight(1f), onExport)
    }
}

@Composable
private fun BottomAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    active: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val tint = when {
        active -> Amber
        enabled -> Bone
        else -> Color(0xFF5D554D)
    }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(21.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, color = tint, fontSize = 10.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdjustmentSheetV2(
    vm: DarkroomViewModel,
    onFilm: () -> Unit,
    onPaper: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Start at the half-height detent so the photograph remains visible while
    // the user is moving a slider. The sheet can still be dragged up for the
    // longer edge/date controls.
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = Panel,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 720.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("调整", color = Bone, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = vm::resetLook) {
                    Text("重置影调", color = Amber, fontSize = 12.sp)
                }
            }
            GroupTitle("基础")
            ParamSlider(
                "曝光", vm.exposure, -2f..2f, String.format(Locale.US, "%+.1f EV", vm.exposure),
                onChange = { v -> vm.updateLive { vm.exposure = v } },
                onFinished = vm::finishAdjustment,
                resetValue = 0f,
            )
            ParamSlider(
                "M 滤色（洋红）", vm.mFilter, -1f..1f,
                String.format(Locale.US, "%+.0f CC", vm.mFilter * DarkroomViewModel.FILTER_CC),
                onChange = { v -> vm.updateLive { vm.mFilter = v } },
                onFinished = vm::finishAdjustment,
                resetValue = 0f,
            )
            ParamSlider(
                "Y 滤色（黄）", vm.yFilter, -1f..1f,
                String.format(Locale.US, "%+.0f CC", vm.yFilter * DarkroomViewModel.FILTER_CC),
                onChange = { v -> vm.updateLive { vm.yFilter = v } },
                onFinished = vm::finishAdjustment,
                resetValue = 0f,
            )
            GroupTitle("质感")
            ParamSlider(
                "颗粒", vm.grain, 0f..1f, String.format(Locale.US, "%.2f", vm.grain),
                onChange = { v -> vm.updateLive { vm.grain = v } },
                onFinished = vm::finishAdjustment,
                resetValue = 0.35f,
            )
            ParamSlider(
                "光晕", vm.halation, 0f..1f, String.format(Locale.US, "%.2f", vm.halation),
                onChange = { v -> vm.updateLive { vm.halation = v } },
                onFinished = vm::finishAdjustment,
                resetValue = 0.45f,
            )
            GroupTitle("胶片与相纸")
            StockRow(vm, onFilm = onFilm, onPaper = onPaper)
            GroupTitle("片边与日期")
            EdgeRow(vm)
            if (vm.overscanOn) {
                ChoiceRow("画幅", FORMATS, vm.filmFormat) { vm.update { vm.filmFormat = it } }
                ChoiceRow("片门", GATES, vm.gate) { vm.update { vm.gate = it } }
                ChoiceRow("齿孔那儿看什么", HOLES, vm.holes) { vm.update { vm.holes = it } }
                ChoiceRow("片边之外", CARRIERS, vm.carrier) { vm.update { vm.carrier = it } }
                ChoiceRow(
                    "边缘字",
                    listOf(
                        vm.film.name.uppercase(Locale.US) to vm.film.name,
                        "KODAK" to "KODAK",
                        "PORTRA 400" to "PORTRA 400",
                        "FUJIFILM" to "FUJIFILM",
                        "" to "不印",
                    ),
                    vm.edgeText,
                ) { vm.update { vm.edgeText = it } }
                ParamSlider(
                    "边缘雾", vm.fog, 0f..2f, String.format(Locale.US, "%.2f", vm.fog),
                    onChange = { v -> vm.updateLive { vm.fog = v } },
                    onFinished = vm::finishAdjustment,
                    resetValue = 1f,
                )
            }
            if (vm.dateOn) {
                DateRow(
                    vm.dateText,
                    vm.dateFromPhoto,
                    vm.dateMillis,
                    vm.shootDateAvailable,
                    onPick = vm::chooseDate,
                    onUsePhoto = vm::useShootDate,
                )
                ChoiceRow("日期位置", CORNERS, vm.dateCorner) {
                    vm.update { vm.dateCorner = it }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text,
        color = Amber,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 16.dp, bottom = 5.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportOptionsSheet(
    vm: DarkroomViewModel,
    initialTarget: ExportTarget,
    onExport: (ExportTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val haptics = rememberZhaoyeHaptics()
    var target by remember { mutableStateOf(initialTarget) }
    val estimate3200 = vm.estimateExport(3200)
    val estimate4800 = vm.estimateExport(4800)
    val estimate6400 = vm.estimateExport(6400)
    val estimateSource = vm.estimateExport(0)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = Panel,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("导出成片", color = Bone, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(
                "尺寸会立即用于本次导出，但不会重绘当前预览。",
                color = Faint,
                fontSize = 10.sp,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExportTargetChip(
                    "保存到相册",
                    target == ExportTarget.Gallery,
                    Modifier.weight(1f),
                ) { target = ExportTarget.Gallery }
                ExportTargetChip(
                    "系统分享",
                    target == ExportTarget.Share,
                    Modifier.weight(1f),
                ) { target = ExportTarget.Share }
            }
            Spacer(Modifier.height(16.dp))
            Text("成片尺寸", color = Amber, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Text(
                "按照片区域较长的一边计算；开启片边时，最终画布会更大。",
                color = Faint,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 3.dp),
            )
            Spacer(Modifier.height(7.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExportSizeChip(
                    "标准 3200", exportEstimateDetail(estimate3200),
                    vm.exportLongEdge == 3200, Modifier.weight(1f),
                ) {
                    vm.chooseExportLongEdge(3200)
                }
                ExportSizeChip(
                    "高清 4800", exportEstimateDetail(estimate4800),
                    vm.exportLongEdge == 4800, Modifier.weight(1f),
                ) {
                    vm.chooseExportLongEdge(4800)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExportSizeChip(
                    "大图 6400", exportEstimateDetail(estimate6400),
                    vm.exportLongEdge == 6400, Modifier.weight(1f),
                ) {
                    vm.chooseExportLongEdge(6400)
                }
                ExportSizeChip(
                    "片源原尺寸", exportEstimateDetail(estimateSource),
                    vm.exportLongEdge == 0, Modifier.weight(1f),
                ) {
                    vm.chooseExportLongEdge(0)
                }
            }
            if (vm.exportLongEdge == 0) {
                Text(
                    "JPEG 会自动使用 3200；只有 RAW 能按片源原尺寸导出。",
                    color = Faint,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = {
                    haptics.perform(HapticCue.Selection)
                    onExport(target)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Ink),
            ) {
                Text(if (target == ExportTarget.Gallery) "保存到相册" else "打开系统分享")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun exportEstimateDetail(estimate: ExportEstimate): String {
    if (estimate.width <= 0 || estimate.height <= 0 || estimate.bytes <= 0) return "尺寸计算中"
    val mb = estimate.bytes / 1048576.0
    val size = if (mb >= 10.0) {
        String.format(Locale.US, "%.0f MB", mb)
    } else {
        String.format(Locale.US, "%.1f MB", mb)
    }
    return "${estimate.width}×${estimate.height}\n约 $size"
}

@Composable
private fun ExportProgressDialog(progress: ExportProgressUi) {
    val haptics = rememberZhaoyeHaptics()
    val animated by animateFloatAsState(
        targetValue = progress.percent / 100f,
        animationSpec = tween(260),
        label = "export-progress",
    )
    val completed = progress.percent >= 100
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    LaunchedEffect(completed) {
        if (completed) haptics.perform(HapticCue.Confirm)
    }
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        AnimatedVisibility(
            visible = entered,
            enter = fadeIn(tween(180)) +
                scaleIn(tween(180, easing = FastOutSlowInEasing), initialScale = 0.96f),
        ) {
            Surface(
                color = Panel,
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .widthIn(min = 280.dp, max = 330.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AnimatedContent(
                        targetState = completed,
                        transitionSpec = {
                            fadeIn(tween(150)) togetherWith fadeOut(tween(100))
                        },
                        label = "export-title",
                    ) { done ->
                        Text(
                            if (done) "冲印完成" else "正在冲印成片",
                            color = Bone,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(112.dp)) {
                        CircularProgressIndicator(
                            progress = { animated },
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = if (completed) 0.35f else 1f },
                            color = Amber,
                            trackColor = Color(0xFF332C25),
                            strokeWidth = 7.dp,
                        )
                        AnimatedContent(
                            targetState = completed,
                            transitionSpec = {
                                (
                                    fadeIn(tween(150)) +
                                        scaleIn(tween(180), initialScale = 0.82f)
                                    ) togetherWith (
                                    fadeOut(tween(100)) +
                                        scaleOut(tween(100), targetScale = 1.08f)
                                    )
                            },
                            label = "export-result-mark",
                        ) { done ->
                            if (done) {
                                ExportSuccessCheck(Modifier.size(54.dp))
                            } else {
                                Text(
                                    "${(animated * 100).roundToInt()}%",
                                    color = Bone,
                                    fontSize = 25.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(progress.stage, color = Amber, fontSize = 12.sp)
                    Spacer(Modifier.height(5.dp))
                    Text(
                        exportEstimateDetail(progress.estimate).replace('\n', ' '),
                        color = Faint,
                        fontSize = 10.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (completed) "成片已写入" else "全尺寸会使用重叠分片，请保持屏幕亮起",
                        color = Faint,
                        fontSize = 10.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun ExportSuccessCheck(modifier: Modifier = Modifier) {
    val draw = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        draw.animateTo(1f, tween(340, easing = FastOutSlowInEasing))
    }
    Canvas(modifier) {
        fun point(x: Float, y: Float) = Offset(size.width * x, size.height * y)
        val a = point(0.18f, 0.54f)
        val b = point(0.42f, 0.76f)
        val c = point(0.82f, 0.28f)
        val progress = draw.value
        val first = (progress / 0.38f).coerceIn(0f, 1f)
        val second = ((progress - 0.38f) / 0.62f).coerceIn(0f, 1f)
        drawLine(
            color = Amber,
            start = a,
            end = Offset(
                a.x + (b.x - a.x) * first,
                a.y + (b.y - a.y) * first,
            ),
            strokeWidth = 4.dp.toPx(),
            cap = StrokeCap.Round,
        )
        if (second > 0f) {
            drawLine(
                color = Amber,
                start = b,
                end = Offset(
                    b.x + (c.x - b.x) * second,
                    b.y + (c.y - b.y) * second,
                ),
                strokeWidth = 4.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun ExportTargetChip(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val haptics = rememberZhaoyeHaptics()
    Text(
        label,
        color = if (selected) Ink else Bone,
        fontSize = 12.sp,
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) Amber else Color(0xFF2A241E))
            .clickable {
                if (!selected) haptics.perform(HapticCue.Selection)
                onClick()
            }
            .padding(vertical = 10.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

@Composable
private fun ExportSizeChip(
    label: String,
    detail: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val haptics = rememberZhaoyeHaptics()
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) Color(0xFF30251A) else Color(0xFF211D19))
            .border(
                1.dp,
                if (selected) Amber else Color(0xFF332C25),
                RoundedCornerShape(9.dp),
            )
            .clickable {
                if (!selected) haptics.perform(HapticCue.Selection)
                onClick()
            }
            .padding(horizontal = 6.dp, vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            color = if (selected) Amber else Bone,
            fontSize = 11.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Text(
            detail,
            color = if (selected) Color(0xFFD8C9B8) else Faint,
            fontSize = 9.sp,
            lineHeight = 12.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
