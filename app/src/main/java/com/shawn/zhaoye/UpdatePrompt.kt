package com.shawn.zhaoye

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun UpdatePrompt(info: UpdateInfo, visible: Boolean, onDismiss: () -> Unit) {
    if (!visible) return
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("发现新版本 ${info.version}", color = Bone, fontSize = 19.sp)
        },
        text = {
            Column(
                Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "当前版本 ${info.currentVersion}。下载完成后覆盖安装即可。",
                    color = Bone,
                    fontSize = 12.sp,
                )
                if (info.notes.isNotBlank()) {
                    Text(
                        info.notes,
                        color = Faint,
                        fontSize = 11.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(
                    onClick = {
                        openUpdate(context, info.githubUrl)
                        onDismiss()
                    },
                ) { Text("GitHub", color = Faint) }
                TextButton(
                    onClick = {
                        openUpdate(context, info.domesticUrl)
                        onDismiss()
                    },
                ) { Text("国内下载", color = Amber) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("稍后", color = Faint) }
        },
        containerColor = Panel,
    )
}

private fun openUpdate(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        Diag.log("UPDATE", "opened $url")
    }.onFailure {
        Diag.log("UPDATE", "failed to open $url: $it")
        Toast.makeText(context, "没有可打开下载链接的应用", Toast.LENGTH_SHORT).show()
    }
}
