package com.shawn.zhaoye

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/**
 * The engine wants real paths: `spk_engine_create` mmaps
 * `spektrafilm_constants.bin` and reads the profile JSON and the SPIR-V
 * kernels from disk. Neither mmap nor fopen reaches into an APK, so the
 * 15 MB of baked resources are unpacked once into filesDir and versioned.
 */
object Assets {

    // android-2: assets/engine/fonts joined the unpack (the three DroidSans
    // faces the edge print is set in), so an installed copy must be redone.
    private const val VERSION = "engine-1.4.0-android-7-tiled-export"

    fun ensureEngine(context: Context): File {
        val root = File(context.filesDir, "engine")
        val marker = File(root, ".unpacked")
        if (marker.isFile && marker.readText() == VERSION) return root
        if (root.exists()) root.deleteRecursively()
        root.mkdirs()
        copy(context, "engine", root)
        marker.writeText(VERSION)
        return root
    }

    private fun copy(context: Context, assetPath: String, target: File) {
        val children = context.assets.list(assetPath) ?: return
        if (children.isEmpty()) {
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        target.mkdirs()
        for (child in children) copy(context, "$assetPath/$child", File(target, child))
    }

    /**
     * The share path's file: cacheDir/share, which is the one directory
     * `res/xml/file_paths.xml` exposes besides the logs. Kept out of MediaStore
     * on purpose -- a print the user chose to *send* should not also land in
     * the gallery.
     */
    fun saveToShare(context: Context, bitmap: Bitmap): File? = runCatching {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        // Old ones are the same print by definition; two or three is enough to
        // keep a chooser that reads late from finding a deleted file.
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(3)?.forEach { it.delete() }
        val file = File(dir, "ZHAOYE_${System.currentTimeMillis()}.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        file
    }.getOrNull()

    /** The system chooser, from the Application context, hence the new task. */
    fun shareImage(context: Context, file: File): Boolean = runCatching {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "分享成片").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrElse {
        Diag.log("EXPORT", "share failed: $it")
        false
    }

    fun saveToGallery(context: Context, bitmap: Bitmap): String? = runCatching {
        val name = "ZHAOYE_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Zhaoye")
            }
        }
        val resolver = context.contentResolver
        val uri: Uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        resolver.openOutputStream(uri)?.use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out) }
        name
    }.getOrNull()
}
