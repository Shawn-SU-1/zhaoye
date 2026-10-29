package com.shawn.zhaoye

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * The diagnostics the settings page exports.
 *
 * Two sources, because one is not enough. The ring buffer below catches what
 * the app itself decided -- which file was opened, how long a render took, why
 * a delta was refused -- and survives without any permission. Logcat catches
 * what the native engine printed (`__android_log_print` never reaches Kotlin),
 * and an app may read its own process's log without READ_LOGS.
 *
 * The export is a plain text file shared through a FileProvider, so it can be
 * mailed or messaged without a cable.
 */
object Diag {

    private const val CAP = 800
    private const val LOGCAT_TAIL = 220_000   // chars; the whole buffer can be megabytes

    private val ring = ArrayDeque<String>()
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** App-side trace. Cheap enough to call on every state change. */
    fun log(tag: String, message: String) {
        synchronized(ring) {
            if (ring.size >= CAP) ring.removeFirst()
            ring.addLast("${stamp.format(Date())} $tag: $message")
        }
    }

    fun clear() = synchronized(ring) { ring.clear() }

    /**
     * Everything the settings page shows. `engine` may be null before the
     * engine finishes coming up, which is itself worth recording.
     */
    fun snapshot(context: Context, engine: EngineHandle?): String {
        val sb = StringBuilder()
        sb.appendLine("照夜 · 诊断报告")
        sb.appendLine("生成时间 ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        sb.appendLine()

        sb.appendLine("## 应用")
        sb.appendLine("包名 ${context.packageName}")
        sb.appendLine("版本 ${versionName(context)} (${versionCode(context)})")
        sb.appendLine("largeHeap ${isLargeHeap(context)}")
        sb.appendLine()

        sb.appendLine("## 设备")
        sb.appendLine("厂商 ${Build.MANUFACTURER}")
        sb.appendLine("型号 ${Build.MODEL}")
        sb.appendLine("设备 ${Build.DEVICE} / ${Build.PRODUCT}")
        sb.appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("ABI ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        memory(context)?.let { sb.appendLine(it) }
        sb.appendLine()

        sb.appendLine("## 引擎")
        sb.appendLine(if (engine?.isReady == true) "就绪" else "未就绪：${engine?.lastError ?: "尚未创建"}")
        sb.appendLine("capabilities ${engine?.capabilities ?: "{}"}")
        sb.appendLine("build ${runCatching { Engine.nativeBuildInfo() }.getOrElse { it.toString() }}")
        sb.appendLine()

        sb.appendLine("## 应用日志（最近 ${CAP} 条）")
        synchronized(ring) {
            if (ring.isEmpty()) sb.appendLine("(空)") else for (line in ring) sb.appendLine(line)
        }
        sb.appendLine()

        sb.appendLine("## Logcat（本进程）")
        sb.appendLine(logcat())
        return sb.toString()
    }

    /** Writes the report under cacheDir and returns it, ready to be shared. */
    fun write(context: Context, engine: EngineHandle?): File {
        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
        // Keep a handful so a second export does not silently overwrite the
        // report that describes the first failure.
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(6)?.forEach { it.delete() }
        val name = "zhaoye-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt"
        val file = File(dir, name)
        file.writeText(snapshot(context, engine))
        return file
    }

    private fun memory(context: Context): String? = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val rt = Runtime.getRuntime()
        "内存 系统总 ${mb(info.totalMem)} · 可用 ${mb(info.availMem)} · 低内存阈值 ${mb(info.threshold)}" +
            "\n进程堆 上限 ${mb(rt.maxMemory())} · 已用 ${mb(rt.totalMemory() - rt.freeMemory())}" +
            "\nlowMemory=${info.lowMemory}"
    }.getOrNull()

    private fun mb(bytes: Long): String = String.format(Locale.US, "%.0f MB", bytes / 1048576.0)

    private fun isLargeHeap(context: Context): Boolean =
        (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_LARGE_HEAP) != 0

    private fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"

    private fun versionCode(context: Context): Long = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    }.getOrNull() ?: 0L

    /**
     * `logcat --pid` on our own process: allowed since Android 4.1 without
     * READ_LOGS. If a ROM disagrees the report still has the ring buffer, so
     * this failing is not fatal.
     */
    private fun logcat(): String = runCatching {
        val process = ProcessBuilder("logcat", "-d", "--pid=${android.os.Process.myPid()}", "-v", "time")
            .redirectErrorStream(true)
            .start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        if (text.length <= LOGCAT_TAIL) text else "…（前 ${text.length - LOGCAT_TAIL} 字符已省略）\n" + text.takeLast(LOGCAT_TAIL)
    }.getOrElse { "logcat 不可用：$it" }
}
