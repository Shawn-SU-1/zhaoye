package com.shawn.zhaoye

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel

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
)

@Composable
fun SettingsScreen(vm: DarkroomViewModel = viewModel(), onBack: () -> Unit) {
    val context = vm.getApplication<android.app.Application>()
    var note by remember { mutableStateOf("") }

    // Edge to edge is the default from target 35 on, so the background has to
    // be painted before the insets are subtracted or the title ends up under
    // the status bar.
    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 6.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Bone)
            }
            Column(Modifier.weight(1f)) {
                Text("设置", color = Bone, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Serif)
                Text("日志、导出尺寸与开源信息", color = Faint, fontSize = 10.sp)
            }
        }

        Section("诊断")
        Body("出问题的时候先导一份日志：里面是设备与内存、引擎能力、这次的每一条应用事件，以及引擎打印的 logcat。")
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            Text(note, color = Amber, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        LinkRow("出问题了？去 GitHub 提 issue", "$REPO/issues/new/choose") {
            open(context, "$REPO/issues/new/choose")
        }

        Divider()

        Section("导出")
        Body(
            "导出长边决定成片的分辨率，每一档的像素量约为：3200 → 7 MP，4800 → 15 MP，" +
                "6400 → 27 MP，原图（A7R3 的 7968）→ 42 MP。“原图长边”是引擎真的按片源尺寸冲印，" +
                "不是放大，成片的每一颗像素都是跑完整条管线算出来的。",
        )
        Body(
            "代价是内存：一条管线同时活几张全尺寸浮点图，42 MP 一张就是 485 MB，" +
                "骁龙 888 这类手机 GPU 到 6400 以上有概率直接掉线（Vulkan 设备丢失）。" +
                "掉线后 App 会自动重建引擎、把这一项退回 3200，换小的再试即可。",
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("3200（稳）", vm.exportLongEdge == 3200, Modifier.weight(1f)) { vm.chooseExportLongEdge(3200) }
            Chip("4800", vm.exportLongEdge == 4800, Modifier.weight(1f)) { vm.chooseExportLongEdge(4800) }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Chip("6400", vm.exportLongEdge == 6400, Modifier.weight(1f)) { vm.chooseExportLongEdge(6400) }
            Chip("原图长边", vm.exportLongEdge == 0, Modifier.weight(1f)) { vm.chooseExportLongEdge(0) }
        }

        Divider()

        Section("关于")
        KeyValue("版本", context.packageName + " " + (runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"))
        KeyValue("作者", "夢島千尋 / Shawn")
        LinkRow("项目仓库", REPO) { open(context, REPO) }
        LinkRow("GitHub 主页", GITHUB) { open(context, GITHUB) }
        Body("不是滤镜，是冲印：光穿过胶片、在显影液里长出密度、再被相纸记录下来。")

        Divider()

        Section("名字")
        Body(
            "照夜，取自唐玄宗的名马「照夜白」——一身白毛，黑夜里也照得见。" +
                "韩幹为它画过像，那张《照夜白图》今天还在大都会博物馆。",
        )
        Body(
            "暗房也正是这样一间屋子：它黑着，得把光放进去，影才浮出来。" +
                "这名字不是说要照亮照片，是说让一张底片在没有光的房间里，重新被照亮一次。",
        )

        Divider()

        Section("上游与许可")
        for (c in CREDITS) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Panel)
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

        Divider()

        Section("用到的第三方")
        for ((name, what) in LIBS) KeyValue(name, what)

        Text(
            "日志不含照片内容，只有文件名、尺寸和耗时。",
            color = Faint,
            fontSize = 10.sp,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        color = Amber,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun Body(text: String) {
    Text(
        text,
        color = Faint,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun Divider() {
    HorizontalDivider(
        color = Edge,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp)) {
        Text(key, color = Faint, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Text(value, color = Bone, fontSize = 11.sp)
    }
}

@Composable
private fun LinkRow(label: String, url: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Panel)
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Bone, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(url, color = Amber, fontSize = 10.sp)
        }
        Text("打开", color = Faint, fontSize = 10.sp)
    }
}

@Composable
private fun Action(label: String, modifier: Modifier, onClick: () -> Unit) {
    Text(
        label,
        color = Ink,
        fontSize = 12.sp,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Amber)
            .clickable { onClick() }
            .padding(vertical = 11.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

@Composable
private fun Chip(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Text(
        label,
        color = if (on) Ink else Bone,
        fontSize = 12.sp,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (on) Amber else Panel)
            .clickable { onClick() }
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
