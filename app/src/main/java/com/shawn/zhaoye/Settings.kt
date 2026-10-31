package com.shawn.zhaoye

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

private const val GITHUB = "https://github.com/Shawn-SU-1"
private const val REPO = "https://github.com/Shawn-SU-1/zhaoye"

private data class Credit(val name: String, val detail: String, val url: String?)

private val CREDITS = listOf(
    Credit(
        "spektrafilm · Andrea Volpato",
        "胶片的光谱模型、实测片边与曲线配置、相纸 LUT。\n配置与 LUT 以 CC BY-SA 4.0 授权。",
        "https://github.com/andreavolpato/spektrafilm",
    ),
    Credit(
        "SpektraLab · Hanze Qiu",
        "把上面的引擎移植成 C++/Metal，本 App 的引擎就是它（含本次 Android 补丁）。\nGPL-3.0-or-later。",
        "https://github.com/JamesQiu2005/SpektraLab",
    ),
    Credit(
        "照夜 for Android · 夢島千尋（Shawn）",
        "引擎的 Android 移植：Vulkan 计算、片边与日期后背的 Vulkan 内核、可移植光栅化层。\nGPL-3.0-or-later。",
        REPO,
    ),
)

private val LIBS = listOf(
    "LibRaw" to "RAW 解码 · LGPL-2.1 / CDDL-1.0",
    "stb_truetype" to "字形轮廓 · public domain",
    "DroidSans" to "片边字样 · Apache-2.0",
    "Vulkan / volk" to "计算后端 · Apache-2.0 / MIT",
    "Zoomable" to "预览双指缩放与平移 · Apache-2.0",
)

private enum class SettingsPage(
    val title: String,
    val subtitle: String,
) {
    Root("设置", "照夜偏好、性能与系统信息"),
    General("常规", "成片尺寸与实时预览"),
    Performance("性能与跑分", "设备能力和 App 专用性能测试"),
    Diagnostics("诊断与日志", "日志、设备和问题反馈"),
    Updates("软件更新", "版本检查与国内下载入口"),
    About("关于与许可", "作者、项目、上游与第三方组件"),
}

@Composable
fun SettingsScreen(vm: DarkroomViewModel = viewModel(), onBack: () -> Unit) {
    val context = vm.getApplication<android.app.Application>()
    val haptics = rememberZhaoyeHaptics()
    val scope = rememberCoroutineScope()
    val expansion = remember { Animatable(1f) }
    val scrollState = rememberScrollState()
    var note by remember { mutableStateOf("") }
    var page by rememberSaveable { mutableStateOf(SettingsPage.Root) }
    var openOrigin by remember { mutableStateOf(Offset(0.5f, 0.2f)) }
    val versionName = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"
    // Refresh the manifest when settings opens so download URLs and passwords
    // are not tied to the values fetched at the previous app launch.
    LaunchedEffect(Unit) { vm.checkForUpdates() }

    fun closePage() {
        if (page == SettingsPage.Root) {
            onBack()
        } else {
            scope.launch {
                expansion.animateTo(
                    targetValue = 0.06f,
                    animationSpec = tween(230, easing = FastOutSlowInEasing),
                )
                page = SettingsPage.Root
                expansion.snapTo(1f)
            }
        }
    }

    BackHandler(enabled = page != SettingsPage.Root) {
        closePage()
    }

    LaunchedEffect(page) { scrollState.scrollTo(0) }

    // The title bar is deliberately outside the scroll container: like the
    // system Settings app it stays put while the grouped controls move.
    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .navigationBarsPadding(),
    ) {
        SettingsTopBar(
            title = page.title,
            subtitle = page.subtitle,
            onBack = {
                haptics.perform(HapticCue.Selection)
                closePage()
            },
        )

        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF090807))
                .graphicsLayer {
                    scaleX = 0.06f + 0.94f * expansion.value
                    scaleY = 0.06f + 0.94f * expansion.value
                    alpha = 0.18f + 0.82f * expansion.value
                    transformOrigin = TransformOrigin(openOrigin.x, openOrigin.y)
                }
                .verticalScroll(scrollState)
                .padding(bottom = 24.dp),
        ) {

        if (page == SettingsPage.Root) {
            SettingsRootMenu(
                version = versionName,
                exportLabel = exportSizeLabel(vm.exportLongEdge),
                realtime = vm.realtimePreviewEnabled,
                benchmarkScore = vm.benchmarkScore,
                hasUpdate = vm.updateInfo != null,
                onOpen = { target, origin ->
                    if (page == SettingsPage.Root) {
                        openOrigin = origin
                        scope.launch {
                            expansion.snapTo(0.06f)
                            page = target
                            expansion.animateTo(
                                targetValue = 1f,
                                animationSpec = tween(300, easing = FastOutSlowInEasing),
                            )
                        }
                    }
                },
            )
        }

        if (page == SettingsPage.Diagnostics) SettingsGroup("诊断") {
            Body("出问题的时候先导一份日志：里面是设备与内存、引擎能力、这次的每一条应用事件，以及引擎打印的 logcat。")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Action("导出日志文件", Modifier.weight(1f)) {
                    note = share(context, vm)
                }
                Action("复制到剪贴板", Modifier.weight(1f)) {
                    val text = Diag.snapshot(context, vm.engineForDiag())
                    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clip.setPrimaryClip(ClipData.newPlainText("照夜 诊断", text))
                    note = "已复制 ${text.length} 个字符"
                }
            }
            if (note.isNotEmpty()) {
                Text(
                    note,
                    color = Amber,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            LinkRow("出问题了？去 GitHub 提 issue", "$REPO/issues/new/choose") {
                open(context, "$REPO/issues/new/choose")
            }
        }

        if (page == SettingsPage.Performance) SettingsGroup("性能与实时预览") {
            KeyValue("设备", "${Build.MANUFACTURER} ${Build.MODEL}")
            Body(
                "照夜跑分只测试本 App 的真实渲染路径：800px 完整光谱冲印占 35%、" +
                    "800px 片边冲印占 25%、缓存重印占 15%、1600px 松手收尾占 15%，" +
                    "Vulkan 缓冲占 10%。" +
                    "测试片包含灰阶、彩块、棋盘高频纹理和噪声；不使用安兔兔或网络性能榜。",
            )
            Action(
                when {
                    vm.benchmarkRunning -> "测试中…"
                    vm.benchmarkScore != null -> "重新跑分"
                    else -> "运行照夜跑分"
                },
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                if (!vm.benchmarkRunning) vm.runBenchmark()
            }
        if (vm.benchmarkMessage.isNotEmpty()) {
            Text(
                vm.benchmarkMessage,
                color = if (vm.benchmarkMessage.contains("失败") ||
                    vm.benchmarkMessage.contains("不能")
                ) {
                    SafeRed
                } else {
                    Amber
                },
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        vm.benchmarkScore?.let { score ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Panel)
                    .padding(14.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("$score", color = Amber, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Text(" / 100", color = Faint, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    Text(vm.benchmarkGrade, color = Amber, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                KeyValue("测试算法", "照夜 R4")
                val fullMs = vm.benchmarkFullMs ?: 1
                val reprintMs = vm.benchmarkReprintMs ?: 1
                KeyValue(
                    "实时预览",
                    String.format(java.util.Locale.US, "%.1f fps / %d ms", 1000f / fullMs, fullMs),
                )
                KeyValue(
                    "滤镜重印",
                    String.format(java.util.Locale.US, "%.1f fps / %d ms", 1000f / reprintMs, reprintMs),
                )
                val edgeMs = vm.benchmarkEdgeMs ?: 1
                KeyValue(
                    "片边冲印",
                    String.format(java.util.Locale.US, "%.1f fps / %d ms", 1000f / edgeMs, edgeMs),
                )
                KeyValue("1600px 收尾", "${vm.benchmarkFinalMs ?: 0} ms")
                KeyValue("Vulkan 缓冲", "${vm.benchmarkBufferMb ?: 0} MB")
                Text(
                    when {
                        vm.benchmarkScore!! >= 82 -> "适合实时预览，也适合进行较大尺寸导出。"
                        vm.benchmarkScore!! >= 65 -> "基础调参流畅，建议保留 800px 实时预览。"
                        vm.benchmarkScore!! >= 45 -> "可关闭实时预览以减少拖动时负载。"
                        else -> "建议关闭实时预览，只在松手后冲印。"
                    },
                    color = Faint,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            val recommendation = vm.benchmarkRecommendation
            if (recommendation != null && recommendation != vm.realtimePreviewEnabled) {
                Action(
                    if (recommendation) "采用建议：开启实时预览" else "采用建议：关闭实时预览",
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    vm.useBenchmarkRecommendation()
                }
            } else if (recommendation != null) {
                Text(
                    "当前设置已符合跑分建议",
                    color = Faint,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            }
        }

        if (page == SettingsPage.General) SettingsGroup("实时预览") {
            SettingsSwitchRow(
                title = "拖动时实时冲印",
                subtitle = if (vm.realtimePreviewEnabled) {
                    "已开启 · 使用 800px 光谱预览"
                } else {
                    "已关闭 · 松手后再冲印"
                },
                checked = vm.realtimePreviewEnabled,
                onCheckedChange = {
                    haptics.perform(HapticCue.Selection)
                    vm.setRealtimePreview(it)
                },
            )
            Body("关闭后拖动参数时保留上一帧，可减少旧设备的实时渲染负担。")
        }

        if (page == SettingsPage.General) SettingsGroup("成片尺寸") {
            KeyValue(
                "默认成片尺寸",
                when (vm.exportLongEdge) {
                    0 -> "片源原尺寸（仅 RAW）"
                    3200 -> "标准 3200"
                    4800 -> "高清 4800"
                    6400 -> "大图 6400"
                    else -> vm.exportLongEdge.toString()
                },
            )
        Body(
            "按成片较长的一边计算；数值越大，细节越多，文件也越大。" +
                "这里只决定下一次保存或分享的尺寸，不会立即重绘当前预览。" +
                "每一档的像素量约为：3200 → 7 MP，4800 → 15 MP，" +
                "6400 → 27 MP，原图（A7R3 的 7968）→ 42 MP。“原图长边”是引擎真的按片源尺寸冲印，" +
                "不是放大；JPEG 选择原图时仍会自动按 3200 导出。",
        )
        Body(
            "代价是内存：一条管线同时活几张全尺寸浮点图，42 MP 一张就是 485 MB，" +
                "骁龙 888 这类手机 GPU 到 6400 以上有概率直接掉线（Vulkan 设备丢失）。" +
                "掉线后 App 会自动重建引擎、把这一项退回 3200，换小的再试即可。",
        )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Chip("标准 3200", vm.exportLongEdge == 3200, Modifier.weight(1f)) { vm.chooseExportLongEdge(3200) }
                Chip("高清 4800", vm.exportLongEdge == 4800, Modifier.weight(1f)) { vm.chooseExportLongEdge(4800) }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Chip("大图 6400", vm.exportLongEdge == 6400, Modifier.weight(1f)) { vm.chooseExportLongEdge(6400) }
                Chip("片源原尺寸", vm.exportLongEdge == 0, Modifier.weight(1f)) { vm.chooseExportLongEdge(0) }
            }
        }

        if (page == SettingsPage.Updates) SettingsGroup("软件更新") {
            KeyValue("当前版本", context.packageName + " " + versionName)
            Body("每次打开 App 都会读取实时更新清单；没有更新或网络不可用时不会打扰编辑。")
            Action(
                if (vm.updateChecking) "正在检查…" else "检查更新",
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                if (!vm.updateChecking) vm.checkForUpdates(manual = true)
            }
            if (vm.updateMessage.isNotEmpty()) {
                Text(
                    vm.updateMessage,
                    color = if (vm.updateMessage.startsWith("检查失败")) SafeRed else Amber,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            val downloadInfo = vm.latestRelease ?: UpdateChecker.fallbackInfo(versionName)
            val updateSuffix = if (UpdateChecker.isNewer(downloadInfo.version, versionName)) {
                " · 新版本 ${downloadInfo.version}"
            } else {
                ""
            }
            LinkRow(
                "蓝奏云国内下载$updateSuffix · 密码 ${downloadInfo.domesticPassword}",
                downloadInfo.domesticUrl,
            ) {
                open(context, downloadInfo.domesticUrl)
            }
            LinkRow(
                "百度网盘$updateSuffix · 提取码 ${downloadInfo.baiduPassword}",
                downloadInfo.baiduUrl,
            ) {
                open(context, downloadInfo.baiduUrl)
            }
            LinkRow("GitHub 下载$updateSuffix", downloadInfo.githubUrl) {
                open(context, downloadInfo.githubUrl)
            }
            LinkRow("照夜官方网站", downloadInfo.websiteUrl) {
                open(context, downloadInfo.websiteUrl)
            }
        }

        if (page == SettingsPage.About) SettingsGroup("关于") {
            KeyValue("版本", context.packageName + " " + versionName)
            KeyValue("作者", "夢島千尋 / Shawn")
            LinkRow("项目仓库", REPO) { open(context, REPO) }
            LinkRow("GitHub 主页", GITHUB) { open(context, GITHUB) }
            Body("不是滤镜，是冲印：光穿过胶片、在显影液里长出密度、再被相纸记录下来。")
        }

        if (page == SettingsPage.About) SettingsGroup("名字") {
            Body(
                "照夜，取自唐玄宗的名马「照夜白」——一身白毛，黑夜里也照得见。" +
                    "韩幹为它画过像，那张《照夜白图》今天还在大都会博物馆。",
            )
            Body(
                "暗房也正是这样一间屋子：它黑着，得把光放进去，影才浮出来。" +
                    "这名字不是说要照亮照片，是说让一张底片在没有光的房间里，重新被照亮一次。",
            )
        }

        if (page == SettingsPage.About) SettingsGroup("上游与许可") {
            for (c in CREDITS) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .then(if (c.url != null) Modifier.clickable { open(context, c.url) } else Modifier)
                        .padding(12.dp),
                ) {
                    Text(c.name, color = Bone, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(c.detail, color = Faint, fontSize = 11.sp, lineHeight = 16.sp)
                    if (c.url != null) Text(c.url, color = Amber, fontSize = 10.sp)
                }
            }
            Body(
                "本 App 是上面两位工作的衍生作品，按 GPL-3.0-or-later 分发；" +
                    "spektrafilm 的配置与 LUT 按 CC BY-SA 4.0 使用，修改已记录在仓库的 patches/ 目录。",
            )
        }

        if (page == SettingsPage.About) SettingsGroup("用到的第三方") {
            for ((name, what) in LIBS) KeyValue(name, what)
        }

        if (page == SettingsPage.About) {
            Text(
                "日志不含照片内容，只有文件名、尺寸和耗时。",
                color = Faint,
                fontSize = 10.sp,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
}

@Composable
private fun SettingsTopBar(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(Ink).statusBarsPadding()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(62.dp)
                .padding(start = 6.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Bone,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = Bone,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Serif,
                )
                Text(subtitle, color = Faint, fontSize = 10.sp)
            }
        }
        HorizontalDivider(color = Edge)
    }
}

@Composable
private fun SettingsRootMenu(
    version: String,
    exportLabel: String,
    realtime: Boolean,
    benchmarkScore: Int?,
    hasUpdate: Boolean,
    onOpen: (SettingsPage, Offset) -> Unit,
) {
    SettingsGroup("编辑") {
        SettingsMenuRow(
            icon = Icons.Default.Save,
            title = "常规",
            subtitle = "$exportLabel · 实时预览${if (realtime) "已开启" else "已关闭"}",
        ) { origin -> onOpen(SettingsPage.General, origin) }
    }
    SettingsGroup("系统") {
        SettingsMenuRow(
            icon = Icons.Default.Tune,
            title = "性能与跑分",
            subtitle = benchmarkScore?.let { "当前 ${it}/100 · 重新测试渲染能力" }
                ?: "测试这台手机的照夜渲染性能",
        ) { origin -> onOpen(SettingsPage.Performance, origin) }
        MenuDivider()
        SettingsMenuRow(
            icon = Icons.Default.Build,
            title = "诊断与日志",
            subtitle = "导出诊断文件、复制日志或反馈问题",
        ) { origin -> onOpen(SettingsPage.Diagnostics, origin) }
    }
    SettingsGroup("软件") {
        SettingsMenuRow(
            icon = Icons.Default.Refresh,
            title = "软件更新",
            subtitle = if (hasUpdate) "发现可用新版本" else "当前版本 $version",
        ) { origin -> onOpen(SettingsPage.Updates, origin) }
        MenuDivider()
        SettingsMenuRow(
            icon = Icons.Default.Info,
            title = "关于与许可",
            subtitle = "作者、项目仓库、上游与第三方组件",
        ) { origin -> onOpen(SettingsPage.About, origin) }
    }
}

@Composable
private fun SettingsMenuRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: (Offset) -> Unit,
) {
    val haptics = rememberZhaoyeHaptics()
    val view = LocalView.current
    var center by remember { mutableStateOf(Offset(0.5f, 0.2f)) }
    Row(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInRoot()
                val width = view.width.coerceAtLeast(1)
                val height = view.height.coerceAtLeast(1)
                center = Offset(
                    (bounds.center.x / width).coerceIn(0f, 1f),
                    (bounds.center.y / height).coerceIn(0f, 1f),
                )
            }
            .clickable {
                haptics.perform(HapticCue.Selection)
                onClick(center)
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF292521)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Faint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Bone, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                color = Faint,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Text("›", color = Faint, fontSize = 23.sp)
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Bone, fontSize = 14.sp)
            Text(subtitle, color = Faint, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Ink,
                checkedTrackColor = Amber,
                uncheckedThumbColor = Faint,
                uncheckedTrackColor = Color(0xFF332C25),
            ),
        )
    }
}

@Composable
private fun MenuDivider() {
    HorizontalDivider(
        color = Edge.copy(alpha = 0.55f),
        modifier = Modifier.padding(start = 64.dp),
    )
}

private fun exportSizeLabel(edge: Int): String = when (edge) {
    0 -> "片源原尺寸"
    3200 -> "标准 3200"
    4800 -> "高清 4800"
    6400 -> "大图 6400"
    else -> edge.toString()
}

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Text(
            title,
            color = Amber,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Panel)
                .border(1.dp, Edge, RoundedCornerShape(14.dp))
                .padding(vertical = 4.dp),
            content = content,
        )
    }
}

@Composable
private fun Body(text: String) {
    Text(
        text,
        color = Faint,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
    )
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(key, color = Faint, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Text(value, color = Bone, fontSize = 11.sp)
    }
}

@Composable
private fun LinkRow(label: String, url: String, onClick: () -> Unit) {
    val haptics = rememberZhaoyeHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                haptics.perform(HapticCue.Selection)
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Bone, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(url, color = Amber, fontSize = 10.sp)
        }
        Text("›", color = Faint, fontSize = 22.sp)
    }
}

@Composable
private fun Action(label: String, modifier: Modifier, onClick: () -> Unit) {
    val haptics = rememberZhaoyeHaptics()
    Text(
        label,
        color = Ink,
        fontSize = 12.sp,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Amber)
            .clickable {
                haptics.perform(HapticCue.Selection)
                onClick()
            }
            .padding(vertical = 11.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

@Composable
private fun Chip(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val haptics = rememberZhaoyeHaptics()
    Text(
        label,
        color = if (on) Ink else Bone,
        fontSize = 12.sp,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (on) Amber else Panel)
            .clickable {
                if (!on) haptics.perform(HapticCue.Selection)
                onClick()
            }
            .padding(vertical = 11.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

private fun share(context: Context, vm: DarkroomViewModel): String = runCatching {
    val file = Diag.write(context, vm.engineForDiag())
    val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    // The settings page holds the Application context, and a chooser started
    // from one of those needs its own task; without the flag this throws and
    // the button looks dead.
    context.startActivity(Intent.createChooser(send, "导出日志").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    Diag.log("LOG", "exported ${file.name} (${file.length()} bytes)")
    "已生成 ${file.name}（${file.length() / 1024} KB）"
}.getOrElse {
    Diag.log("LOG", "export failed: $it")
    "导出失败：$it"
}

/**
 * Open a link outside the app. The caller holds the Application context, so the
 * intent needs its own task; without the flag `startActivity` throws, the
 * `runCatching` eats it, and the row looks like a link that does nothing.
 */
private fun open(context: Context, url: String) {
    runCatching {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(view)
        Diag.log("LINK", "opened $url")
    }.onFailure {
        Diag.log("LINK", "failed to open $url: $it")
    }
}
