package com.shawn.zhaoye

import android.graphics.Bitmap
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Thin Kotlin face over the C ABI in `jni_bridge.cpp`.
 *
 * One [EngineHandle] per process (the setup tables are expensive: ~830k CAM16
 * inversions and the Hanatos contraction). One [Session] per opened frame; a
 * print-layer edit reprints from the cached negative, which is why the same
 * session is kept while sliders move.
 */
object Engine {
    private const val TAG = "Zhaoye"

    init {
        System.loadLibrary("spektralab")
    }

    external fun nativeCreate(resourcesDir: String): Long
    external fun nativeDestroy(handle: Long)
    external fun nativeLastError(handle: Long): String
    external fun nativeCapabilities(handle: Long): String
    external fun nativeMemoryReport(handle: Long): String
    external fun nativeParamsSchema(handle: Long): String
    external fun nativeBuildInfo(): String

    external fun nativeOpen(
        handle: Long,
        pixels: FloatArray,
        width: Int,
        height: Int,
        deltaJson: String,
    ): Long

    /**
     * Decodes a RAW file and opens it in one call. `info` is a one-element
     * out-array that receives the decoder's JSON (camera, sizes, timings) or
     * `{"error":...}`.
     */
    external fun nativeOpenRaw(
        handle: Long,
        path: String,
        longEdge: Int,
        deltaJson: String,
        info: Array<String?>,
    ): Long

    external fun nativeSetParams(handle: Long, deltaJson: String): String
    external fun nativeGetParams(handle: Long): String
    external fun nativeProgressPercent(handle: Long): Double
    external fun nativeProgressJson(handle: Long): String

    /** Header (int32 width, int32 height) followed by RGBA8 pixels. */
    external fun nativeRender(handle: Long, tier: String, fullRender: Boolean): ByteArray?
    external fun nativeCloseSession(handle: Long)
}

class EngineHandle private constructor(private val handle: Long) {

    val isReady: Boolean get() = handle != 0L
    val lastError: String get() = if (handle == 0L) "engine not created" else Engine.nativeLastError(handle)
    val capabilities: String get() = if (handle == 0L) "{}" else Engine.nativeCapabilities(handle)
    val memoryReport: String get() = if (handle == 0L) "{}" else Engine.nativeMemoryReport(handle)

    companion object {
        fun create(resourcesDir: String): EngineHandle {
            val handle = Engine.nativeCreate(resourcesDir)
            if (handle == 0L) {
                Log.e("Zhaoye", "engine creation failed: ${Engine.nativeLastError(0)}")
            }
            return EngineHandle(handle)
        }
    }

    /**
     * @param pixels tightly packed float32 RGB, sRGB-encoded 0..1 (the delta
     * says so, so the engine decodes it on ingest).
     */
    fun open(pixels: FloatArray, width: Int, height: Int, deltaJson: String): Session? {
        if (handle == 0L) return null
        val session = Engine.nativeOpen(handle, pixels, width, height, deltaJson)
        if (session == 0L) {
            Log.e("Zhaoye", "open failed: ${Engine.nativeLastError(handle)}")
            return null
        }
        return Session(handle, session)
    }

    /** Returns the session, and fills `info[0]` with the decode report. */
    fun openRaw(path: String, longEdge: Int, deltaJson: String, info: Array<String?>): Session? {
        if (handle == 0L) return null
        val session = Engine.nativeOpenRaw(handle, path, longEdge, deltaJson, info)
        if (session == 0L) {
            Log.w("Zhaoye", "raw open failed: ${info[0]}")
            return null
        }
        return Session(handle, session)
    }

    fun release() {
        if (handle != 0L) Engine.nativeDestroy(handle)
    }
}

class Session internal constructor(private val engine: Long, private val handle: Long) {

    /** Returns the engine's reply, whose `invalidated` field says what a render will rebuild. */
    fun setParams(deltaJson: String): String = Engine.nativeSetParams(handle, deltaJson)

    fun params(): String = Engine.nativeGetParams(handle)
    fun progressPercent(): Float =
        Engine.nativeProgressPercent(handle).toFloat().coerceIn(0f, 100f)
    fun progressJson(): String = Engine.nativeProgressJson(handle)

    /**
     * @param fullRender true runs the whole press again (`spk_render`), false
     * reprints from the cached negative (`spk_reprint`). After a film-side
     * edit the negative is invalid, so the caller passes true.
     */
    fun render(tier: String = "live", fullRender: Boolean = false): Bitmap? {
        val bytes = Engine.nativeRender(handle, tier, fullRender) ?: return null
        if (bytes.size < 8) return null
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val width = header.int
        val height = header.int
        if (width <= 0 || height <= 0) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = ByteBuffer.wrap(bytes, 8, width * height * 4).order(ByteOrder.LITTLE_ENDIAN)
        bitmap.copyPixelsFromBuffer(pixels)
        return bitmap
    }

    fun close() = Engine.nativeCloseSession(handle)
}

/** Decodes a [Bitmap] into the engine's ingest format: float32 RGB, sRGB-encoded. */
fun Bitmap.toIngestPixels(): FloatArray {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    val out = FloatArray(width * height * 3)
    var o = 0
    for (pixel in pixels) {
        out[o++] = ((pixel shr 16) and 0xFF) / 255f
        out[o++] = ((pixel shr 8) and 0xFF) / 255f
        out[o++] = (pixel and 0xFF) / 255f
    }
    return out
}
