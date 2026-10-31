package com.shawn.zhaoye

import android.app.Application
import android.content.Context
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Where a finished print goes: the gallery, or straight out to another app. */
enum class ExportTarget { Gallery, Share }

data class ExportEstimate(
    val width: Int,
    val height: Int,
    val bytes: Long,
)

data class ExportProgressUi(
    val percent: Int,
    val stage: String,
    val estimate: ExportEstimate,
)

private data class OverscanTemplate(
    val base: Bitmap?,
    val clean: Bitmap?,
    val gateQuad: FloatArray?,
    val error: String,
)

/**
 * Owns the engine, the current session and the darkroom settings.
 *
 * Every native call runs on one dispatcher: a session is not thread-safe and
 * the engine's pool is shared, so the serialisation is correctness rather than
 * politeness.
 */
class DarkroomViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val BENCHMARK_VERSION = 4
        /**
         * The CC one unit of `m_filter_shift` / `y_filter_shift` is worth. The
         * engine keeps the reference's -1..1 shift in units of
         * `enlarger.filter_shift_scale`, and at its default 1.0 the whole range
         * moves a ~55/65 CC pack by a twentieth of a stop -- invisible. 30 puts
         * the slider where a real dichroic head lives: neutral in the middle,
         * +/-30 CC at the ends, still inside what the pack can give back.
         */
        const val FILTER_CC = 30f
        private const val FILTER_SHIFT_SCALE = 30.0

        /**
         * The date back's own format: Kodak's LCDs print the year as two digits
         * with a leading apostrophe, then the month and day unpadded --
         * "'26 10 29".
         */
        fun todayCode(): String =
            java.text.SimpleDateFormat("''yy M d", java.util.Locale.US).format(java.util.Date())

        /** The same format for any moment, so a photo's own date prints alike. */
        fun codeFor(millis: Long): String =
            java.text.SimpleDateFormat("''yy M d", java.util.Locale.US).format(java.util.Date(millis))

        /** `2026:10:29 18:30:00` (EXIF) to the back's epoch, or null. */
        fun shootMillis(exif: String?): Long? {
            val raw = exif?.trim().orEmpty()
            if (raw.length < 10) return null
            val formats = listOf("yyyy:MM:dd HH:mm:ss", "yyyy-MM-dd HH:mm:ss", "yyyy:MM:dd", "yyyy-MM-dd")
            for (pattern in formats) {
                val parsed = runCatching {
                    java.text.SimpleDateFormat(pattern, java.util.Locale.US)
                        .apply { isLenient = false }
                        .parse(raw)
                }.getOrNull()
                // 1970-01-01 is what a camera writes when its clock was never
                // set; printing that would be worse than printing nothing.
                if (parsed != null && parsed.time > 86_400_000L) return parsed.time
            }
            return null
        }

        /**
         * Each gate's long edge over its short one, from the engine's own
         * `kFormats` table (`engine/src/pipeline/overscan.cpp`):
         * 135 is 36.25 x 24.3, half frame 18 x 24, 645 is 41.5 x 56, 6x6 is
         * square, 6x7 is 69.5 x 56. The gate is a hole of one shape and the
         * engine refuses a frame of another, so this is what the fit measures
         * against.
         */
        private val GATE_ASPECT = mapOf(
            "135" to 36.25 / 24.3,
            "135_half" to 24.0 / 18.0,
            "120_645" to 56.0 / 41.5,
            "120_6x6" to 1.0,
            "120_6x7" to 69.5 / 56.0,
        )

        /** How a gate is named in the status line. */
        private val FORMAT_NAME = mapOf(
            "135" to "135", "135_half" to "135 半格", "120_645" to "645",
            "120_6x6" to "6×6", "120_6x7" to "6×7",
        )

        /** What LibRaw opens. Keep in sync with the picker: anything else goes
         * through BitmapFactory, which is both faster and lossless for JPEG. */
        val RAW_EXTENSIONS = setOf(
            "arw", "srf", "sr2", "nef", "nrw", "cr2", "cr3", "crw", "raf", "orf",
            "rw2", "raw", "pef", "dng", "srw", "x3f", "3fr", "fff", "iiq", "mos",
            "mef", "erf", "kdc", "dcr", "mrw", "rwl", "braw",
        )
    }

    private val native = Dispatchers.Default.limitedParallelism(1)
    private var engine: EngineHandle? = null
    private var session: Session? = null
    private data class RenderRequest(
        val id: Int,
        val full: Boolean,
        val debounceMs: Long,
        val settlesScrub: Boolean,
    )
    private val renders = RenderCoordinator<RenderRequest>(viewModelScope) { request ->
        renderLatest(request)
    }
    /**
     * A line that survives the reopen that follows a recovery: rebuilding the
     * engine reopens the frame, and every step of that overwrites the status,
     * so the one sentence that explains what just happened has to be carried
     * past all of them.
     */
    private var postOpenNotice: String? = null
    private val ticket = AtomicInteger(0)
    private var pendingFile: java.io.File? = null
    private var inspectionJob: Job? = null
    private var comparisonJob: Job? = null
    private var benchmarkJob: Job? = null
    private var updateJob: Job? = null

    // What the current negative was made from, so export can go back to the
    // full-size frame. A launch intent hands over a path rather than a Uri, so
    // both have to be kept -- export used to work only for the picker's Uri.
    private var lastUri: Uri? = null
    private var lastFile: java.io.File? = null
    /** The frame's own shooting date, when its EXIF carried one. */
    private var lastShootMillis: Long? = null

    var preview by mutableStateOf<Bitmap?>(null)
    /**
     * The preview-sized input frame, retained for before/after comparison.
     * RAW has no Bitmap-side decode yet, so this stays null for RAW inputs.
     */
    var originalPreview by mutableStateOf<Bitmap?>(null)
        private set
    var inspectionPreview by mutableStateOf<Bitmap?>(null)
        private set
    var inspectionLoading by mutableStateOf(false)
        private set
    var comparisonPreview by mutableStateOf<Bitmap?>(null)
        private set
    var comparisonLoading by mutableStateOf(false)
        private set
    var realtimePreviewEnabled by mutableStateOf(true)
        private set
    var benchmarkRunning by mutableStateOf(false)
        private set
    var benchmarkScore by mutableStateOf<Int?>(null)
        private set
    var benchmarkFullMs by mutableStateOf<Int?>(null)
        private set
    var benchmarkReprintMs by mutableStateOf<Int?>(null)
        private set
    var benchmarkEdgeMs by mutableStateOf<Int?>(null)
        private set
    var benchmarkFinalMs by mutableStateOf<Int?>(null)
        private set
    var benchmarkBufferMb by mutableStateOf<Int?>(null)
        private set
    var benchmarkMessage by mutableStateOf("")
        private set
    var updateChecking by mutableStateOf(false)
        private set
    var updateInfo by mutableStateOf<UpdateInfo?>(null)
        private set
    /** Newest manifest, even when it is not newer than this installed build. */
    var latestRelease by mutableStateOf<UpdateInfo?>(null)
        private set
    var updatePromptVisible by mutableStateOf(false)
        private set
    var updateMessage by mutableStateOf("")
        private set
    var isScrubbing by mutableStateOf(false)
        private set
    var renderedExposure by mutableStateOf(0f)
        private set
    var renderedMFilter by mutableStateOf(0f)
        private set
    var renderedYFilter by mutableStateOf(0f)
        private set
    var ready by mutableStateOf(false)
    var status by mutableStateOf("正在装载暗房…")
    /** Drives the status line's colour: a failure must not look like a no-op. */
    var statusIsError by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var engineInfo by mutableStateOf("")

    /** Last RAW decode report, shown in the status line. Empty for JPEG. */
    var rawReport by mutableStateOf("")

    /** True once a frame is loaded, so the toolbar can disable export. */
    var hasFrame by mutableStateOf(false)

    var filmId by mutableStateOf("kodak_portra_400")
    var paperId by mutableStateOf("kodak_portra_endura")
    var exposure by mutableStateOf(0f)
    var grain by mutableStateOf(0.35f)
    var halation by mutableStateOf(0.45f)
    var mFilter by mutableStateOf(0f)
    var yFilter by mutableStateOf(0f)

    // The film outside the frame (RFC-032 §26-§27) and the date back (§31).
    // Both are SHOOT layer, so changing any of them re-runs the negative
    // rather than reprinting it -- the edge print is exposure, not a border.
    var overscanOn by mutableStateOf(false)
    var filmFormat by mutableStateOf("auto")
    var gate by mutableStateOf("auto")
    var holes by mutableStateOf("white")
    var carrier by mutableStateOf("none")
    var fog by mutableStateOf(1.0f)
    /** The manufacturer's edge print, e.g. "KODAK PORTRA 400". */
    var edgeText by mutableStateOf("")
    var dateOn by mutableStateOf(false)
    var dateText by mutableStateOf(todayCode())
    var dateCorner by mutableStateOf("br")

    /**
     * Where [dateText] came from: the picture's own EXIF, or a date the user
     * picked. A real date back printed the moment of exposure because it was
     * wired to the shutter; the nearest thing a file can do is carry the
     * camera's clock, so that wins over "today" whenever it exists.
     */
    var dateFromPhoto by mutableStateOf(false)
        private set

    /** Unix millis behind [dateText], for the picker's starting point. */
    var dateMillis by mutableStateOf(System.currentTimeMillis())
        private set

    /** True when the frame on the easel carries its own shooting date. */
    var shootDateAvailable by mutableStateOf(false)
        private set

    /**
     * Export long edge in pixels, or 0 for "the source's own". A7R3 frames are
     * 7952 wide; see [SettingsScreen] and the README for why the default is
     * 3200 and what the ceiling actually is on a phone.
     */
    var exportLongEdge by mutableStateOf(3200)

    /** Non-null only while an export is running or finishing its save animation. */
    var exportProgress by mutableStateOf<ExportProgressUi?>(null)
        private set

    /** ADB-only test hook; the normal UI never changes this. */
    var debugForceTiledExport by mutableStateOf(false)

    fun engineForDiag(): EngineHandle? = engine

    /** A no-op when the launch check is already in flight. */
    fun checkForUpdates(manual: Boolean = false) {
        if (updateJob?.isActive == true) return
        updateChecking = true
        if (manual) updateMessage = "正在检查更新…"
        updateJob = viewModelScope.launch {
            val result = UpdateChecker.check(getApplication())
            updateChecking = false
            result.latest?.let { latestRelease = it }
            val found = result.update
            if (found != null) {
                updateInfo = found
                updatePromptVisible = true
                updateMessage = "发现新版本 ${found.version}"
                Log.i("Zhaoye", "update available=${found.version} current=${found.currentVersion}")
                Diag.log("UPDATE", "available ${found.version} (current ${found.currentVersion})")
            } else if (result.error != null) {
                updateMessage = if (manual) "检查失败：${result.error}" else ""
                Log.w("Zhaoye", "update check failed: ${result.error}")
                Diag.log("UPDATE", "check failed: ${result.error}")
            } else {
                updateInfo = null
                updateMessage = if (manual) "当前已是最新版本" else ""
                Log.i("Zhaoye", "update check: up to date")
                Diag.log("UPDATE", "up to date")
            }
        }
    }

    fun dismissUpdatePrompt() {
        updatePromptVisible = false
    }

    /** The long edge of the frame on the easel, so a message can name a size. */
    private var lastSourceEdge = 0

    /** The frame's own size, for the gate fit: normalized, so any tier will do. */
    private var frameW = 0
    private var frameH = 0

    /** Empty unless the last [gateFit] cropped to reach the gate's shape. */
    private var fitNote = ""

    /**
     * Why the last frame could not even be read. BitmapFactory hands back null
     * without saying why, and `spk_last_error` only knows about the engine, so
     * without this the status line read "显影失败：" and stopped.
     */
    private var readNote = ""

    val film: FilmStock get() = Stocks.film(filmId)
    val paper: PaperStock get() = Stocks.paper(paperId)

    /**
     * The darkroom's last state, so a film choice, a frame edge or an export
     * size survives the app being closed. Nothing here is a document -- it is
     * the enlarger's settings, and a darkroom that forgets them every launch
     * makes every chip feel like a demo.
     */
    private val prefs by lazy {
        getApplication<Application>().getSharedPreferences("darkroom", Context.MODE_PRIVATE)
    }

    private fun restore() {
        val p = prefs
        filmId = p.getString("film", filmId) ?: filmId
        paperId = p.getString("paper", paperId) ?: paperId
        exposure = p.getFloat("exposure", exposure)
        grain = p.getFloat("grain", grain)
        halation = p.getFloat("halation", halation)
        mFilter = p.getFloat("mFilter", mFilter)
        yFilter = p.getFloat("yFilter", yFilter)
        overscanOn = p.getBoolean("overscanOn", overscanOn)
        filmFormat = p.getString("filmFormat", filmFormat) ?: filmFormat
        gate = p.getString("gate", gate) ?: gate
        holes = p.getString("holes", holes) ?: holes
        carrier = p.getString("carrier", carrier) ?: carrier
        fog = p.getFloat("fog", fog)
        edgeText = p.getString("edgeText", edgeText) ?: edgeText
        dateOn = p.getBoolean("dateOn", dateOn)
        dateCorner = p.getString("dateCorner", dateCorner) ?: dateCorner
        exportLongEdge = p.getInt("exportLongEdge", exportLongEdge)
        realtimePreviewEnabled = p.getBoolean("realtimePreviewEnabled", realtimePreviewEnabled)
        benchmarkScore = p.getInt("benchmarkScore", -1).takeIf { it >= 0 }
        benchmarkFullMs = p.getInt("benchmarkFullMs", -1).takeIf { it > 0 }
        benchmarkReprintMs = p.getInt("benchmarkReprintMs", -1).takeIf { it > 0 }
        benchmarkEdgeMs = p.getInt("benchmarkEdgeMs", -1).takeIf { it > 0 }
        benchmarkFinalMs = p.getInt("benchmarkFinalMs", -1).takeIf { it > 0 }
        benchmarkBufferMb = p.getInt("benchmarkBufferMb", -1).takeIf { it > 0 }
        if (p.getInt("benchmarkVersion", 0) != BENCHMARK_VERSION) {
            benchmarkScore = null
            benchmarkFullMs = null
            benchmarkReprintMs = null
            benchmarkEdgeMs = null
            benchmarkFinalMs = null
            benchmarkBufferMb = null
        }
    }

    private fun persist() {
        prefs.edit()
            .putString("film", filmId)
            .putString("paper", paperId)
            .putFloat("exposure", exposure)
            .putFloat("grain", grain)
            .putFloat("halation", halation)
            .putFloat("mFilter", mFilter)
            .putFloat("yFilter", yFilter)
            .putBoolean("overscanOn", overscanOn)
            .putString("filmFormat", filmFormat)
            .putString("gate", gate)
            .putString("holes", holes)
            .putString("carrier", carrier)
            .putFloat("fog", fog)
            .putString("edgeText", edgeText)
            .putBoolean("dateOn", dateOn)
            .putString("dateCorner", dateCorner)
            .putInt("exportLongEdge", exportLongEdge)
            .putBoolean("realtimePreviewEnabled", realtimePreviewEnabled)
            .putInt("benchmarkScore", benchmarkScore ?: -1)
            .putInt("benchmarkFullMs", benchmarkFullMs ?: -1)
            .putInt("benchmarkReprintMs", benchmarkReprintMs ?: -1)
            .putInt("benchmarkEdgeMs", benchmarkEdgeMs ?: -1)
            .putInt("benchmarkFinalMs", benchmarkFinalMs ?: -1)
            .putInt("benchmarkBufferMb", benchmarkBufferMb ?: -1)
            .putInt("benchmarkVersion", BENCHMARK_VERSION)
            .apply()
    }

    /** Export size is set from the settings page, outside [update]. */
    fun chooseExportLongEdge(pixels: Int) {
        if (exportLongEdge == pixels) return
        exportLongEdge = pixels
        persist()
    }

    private var cachedEstimatePreview: Bitmap? = null
    private var cachedPreviewJpegBytes = 0L
    /** First gate from the last overscan render, eight normalised x/y values. */
    @Volatile
    private var overscanGateQuad: FloatArray? = null

    private fun previewJpegBytes(bitmap: Bitmap): Long {
        if (cachedEstimatePreview === bitmap) return cachedPreviewJpegBytes
        cachedPreviewJpegBytes = runCatching {
            ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
                output.size().toLong()
            }
        }.getOrDefault(0L)
        cachedEstimatePreview = bitmap
        return cachedPreviewJpegBytes
    }

    /**
     * Predicts the saved image's pixel dimensions and JPEG size. Dimensions
     * come from the source aspect plus the live overscan canvas; bytes come
     * from a q95 compression of the current preview, scaled for the extra
     * detail a full-resolution render resolves. The UI labels it "about".
     */
    fun estimateExport(requestedLongEdge: Int): ExportEstimate {
        val bitmap = preview
        val sourceW = frameW.takeIf { it > 0 } ?: bitmap?.width ?: 0
        val sourceH = frameH.takeIf { it > 0 } ?: bitmap?.height ?: 0
        if (sourceW <= 0 || sourceH <= 0) return ExportEstimate(0, 0, 0L)

        val isRawInput = lastFile?.let(::isRaw) == true
        val requested = if (requestedLongEdge == 0 && !isRawInput) 3200 else requestedLongEdge
        val crop = gateCropRect()
        val croppedSourceW = (sourceW * (crop?.w ?: 1.0)).roundToInt().coerceAtLeast(1)
        val croppedSourceH = (sourceH * (crop?.h ?: 1.0)).roundToInt().coerceAtLeast(1)
        val sourceLong = max(croppedSourceW, croppedSourceH)
        val baseLong = if (requested == 0) sourceLong else minOf(requested, sourceLong)
        if (baseLong <= 0) return ExportEstimate(0, 0, 0L)

        // The requested value is an output size, not a property of whichever
        // live preview tier happened to be rendered. Scaling it by the live
        // bitmap made 3200 show as 2842 and let a 1600 px preview cap every
        // larger choice at the same dimensions.
        val photoAspect = croppedSourceW.toDouble() / croppedSourceH
        val photoW = if (photoAspect >= 1.0) {
            baseLong
        } else {
            (baseLong * photoAspect).roundToInt().coerceAtLeast(1)
        }
        val photoH = if (photoAspect >= 1.0) {
            (baseLong / photoAspect).roundToInt().coerceAtLeast(1)
        } else {
            baseLong
        }
        val quad = overscanGateQuad
        val gateW = quad?.let { q ->
            val left = (0 until 4).minOf { q[it * 2] }.toDouble()
            val right = (0 until 4).maxOf { q[it * 2] }.toDouble()
            right - left
        } ?: 0.0
        val gateH = quad?.let { q ->
            val top = (0 until 4).minOf { q[it * 2 + 1] }.toDouble()
            val bottom = (0 until 4).maxOf { q[it * 2 + 1] }.toDouble()
            bottom - top
        } ?: 0.0
        val previewAspect = bitmap?.let { it.width.toDouble() / it.height } ?: photoAspect
        val outputW: Int
        val outputH: Int
        if (overscanOn && requested != 0) {
            val outputLong = baseLong
            outputW = if (previewAspect >= 1.0) outputLong
            else (outputLong * previewAspect).roundToInt().coerceAtLeast(1)
            outputH = if (previewAspect >= 1.0)
                (outputLong / previewAspect).roundToInt().coerceAtLeast(1)
            else outputLong
        } else if (overscanOn && gateW > 0.01 && gateH > 0.01) {
            outputW = (photoW / gateW).roundToInt().coerceAtLeast(1)
            outputH = (photoH / gateH).roundToInt().coerceAtLeast(1)
        } else if (overscanOn) {
            val outputLong = max(photoW, photoH)
            outputW = if (previewAspect >= 1.0) outputLong
            else (outputLong * previewAspect).roundToInt().coerceAtLeast(1)
            outputH = if (previewAspect >= 1.0)
                (outputLong / previewAspect).roundToInt().coerceAtLeast(1)
            else outputLong
        } else {
            outputW = photoW
            outputH = photoH
        }

        val outputPixels = outputW.toLong() * outputH.toLong()
        val previewJpeg = bitmap?.let(::previewJpegBytes) ?: 0L
        val previewPixels = bitmap?.let { it.width.toLong() * it.height.toLong() } ?: 0L
        val estimatedBytes = if (previewJpeg > 0 && previewPixels > 0) {
            val pixelScale = outputPixels.toDouble() / previewPixels.toDouble()
            val scalePower = (ln(pixelScale.coerceAtLeast(1.0)) / ln(2.0)).coerceAtLeast(0.0)
            val resolutionFactor = if (overscanOn) {
                1.90 + 0.38 * scalePower
            } else {
                (1.0 - 0.08 * scalePower).coerceAtLeast(0.48)
            }
            val rawBytes = previewJpeg.toDouble() * pixelScale * resolutionFactor
            val bitsPerPixel = (rawBytes * 8.0 / outputPixels).coerceIn(0.45, 3.60)
            (outputPixels * bitsPerPixel / 8.0).roundToLong().coerceAtLeast(32L * 1024L)
        } else {
            val bitsPerPixel = if (overscanOn) 2.0 else 1.15
            (outputPixels * bitsPerPixel / 8.0).roundToLong().coerceAtLeast(32L * 1024L)
        }
        return ExportEstimate(outputW, outputH, estimatedBytes)
    }

    init {
        restore()
        viewModelScope.launch {
            val context = getApplication<Application>()
            val dir = withContext(Dispatchers.IO) { Assets.ensureEngine(context) }
            val created = withContext(native) { EngineHandle.create(dir.absolutePath) }
            engine = created
            ready = created.isReady
            engineInfo = withContext(native) {
                if (created.isReady) Engine.nativeBuildInfo() else created.lastError
            }
            say(if (created.isReady) "暗房就绪" else "引擎启动失败：${created.lastError}", !created.isReady)
            Log.i("Zhaoye", "engine ready=$ready info=$engineInfo")
            // What the GPU will hold, in the log: the export ceiling is a
            // memory question, and every report that asks "why did 42 MP fail"
            // needs these numbers next to it.
            if (created.isReady) Log.i("Zhaoye", "capabilities=" + created.capabilities)
            pendingFile?.let { file ->
                pendingFile = null
                openFile(file)
            }
        }
    }

    /** Same path as [open], for a file the app can already read (adb-driven checks). */
    fun openFile(file: java.io.File) {
        // A launch intent can arrive before the engine has finished its setup
        // tables; hold the frame instead of dropping it.
        if (engine?.isReady != true) {
            pendingFile = file
            return
        }
        lastFile = file
        lastUri = null
        hasFrame = true
        val raw = isRaw(file)
        if (!raw) {
            sourceDimensions(file)?.let { (w, h) ->
                frameW = w
                frameH = h
                lastSourceEdge = maxOf(w, h)
            }
        }
        viewModelScope.launch {
            say(if (raw) "解开 RAW…" else "读入底片…")
            applyShootDate(withContext(Dispatchers.IO) { shootMillis(file, null) })
            busy = true
            val handle = engine
            if (handle == null) {
                say("引擎还没就绪", error = true)
                busy = false
                return@launch
            }
            session?.let { s -> withContext(native) { s.close() } }
            session = null
            ticket.incrementAndGet()
            preview = null
            originalPreview = null
            clearInspection()
            clearComparison()
            readNote = ""
            val opened = openSession(handle, file, 1600, remember = true)
            if (opened == null) {
                // Nothing on the easel means nothing to export; leave the share
                // button lit and the tap only rewrites the footer.
                hasFrame = false
                say(failureText(raw), error = true)
                busy = false
                return@launch
            }
            session = opened
            say(if (raw) "RAW 已上胶卷" else "已上胶卷")
            render(full = true)
        }
    }

    private fun isRaw(file: java.io.File): Boolean = file.extension.lowercase() in RAW_EXTENSIONS

    /** The engine's reason as the same shape LibRaw's report uses, or a fallback. */
    private fun errorJson(handle: EngineHandle): String =
        JSONObject().put("error", handle.lastError.ifEmpty { "引擎没有给出原因" }).toString()

    private fun failureText(raw: Boolean): String {
        val decoded = runCatching {
            val root = JSONTokener(rawReport).nextValue() as? JSONObject
            root?.optString("error")?.takeIf { it.isNotEmpty() }
        }.getOrNull()
        return when {
            decoded != null -> "RAW：$decoded"
            raw -> "这张 RAW 读不出来"
            else -> {
                val reason = engine?.lastError.orEmpty().ifEmpty { readNote }
                if (reason.isEmpty()) "显影失败：引擎没有给出原因" else "显影失败：$reason"
            }
        }
    }

    /**
     * The one place a frame becomes a session, for both kinds of input.
     *
     * A RAW goes through LibRaw on the native side and never crosses JNI as
     * pixels: 42 MP is 254 MB as 16-bit RGB, and a round trip would double it.
     * `remember` is false for export, which opens its own throwaway session at
     * the larger tier and must not disturb the picture on screen.
     */
    private suspend fun openSession(
        handle: EngineHandle,
        file: java.io.File,
        longEdge: Int,
        remember: Boolean,
    ): Session? {
        // Threading lives here so no caller can get it wrong: the native
        // dispatcher is single-threaded for session safety, so file IO and
        // JPEG decoding stay on IO and only `spk_open` runs on native.
        if (isRaw(file)) {
            return withContext(native) {
                val info = arrayOfNulls<String>(1)
                val opened = handle.openRaw(file.absolutePath, longEdge, openingDelta(raw = true), info)
                val report = info[0].orEmpty()
                // Always kept, not just for the preview: when an export's own
                // session fails to open, the reason is in *this* report, and
                // keeping the preview's successful one hid it.
                //
                // `spk_last_error` is thread-local, so a failure that did not
                // come with a report has to be read *here*, on the thread that
                // just failed -- read from the main thread it comes back empty
                // and the user sees "this RAW cannot be read" with no reason.
                rawReport = if (opened == null && !report.contains("\"error\"")) errorJson(handle) else report
                // LibRaw applies EXIF camera orientation while decoding, and
                // `decoded` may describe a half-size preview. Keep the full
                // sensor long edge, but use the decoded/raw orientation to
                // decide whether that sensor rectangle is portrait or
                // landscape. Using decoded dimensions directly made a 42 MP
                // A7R III report only 3984 px while its 1600 preview was open,
                // which capped every export estimate at the same size.
                runCatching {
                    val root = JSONTokener(report).nextValue() as JSONObject
                    fun dims(key: String): Pair<Int, Int>? {
                        val size = root.optString(key)
                        if (!size.matches(Regex("""\d+x\d+"""))) return null
                        val w = size.substringBefore('x').toIntOrNull() ?: return null
                        val h = size.substringAfter('x', "").toIntOrNull() ?: return null
                        return (w to h).takeIf { w > 0 && h > 0 }
                    }
                    val raw = dims("raw")
                    val decoded = dims("decoded") ?: dims("downscaled")
                    if (raw != null) {
                        val sourceLong = maxOf(raw.first, raw.second)
                        val sourceShort = minOf(raw.first, raw.second)
                        val decodedLandscape = decoded?.let { it.first >= it.second } ?: true
                        val orientedW = if (decodedLandscape) sourceLong else sourceShort
                        val orientedH = if (decodedLandscape) sourceShort else sourceLong
                        lastSourceEdge = sourceLong
                        frameW = orientedW
                        frameH = orientedH
                    }
                }
                Log.i("Zhaoye", "raw $report")
                Diag.log("RAW", report)
                opened
            }
        }
        val bitmap = withContext(Dispatchers.IO) { decodeFile(file, longEdge) } ?: return null
        if (frameW <= 0 || frameH <= 0) {
            frameW = bitmap.width
            frameH = bitmap.height
        }
        if (remember) originalPreview = bitmap
        return withContext(native) {
            val opened =
                handle.open(bitmap.toIngestPixels(), bitmap.width, bitmap.height, openingDelta(raw = false))
            if (opened == null) rawReport = errorJson(handle)
            opened
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver.query(
            uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    private fun sourceDimensions(file: File): Pair<Int, Int>? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            bounds.outWidth to bounds.outHeight
        } else {
            null
        }
    }.getOrNull()

    private fun sourceDimensions(uri: Uri): Pair<Int, Int>? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        getApplication<Application>().contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            bounds.outWidth to bounds.outHeight
        } else {
            null
        }
    }.getOrNull()

    /** LibRaw wants a path, so a picked RAW is copied out of the content:// URI first. */
    private fun copyToCache(uri: Uri, extension: String): java.io.File? = runCatching {
        val dir = java.io.File(getApplication<Application>().cacheDir, "raw").apply { mkdirs() }
        val target = java.io.File(dir, "import.$extension")
        getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        target
    }.getOrNull()

    fun open(uri: Uri) {
        viewModelScope.launch {
            say("读入底片…")
            busy = true
            val extension = withContext(Dispatchers.IO) {
                displayName(uri)?.substringAfterLast('.', "")?.lowercase().orEmpty()
            }
            if (extension in RAW_EXTENSIONS) {
                val cached = withContext(Dispatchers.IO) { copyToCache(uri, extension) }
                busy = false
                if (cached == null) {
                    say("这张 RAW 拷不出来", error = true)
                    return@launch
                }
                openFile(cached)
                return@launch
            }
            lastUri = uri
            lastFile = null
            hasFrame = true
            rawReport = ""
            sourceDimensions(uri)?.let { (w, h) ->
                frameW = w
                frameH = h
                lastSourceEdge = maxOf(w, h)
            }
            val handle = engine
            if (handle == null) {
                say("引擎还没就绪")
                busy = false
                return@launch
            }
            // Decode off the native dispatcher: it is single-threaded on
            // purpose (the session is not reentrant), and a JPEG decode is IO.
            applyShootDate(withContext(Dispatchers.IO) { shootMillis(null, uri) })
            val bitmap = withContext(Dispatchers.IO) { decode(getApplication(), uri, 1600) }
            // The frame's own shape has to be known before the gate fit runs,
            // or `gateFit` cannot crop and the engine refuses the print with
            // "a 1080 x 1434 frame is not the 120_6x6 gate's shape". The file
            // path set these two on open; the picker path did not, so a photo
            // opened from 相册 could not use the frame edge at all.
            if (bitmap != null) {
                if (frameW <= 0 || frameH <= 0) {
                    frameW = bitmap.width
                    frameH = bitmap.height
                    lastSourceEdge = maxOf(bitmap.width, bitmap.height)
                }
            }
            session?.let { s -> withContext(native) { s.close() } }
            session = null
            ticket.incrementAndGet()
            preview = null
            originalPreview = null
            clearInspection()
            clearComparison()
            var failure = ""
            val created = withContext(native) {
                val opened = bitmap?.let { frame ->
                    handle.open(
                        frame.toIngestPixels(), frame.width, frame.height, openingDelta(raw = false),
                    )
                }
                if (bitmap != null && opened == null) failure = handle.lastError
                opened
            }
            if (created == null) {
                hasFrame = false
                say(
                    if (bitmap == null) readNote.ifEmpty { "这张照片读不出来" }
                    else "显影失败：${failure.ifEmpty { "引擎没有给出原因" }}",
                    error = bitmap != null,
                )
                busy = false
                return@launch
            }
            session = created
            originalPreview = bitmap
            say("已上胶卷")
            render(full = true)
        }
    }

    /** A print-layer edit reprints from the cached negative; the engine's own
     * `invalidated` answer decides whether that is enough. */
    private fun say(text: String, error: Boolean = false) {
        status = text
        statusIsError = error
        // Every user-visible line is also the app's own trace: the report the
        // settings page exports is mostly these, in order.
        Diag.log(if (error) "E" else "I", text)
    }

    /** Export is a deliberate, occasional action whose only other evidence is a
     * file in the gallery and a line of 11 sp text, so it also gets a toast. */
    private fun announce(text: String) {
        runCatching { Toast.makeText(getApplication(), text, Toast.LENGTH_SHORT).show() }
    }

    fun update(block: () -> Unit) {
        clearInspection()
        clearComparison()
        block()
        persist()
        render(full = false, debounceMs = 70)
    }

    /** Slider movement: update the cheap proxy now and conflate native work. */
    fun updateLive(block: () -> Unit) {
        clearInspection()
        clearComparison()
        block()
        isScrubbing = true
        if (realtimePreviewEnabled) render(full = false, debounceMs = 90)
    }

    fun setRealtimePreview(enabled: Boolean) {
        realtimePreviewEnabled = enabled
        persist()
        if (!enabled) {
            isScrubbing = false
            render(full = false, debounceMs = 0, settlesScrub = true)
        }
    }

    val benchmarkRecommendation: Boolean?
        get() {
            val score = benchmarkScore ?: return null
            val full = benchmarkFullMs ?: return null
            val reprint = benchmarkReprintMs ?: return null
            val edge = benchmarkEdgeMs ?: return null
            return score >= 65 && full <= 150 && edge <= 250 && reprint <= 150
        }

    val benchmarkGrade: String
        get() = when {
            benchmarkScore == null -> "未测试"
            benchmarkScore!! >= 82 -> "S"
            benchmarkScore!! >= 65 -> "A"
            benchmarkScore!! >= 45 -> "B"
            else -> "C"
        }

    /**
     * Runs only the paths that decide whether the editor feels responsive:
     * a complete 800px spectral print, a cached-negative reprint, and the
     * Vulkan buffer ceiling. A neutral synthetic frame keeps scores comparable
     * between phones and between camera files.
     */
    fun runBenchmark() {
        if (benchmarkRunning) return
        val handle = engine
        if (handle?.isReady != true) {
            benchmarkMessage = "引擎尚未就绪，暂时不能跑分"
            return
        }
        benchmarkJob?.cancel()
        benchmarkJob = viewModelScope.launch {
            benchmarkRunning = true
            benchmarkMessage = "正在测试照夜渲染…"
            val width = 1600
            val height = 1200
            var failure = ""
            val result = withContext(native) {
                val input = benchmarkPixels(width, height)
                val session = handle.open(
                    input,
                    width,
                    height,
                    benchmarkOpeningDelta(),
                )
                if (session == null) {
                    failure = handle.lastError.ifEmpty { "无法创建测试会话" }
                    return@withContext null
                }
                try {
                    session.render("live", true) ?: run {
                        failure = handle.lastError.ifEmpty { "预热渲染失败" }
                        return@withContext null
                    }
                    val fullTimes = ArrayList<Long>(3)
                    repeat(3) {
                        val started = System.nanoTime()
                        val frame = session.render("live", true)
                        if (frame == null) {
                            failure = handle.lastError.ifEmpty { "全管线测试失败" }
                            return@withContext null
                        }
                        fullTimes += TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                    }
                    val filterDelta = JSONObject()
                        .put("m_filter_shift", 0.08)
                        .put("preview_long_edge", 800)
                    session.setParams(filterDelta.toString())
                    val reprintTimes = ArrayList<Long>(5)
                    repeat(5) {
                        val started = System.nanoTime()
                        val frame = session.render("live", false)
                        if (frame == null) {
                            failure = handle.lastError.ifEmpty { "重印测试失败" }
                            return@withContext null
                        }
                        reprintTimes += TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                    }
                    session.setParams(
                        JSONObject()
                            .put("overscan_active", true)
                            .put("overscan_format", "135_half")
                            .put("overscan_gate", "auto")
                            .put("overscan_holes", "white")
                            .put("overscan_carrier", "none")
                            .put("overscan_edge_text", "KODAK PORTRA 400")
                            .put("date_imprint_active", false)
                            .toString(),
                    )
                    val edgeTimes = ArrayList<Long>(2)
                    repeat(2) {
                        val started = System.nanoTime()
                        val frame = session.render("live", true)
                        if (frame == null) {
                            failure = handle.lastError.ifEmpty { "片边渲染测试失败" }
                            return@withContext null
                        }
                        edgeTimes += TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                    }
                    session.setParams(
                        JSONObject()
                            .put("preview_long_edge", 1600)
                            .toString(),
                    )
                    val finalStarted = System.nanoTime()
                    val finalFrame = session.render("live", true)
                    if (finalFrame == null) {
                        failure = handle.lastError.ifEmpty { "1600px 收尾帧测试失败" }
                        return@withContext null
                    }
                    val finalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - finalStarted).toInt()
                    val buffer = runCatching {
                        JSONObject(handle.capabilities)
                            .optLong("max_storage_buffer_range", 0L)
                    }.getOrDefault(0L)
                    BenchmarkResult(
                        fullTimes.sorted()[fullTimes.size / 2].toInt(),
                        reprintTimes.sorted()[reprintTimes.size / 2].toInt(),
                        edgeTimes.sorted()[edgeTimes.size / 2].toInt(),
                        finalMs,
                        buffer,
                    )
                } finally {
                    session.close()
                }
            }
            if (result == null) {
                benchmarkMessage = failure
                benchmarkRunning = false
                return@launch
            }
            val fullMs = result.fullMs
            val reprintMs = result.reprintMs
            val edgeMs = result.edgeMs
            // Scores describe observable editor responsiveness, not raw chip
            // benchmarks. 8 fps is the floor for usable interactive feedback;
            // 12 fps makes filter reprints feel close to continuous.
            val fullScore = ((1000.0 / fullMs - 1.0) / 7.0).coerceIn(0.0, 1.0)
            val reprintScore = ((1000.0 / reprintMs - 2.0) / 10.0).coerceIn(0.0, 1.0)
            val edgeScore = ((1000.0 / edgeMs - 0.5) / 7.5).coerceIn(0.0, 1.0)
            val finalScore = ((3500.0 - result.finalMs) / 3000.0).coerceIn(0.0, 1.0)
            val bufferScore = (result.bufferBytes / (512.0 * 1024.0 * 1024.0))
                .coerceIn(0.0, 1.0)
            val score = ((fullScore * 0.35 + edgeScore * 0.25 + reprintScore * 0.15 +
                finalScore * 0.15 + bufferScore * 0.10) * 100)
                .roundToInt()
            benchmarkFullMs = fullMs
            benchmarkReprintMs = reprintMs
            benchmarkEdgeMs = edgeMs
            benchmarkFinalMs = result.finalMs
            benchmarkBufferMb = (result.bufferBytes / (1024.0 * 1024.0))
                .roundToInt()
                .takeIf { it > 0 }
            benchmarkScore = score
            benchmarkMessage = "测试完成"
            persist()
            Diag.log(
                "BENCH",
                "score=$score full=${fullMs}ms edge=${edgeMs}ms reprint=${reprintMs}ms " +
                    "final1600=${result.finalMs}ms buffer=${benchmarkBufferMb ?: 0}MB",
            )
            benchmarkRunning = false
        }
        benchmarkJob?.invokeOnCompletion {
            benchmarkRunning = false
        }
    }

    private data class BenchmarkResult(
        val fullMs: Int,
        val reprintMs: Int,
        val edgeMs: Int,
        val finalMs: Int,
        val bufferBytes: Long,
    )

    fun useBenchmarkRecommendation() {
        val recommendation = benchmarkRecommendation ?: return
        setRealtimePreview(recommendation)
    }

    private fun benchmarkPixels(width: Int, height: Int): FloatArray {
        val pixels = FloatArray(width * height * 3)
        val colors = arrayOf(
            floatArrayOf(.78f, .18f, .14f), floatArrayOf(.80f, .62f, .20f),
            floatArrayOf(.27f, .58f, .25f), floatArrayOf(.16f, .62f, .63f),
            floatArrayOf(.18f, .30f, .70f), floatArrayOf(.54f, .27f, .65f),
            floatArrayOf(.74f, .44f, .20f), floatArrayOf(.20f, .20f, .20f),
        )
        var cursor = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val u = x.toFloat() / (width - 1)
                val v = y.toFloat() / (height - 1)
                val rgb = when {
                    y < height / 5 -> {
                        val step = (u * 16f).toInt().coerceIn(0, 15) / 15f
                        floatArrayOf(step, step, step)
                    }
                    y < height * 2 / 5 -> {
                        val col = (u * 8f).toInt().coerceIn(0, 7)
                        val row = if (y < height * 3 / 10) 0 else 1
                        val base = colors[(col + row * 3) % colors.size]
                        floatArrayOf(base[0], base[1], base[2])
                    }
                    y < height * 3 / 5 -> {
                        val block = (((x / 8) + (y / 8)) and 1) == 0
                        val edge = if (block) 0.94f else 0.03f
                        floatArrayOf(edge, 1f - edge, edge)
                    }
                    y < height * 4 / 5 -> {
                        val n = (((x * 1103515245 + y * 12345) ushr 16) and 255) / 255f
                        floatArrayOf(n, 1f - n, (0.2f + 0.6f * u))
                    }
                    else -> {
                        val radial = (kotlin.math.hypot(
                            (u - 0.5f).toDouble(),
                            (v - 0.72f).toDouble(),
                        ) / 0.72).toFloat()
                        floatArrayOf(
                            (0.5f + 0.5f * kotlin.math.sin(radial * 42f)).coerceIn(0f, 1f),
                            (0.5f + 0.5f * kotlin.math.cos(radial * 31f)).coerceIn(0f, 1f),
                            (0.5f + 0.5f * kotlin.math.sin((u + v) * 55f)).coerceIn(0f, 1f),
                        )
                    }
                }
                pixels[cursor++] = rgb[0].coerceIn(0f, 1f)
                pixels[cursor++] = rgb[1].coerceIn(0f, 1f)
                pixels[cursor++] = rgb[2].coerceIn(0f, 1f)
            }
        }
        return pixels
    }

    private fun benchmarkOpeningDelta(): String = JSONObject()
        .put("film_stock", "kodak_portra_400")
        .put("print_stock", "kodak_portra_endura")
        .put("input_color_space", "sRGB")
        .put("input_cctf_decoding", true)
        .put("output_color_space", "sRGB")
        .put("output_cctf_encoding", true)
        .put("grain_amount", 0.35)
        .put("halation_amount", 0.45)
        .put("preview_long_edge", 800)
        .put("product_defaults", true)
        .toString()

    fun resetLook() {
        update {
            exposure = 0f
            mFilter = 0f
            yFilter = 0f
            grain = 0.35f
            halation = 0.45f
        }
    }

    /** The finger is up: persist once and ask for the best preview. */
    fun finishAdjustment() {
        isScrubbing = false
        persist()
        render(full = false, debounceMs = 0, settlesScrub = true)
    }

    /**
     * A paused zoom asks for a real 3400px print. This runs in its own session,
     * so the normal 1600px preview disappears only when the detail frame is
     * ready and the current pinch position can be kept.
     */
    fun requestInspection() {
        if (inspectionPreview != null || inspectionLoading || !hasFrame) return
        val file = lastFile
        val uri = lastUri
        if (file == null && uri == null) return
        val handle = engine ?: return
        inspectionJob?.cancel()
        inspectionJob = viewModelScope.launch {
            inspectionLoading = true
            val opened = if (file != null) {
                openSession(handle, file, 3400, remember = false)
            } else {
                val frame = withContext(Dispatchers.IO) { decode(getApplication(), uri!!, 3400) }
                frame?.let {
                    withContext(native) {
                        handle.open(
                            it.toIngestPixels(),
                            it.width,
                            it.height,
                            openingDelta(raw = false),
                        )
                    }
                }
            }
            if (opened == null) {
                inspectionLoading = false
                return@launch
            }
            val detail = withContext(native) {
                opened.setParams(delta().toString())
                val frame = opened.render("preview", true)
                opened.close()
                frame
            }
            if (inspectionLoading) inspectionPreview = detail
            inspectionLoading = false
        }
    }

    fun releaseInspection() {
        clearInspection()
    }

    private fun clearInspection() {
        inspectionJob?.cancel()
        inspectionJob = null
        inspectionLoading = false
        inspectionPreview = null
    }

    /**
     * Comparison is about the photograph, so an overscan print is temporarily
     * reprinted without its film carrier and date back. Both sides then have
     * the source frame's aspect ratio and align without guessing at the gate
     * position.
     */
    fun requestComparison() {
        if (!overscanOn) {
            clearComparison()
            return
        }
        if (comparisonPreview != null || comparisonLoading) return
        val file = lastFile
        val uri = lastUri
        if (file == null && uri == null) return
        val handle = engine ?: return
        comparisonJob?.cancel()
        comparisonJob = viewModelScope.launch {
            comparisonLoading = true
            val opened = if (file != null) {
                openSession(handle, file, 1600, remember = false)
            } else {
                val frame = withContext(Dispatchers.IO) { decode(getApplication(), uri!!, 1600) }
                frame?.let {
                    withContext(native) {
                        handle.open(
                            it.toIngestPixels(),
                            it.width,
                            it.height,
                            openingDelta(raw = false),
                        )
                    }
                }
            }
            if (opened == null) {
                comparisonLoading = false
                return@launch
            }
            val compareDelta = delta()
                .put("overscan_active", false)
                .put("date_imprint_active", false)
            val frame = withContext(native) {
                opened.setParams(compareDelta.toString())
                val rendered = opened.render("live", true)
                opened.close()
                rendered
            }
            comparisonPreview = frame
            comparisonLoading = false
        }
    }

    fun releaseComparison() {
        clearComparison()
    }

    private fun clearComparison() {
        comparisonJob?.cancel()
        comparisonJob = null
        comparisonLoading = false
        comparisonPreview = null
    }

    /** The frame's own name is what a real edge print carries. */
    private fun defaultEdgeText(): String = film.name.uppercase(java.util.Locale.US)

    fun toggleOverscan() = update {
        overscanOn = !overscanOn
        if (overscanOn && edgeText.isEmpty()) edgeText = defaultEdgeText()
    }

    /** A date picked by hand: turns the back on and stops it following the file. */
    fun chooseDate(millis: Long) = update {
        dateMillis = millis
        dateText = codeFor(millis)
        dateFromPhoto = false
        dateOn = true
    }

    /** Back to the file's own date, when the frame on the easel has one. */
    fun useShootDate() {
        val own = lastShootMillis ?: return
        update {
            dateMillis = own
            dateText = codeFor(own)
            dateFromPhoto = true
        }
    }

    /**
     * The camera's clock for the frame on the easel. Called on every open, so a
     * new picture never inherits the previous one's date; a file with no
     * readable EXIF date keeps "now", which is what a body with a dead
     * battery-back would have set anyway.
     */
    private fun applyShootDate(millis: Long?) {
        lastShootMillis = millis
        shootDateAvailable = millis != null
        dateFromPhoto = millis != null
        dateMillis = millis ?: System.currentTimeMillis()
        dateText = codeFor(dateMillis)
    }

    /** EXIF from a file or a picked URI, best effort: a RAW or a stripped JPEG has none. */
    private fun shootMillis(file: java.io.File?, uri: Uri?): Long? = runCatching {
        val attribute = if (file != null) {
            android.media.ExifInterface(file.absolutePath)
        } else {
            getApplication<Application>().contentResolver.openInputStream(uri!!)?.use {
                android.media.ExifInterface(it)
            }
        }
        val raw = attribute?.getAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: attribute?.getAttribute(android.media.ExifInterface.TAG_DATETIME)
        shootMillis(raw)
    }.getOrNull()

    fun toggleDate() = update {
        dateOn = !dateOn
        if (dateOn && dateText.isEmpty()) dateText = todayCode()
    }

    fun importStock(film: String? = null, paper: String? = null) {
        if (film != null) {
            filmId = film
            Stocks.film(film).targetPaper?.let { paperId = it }
            // Edge printing is part of the stock itself. Keep it in step while
            // the user changes emulsions instead of leaving the previous name.
            edgeText = defaultEdgeText()
        }
        if (paper != null) paperId = paper
        persist()
        render(full = true)
    }

    /** `raw` switches the input contract: LibRaw hands back linear ProPhoto. */
    private fun openingDelta(raw: Boolean): String = JSONObject()
        .put("film_stock", filmId)
        // Slides are already positives. They must bypass the negative-to-paper
        // stage and be scanned directly, or the complete negative pipeline
        // prints them as an inverted image.
        .put("scan_film", film.positive)
        .put("print_stock", paperId)
        .put("input_color_space", if (raw) "ProPhoto RGB" else "sRGB")
        .put("input_cctf_decoding", !raw)
        .put("output_color_space", "sRGB")
        .put("output_cctf_encoding", true)
        .put("grain_amount", grain.toDouble())
        .put("halation_amount", halation.toDouble())
        .put("filter_shift_scale", FILTER_SHIFT_SCALE)
        // Open-only switch, stripped before the delta is validated. Without it
        // the enlarger holds the print's brightness against Camera Exp. Comp.
        // -- which is the reference's behaviour and exactly why the reference
        // also needs a "Print auto compensation" switch that SpektraLab does
        // not have. Asking for the product defaults makes the exposure slider
        // move the finished print, which is what a slider labelled EV must do.
        .put("product_defaults", true)
        .toString()

    /** Clamped to the engine's own schema: an out-of-range field is refused and
     * takes the whole delta with it, which looks exactly like a dead slider. */
    private fun delta(): JSONObject = JSONObject()
        .put("film_stock", filmId)
        // Also sent when a stock is changed inside an open session, so moving
        // between slide and negative film cannot leave direct-scan mode stale.
        .put("scan_film", film.positive)
        .put("print_stock", paperId)
        // Drags use the engine's own 800px interactive tier. The alternative
        // was a display-side colour matrix, but exposure is shaped by the film
        // and paper curves, not by a neutral brightness scale.
        .put("preview_long_edge", if (isScrubbing) 800 else 1600)
        .put("exposure_compensation_ev", exposure.coerceIn(-2f, 2f).toDouble())
        .put("m_filter_shift", mFilter.coerceIn(-1f, 1f).toDouble())
        .put("y_filter_shift", yFilter.coerceIn(-1f, 1f).toDouble())
        .put("grain_amount", grain.coerceIn(0f, 1f).toDouble())
        .put("halation_amount", halation.coerceIn(0f, 1f).toDouble())
        .put("filter_shift_scale", FILTER_SHIFT_SCALE)
        .put("overscan_active", overscanOn)
        .put("overscan_format", resolveFormat())
        .put("overscan_gate", gate)
        .put("overscan_holes", holes)
        .put("overscan_carrier", carrier)
        .put("overscan_fog", fog.coerceIn(0f, 4f).toDouble())
        .put("overscan_edge_text", edgeText)
        .put("date_imprint_active", dateOn)
        .put("date_imprint_text", dateText)
        .put("date_imprint_corner", dateCorner)
        .apply {
            // A 135 gate is a 3:2 hole; a 4:3 phone photo cannot go through
            // it and the engine says so and refuses. Cropping is what film
            // does -- the gate decides -- so the fit is applied here, as the
            // geometry node's normalized crop, which costs no second decode.
            //
            // Sent on every delta, identity included: `set_params` is a
            // delta, so leaving the key out after turning 片边 off would keep
            // the last crop on screen for good.
            val fit = gateFit() ?: doubleArrayOf(0.0, 0.0, 1.0, 1.0)
            put("geometry_crop_x", fit[0])
            put("geometry_crop_y", fit[1])
            put("geometry_crop_w", fit[2])
            put("geometry_crop_h", fit[3])
        }

    /**
     * The centre crop that puts the frame inside the selected gate, as the
     * geometry node's normalized (x, y, w, h), or null when the frame already
     * is the gate's shape (within the 5 % the engine allows).
     *
     * [fitNote] carries the sentence for the status bar.
     */
    /**
     * The gate to print with. "auto" is not a gate: it picks the one whose
     * shape is closest to the frame's, so the picture is not trimmed to fit a
     * hole it never had. A 4:3 phone frame lands on 135 half (18 x 24, exactly
     * 4:3), a 3:2 one on 135, a square one on 6x6.
     */
    private fun resolveFormat(): String {
        if (filmFormat != "auto") return filmFormat
        if (frameW <= 0 || frameH <= 0) return "135"
        val r = maxOf(frameW, frameH).toDouble() / minOf(frameW, frameH).toDouble()
        return GATE_ASPECT.minByOrNull { (_, g) -> kotlin.math.abs(kotlin.math.ln(r / g)) }?.key ?: "135"
    }

    /** "135 半格" 这种，给状态栏和片边那颗 chip 用。 */
    val effectiveFormatLabel: String
        get() = FORMAT_NAME[resolveFormat()] ?: resolveFormat()

    private fun gateFit(): DoubleArray? {
        fitNote = ""
        val crop = gateCropRect() ?: return null
        // Auto already picked the closest gate; whatever is left is under a
        // percent and not worth a sentence. A gate the user picked by hand is
        // worth one, with the way out.
        if (filmFormat != "auto") {
            fitNote = " · %s 片门比照片%s，已居中裁 %.0f%%（画幅选自动可避开）"
                .format(
                    FORMAT_NAME[resolveFormat()] ?: resolveFormat(),
                    if (crop.alongX) "窄" else "扁",
                    crop.lostPercent,
                )
        }
        return doubleArrayOf(crop.x, crop.y, crop.w, crop.h)
    }

    private data class GateCrop(
        val x: Double,
        val y: Double,
        val w: Double,
        val h: Double,
        val alongX: Boolean,
        val lostPercent: Double,
    )

    /** Gate crop without the status-line side effect used by [gateFit]. */
    private fun gateCropRect(): GateCrop? {
        if (!overscanOn || frameW <= 0 || frameH <= 0) return null
        val fmt = resolveFormat()
        val gate = GATE_ASPECT[fmt] ?: return null
        val long = maxOf(frameW, frameH).toDouble()
        val short = minOf(frameW, frameH).toDouble()
        val ratio = long / short
        if (kotlin.math.abs(ratio / gate - 1.0) <= 0.02) return null
        // Shrink whichever axis is too long: the short one when the frame is
        // too square, the long one when it is too wide.
        val trimShort = ratio < gate
        val keep = if (trimShort) ratio / gate else gate / ratio
        val widthIsShort = frameW <= frameH
        val alongX = trimShort == widthIsShort
        val w = if (alongX) keep else 1.0
        val h = if (alongX) 1.0 else keep
        val lost = (1.0 - keep) * 100.0
        return GateCrop((1.0 - w) / 2.0, (1.0 - h) / 2.0, w, h, alongX, lost)
    }

    private class Applied(val error: String?, val needsFull: Boolean)

    fun render(full: Boolean, debounceMs: Long = 160, settlesScrub: Boolean = false) {
        if (session == null) return
        renders.submit(RenderRequest(ticket.incrementAndGet(), full, debounceMs, settlesScrub))
    }

    private suspend fun renderLatest(request: RenderRequest) {
        if (request.debounceMs > 0) kotlinx.coroutines.delay(request.debounceMs)
        if (request.id != ticket.get()) return
        val current = session ?: return
        busy = true
        val applied = withContext(native) {
            val reply = current.setParams(delta().toString())
            val root = runCatching { JSONTokener(reply).nextValue() }.getOrNull() as? JSONObject
            val error = root?.optString("error")?.takeIf { it.isNotEmpty() }
            // `invalidated` is the string "shoot" or "print". Anything else,
            // including an error reply, must fall back to the expensive path.
            val invalidated = root?.optString("invalidated").orEmpty()
            Applied(error, request.full || invalidated != "print")
        }
        if (request.id != ticket.get()) return
        if (applied.error != null) {
            say("参数被退回：${applied.error}", error = true)
            busy = false
            return
        }
        val started = System.currentTimeMillis()
        var failure = ""
        val bitmap = withContext(native) {
            val rendered = current.render("live", applied.needsFull)
            if (rendered == null) failure = engine?.lastError.orEmpty()
            val gate = if (overscanOn) readFirstOverscanGate(current) else null
            if (gate == null && overscanOn) Diag.log("EXPORT", "overscan gate unavailable after live render")
            overscanGateQuad = gate
            rendered
        }
        Diag.log(
            "R",
            "live ${if (applied.needsFull) "full" else "reprint"} " +
                "${System.currentTimeMillis() - started} ms -> " +
                (bitmap?.let { "${it.width}x${it.height}" } ?: "null"),
        )
        if (request.id != ticket.get()) return
        if (bitmap != null) {
            preview = bitmap
            renderedExposure = exposure
            renderedMFilter = mFilter
            renderedYFilter = yFilter
            if (request.settlesScrub) isScrubbing = false
            val notice = postOpenNotice
            if (notice != null) {
                postOpenNotice = null
                say(notice, error = true)
            } else {
                val ms = System.currentTimeMillis() - started
                Diag.log("R", "published ${bitmap.width}x${bitmap.height} in $ms ms")
                say("冲印完成$fitNote")
            }
        } else if (failure.contains("gate's shape") && filmFormat != "auto") {
            filmFormat = "auto"
            persist()
            say("这个画幅装不下这张照片的形状，已切回「画幅 自动」")
            render(full = true)
            return
        } else {
            say("渲染失败：${failure.ifEmpty { "引擎没有给出原因" }}", error = true)
        }
        busy = false
    }

    /**
     * Renders at export resolution and hands the print to [target]: the
     * gallery, or another app through the system chooser.
     */
    fun export(to: ExportTarget = ExportTarget.Gallery, onDone: (String) -> Unit = {}) {
        val file = lastFile
        val uri = lastUri
        if (file == null && uri == null) {
            say("先打开一张底片", error = true)
            return
        }
        viewModelScope.launch {
            busy = true
            val estimate = estimateExport(exportLongEdge)
            Diag.log(
                "EXPORT",
                "estimate long-edge=${if (exportLongEdge == 0) "source" else exportLongEdge} " +
                    "-> ${estimate.width}x${estimate.height}",
            )
            exportProgress = ExportProgressUi(0, "准备底片", estimate)
            say("按原图重新冲印…")
            try {
            val handle = engine
            if (handle == null) {
                say("引擎还没就绪")
                busy = false
                return@launch
            }
            // A RAW export decodes again at the larger tier -- 3984 px from the
            // half-size demosaic, which still resolves the 3200 px this tier
            // renders at.
            val requested = exportLongEdge
            if (requested == 0 && (file == null || !isRaw(file))) {
                // The JPEG path hands the frame to the engine as a Java
                // FloatArray, and 42 MP of that is 506 MB: it cannot be
                // allocated, so say so rather than die.
                say("原图尺寸只对 RAW 可用；JPEG 走 FloatArray 会超过堆上限，已按 3200 导出", error = true)
            }
            val target = if (requested == 0 && (file == null || !isRaw(file))) 3200 else requested
            val previewLong = preview?.let { maxOf(it.width, it.height) }
            val overscanFinalScale = if (target != 0 && overscanOn && previewLong != null) {
                (previewLong.toDouble() / minOf(1600, maxOf(frameW, frameH)).coerceAtLeast(1))
                    .coerceIn(0.65, 1.35)
            } else {
                1.0
            }
            // The visible tier is requested in decoded pixels. A gate crop and
            // the film margin make the finished canvas smaller, so a request
            // for a 3200 px *finished* long edge needs a proportionally larger
            // decode. The source-size option deliberately keeps the source's
            // own pixel density instead.
            val sessionTarget = if (target > 0 && overscanOn) {
                kotlin.math.ceil(target / overscanFinalScale).roundToInt()
                    .coerceAtMost(maxOf(target, lastSourceEdge.takeIf { it > 0 } ?: Int.MAX_VALUE))
            } else {
                target
            }
            val opened = if (file != null) {
                openSession(handle, file, sessionTarget, remember = false)
            } else {
                val frame = withContext(Dispatchers.IO) { decode(getApplication(), uri!!, sessionTarget) }
                if (frame == null) {
                    say("原图读不出来", error = true)
                    announce(status)
                    busy = false
                    return@launch
                }
                withContext(native) {
                    handle.open(
                        frame.toIngestPixels(), frame.width, frame.height, openingDelta(raw = false),
                    )
                }
            }
            if (opened == null) {
                say(if (file != null && isRaw(file)) failureText(true) else "导出失败：${engine?.lastError}", error = true)
                announce(status)
                busy = false
                return@launch
            }
            Diag.log("GPU", "source export memory before render: ${handle.memoryReport}")
            exportProgress = ExportProgressUi(8, "准备冲印", estimate)
            val started = System.currentTimeMillis()
            // `preview` renders at 3400 (the tier the app was built around);
            // a request for the source's own size has to ask for `full`, or the
            // long decode is thrown away by a 3400 px render.
            // The source was decoded to `target`, so the render has to be
            // `full` (= the source's own size) for any target above the
            // 3400 px `preview` tier, or the long decode is thrown away.
            val tier = if (target == 0 || target > 3400) "full" else "preview"
            var failure = ""
            val targetEstimate = estimateExport(target)
            val estimatedPixels =
                targetEstimate.width.toLong() * targetEstimate.height.toLong()
            val largeSource = (file?.let(::isRaw) == true || debugForceTiledExport) &&
                (target == 0 || estimatedPixels > 16_000_000L)
            val baseExportParams = delta()
            val cropX = baseExportParams.optDouble("geometry_crop_x", 0.0)
            val cropY = baseExportParams.optDouble("geometry_crop_y", 0.0)
            val cropW = baseExportParams.optDouble("geometry_crop_w", 1.0)
            val cropH = baseExportParams.optDouble("geometry_crop_h", 1.0)
            val croppedWidth = (frameW * cropW).roundToInt().coerceAtLeast(1)
            val croppedHeight = (frameH * cropH).roundToInt().coerceAtLeast(1)
            val outputWidth: Int
            val outputHeight: Int
            val sourceEdge = maxOf(frameW, frameH).coerceAtLeast(1)
            val decodeScale = if (target == 0) 1.0
            else sessionTarget.toDouble() / sourceEdge
            if (target == 0) {
                outputWidth = croppedWidth
                outputHeight = croppedHeight
            } else {
                outputWidth = (croppedWidth * decodeScale).roundToInt().coerceAtLeast(1)
                outputHeight = (croppedHeight * decodeScale).roundToInt().coerceAtLeast(1)
            }
            var tiled = if (largeSource) {
                renderSourceTiled(
                    handle,
                    file,
                    opened,
                    baseExportParams,
                    outputWidth,
                    outputHeight,
                ) { percent, stage ->
                    exportProgress = ExportProgressUi(percent, stage, estimate)
                }
            } else {
                val renderTask = async(native) {
                    opened.setParams(baseExportParams.toString())
                    val bitmap = opened.render(tier, true)
                    // Thread-local error: read it before leaving this thread.
                    if (bitmap == null) failure = handle.lastError.orEmpty()
                    bitmap
                }
                val renderStarted = System.currentTimeMillis()
                while (!renderTask.isCompleted) {
                    delay(120)
                    val raw = opened.progressPercent()
                    val elapsed = System.currentTimeMillis() - renderStarted
                    val percent = if (raw > 0f) {
                        (10f + raw * 0.80f).roundToInt()
                    } else {
                        (10f + elapsed / 250f).roundToInt().coerceAtMost(18)
                    }.coerceIn(10, 90)
                    exportProgress = ExportProgressUi(percent, "冲印中", estimate)
                }
                val rendered = renderTask.await()
                val renderProgress = runCatching { JSONObject(opened.progressJson()) }
                    .getOrDefault(JSONObject())
                Diag.log(
                    "EXPORT",
                    "whole render auto_exposure_ev=" +
                        (if (renderProgress.isNull("auto_exposure_ev")) {
                            "null"
                        } else {
                            renderProgress.optDouble("auto_exposure_ev", 0.0).toString()
                        }),
                )
                rendered to failure
            }
            if (largeSource && overscanOn && tiled.first != null && file != null) {
                exportProgress = ExportProgressUi(88, "准备片边", estimate)
                val template = renderOverscanTemplate(handle, file, baseExportParams)
                if (template.base != null) {
                    exportProgress = ExportProgressUi(93, "合成片边", estimate)
                    val composed = withContext(Dispatchers.Default) {
                        composeFullOverscan(
                            tiled.first!!,
                            template.base!!,
                            template.clean,
                            template.gateQuad,
                        )
                    }
                    tiled.first?.recycle()
                    template.base?.recycle()
                    template.clean?.recycle()
                    tiled = composed
                } else {
                    tiled.first?.recycle()
                    tiled = template.base to template.error
                }
            }
            val renderedOut = tiled.first
            failure = tiled.second
            withContext(native) { opened.close() }
            val out = if (renderedOut != null && target != 0 &&
                estimate.width > 0 && estimate.height > 0 &&
                (renderedOut.width != estimate.width || renderedOut.height != estimate.height)
            ) {
                runCatching {
                    Bitmap.createScaledBitmap(
                        renderedOut,
                        estimate.width,
                        estimate.height,
                        true,
                    )
                }.getOrNull()?.also { scaled ->
                    if (scaled !== renderedOut) renderedOut.recycle()
                } ?: renderedOut
            } else {
                renderedOut
            }
            Diag.log(
                "R",
                "export long-edge=${if (target == 0) "source" else target.toString()} " +
                    "${System.currentTimeMillis() - started} ms -> " +
                    (out?.let { "${it.width}x${it.height}" } ?: "null"),
            )
            if (out == null) {
                val message = failure
                Diag.log("GPU", "source export failed; memory after render: ${handle.memoryReport}")
                if (message.contains("VkResult -4") || message.contains("device lost", true)) {
                    // A lost Vulkan device poisons the engine that owned it:
                    // every later dispatch fails the same way. Say which size
                    // did it, then build a new engine so the app is usable.
                    // The source's own long edge, for the MP figure below.
                    val edge = if (target == 0) lastSourceEdge else target
                    val size = if (target == 0) "片源原尺寸" else "${target} 长边"
                    say("GPU 在${size}上掉线了：这台手机撑不住这个尺寸，正在重建引擎…", error = true)
                    announce(status)
                    // 3:2 frames: MP = edge * (edge / 1.5) / 1e6, so the long
                    // edge alone gives a figure close enough to name the wall.
                    val mp = if (edge > 0) "（约 ${edge * edge / 1_500_000} MP）" else ""
                    recoverEngine(
                        "GPU 掉线了：${size}${mp}触发了显卡驱动限制或长任务超时。已重建引擎，导出长边退回 3200。",
                    )
                    busy = false
                    return@launch
                }
                say("导出失败：$message", error = true)
                announce(status)
                busy = false
                return@launch
            }
            if (to == ExportTarget.Share) {
                exportProgress = ExportProgressUi(96, "写入成片", estimate)
                // The bitmap goes to cacheDir and leaves through the app's own
                // FileProvider: a share target cannot read another app's
                // files, and a MediaStore uri would also put the print in the
                // gallery, which is the other menu item's job.
                val cached = withContext(Dispatchers.IO) { Assets.saveToShare(getApplication(), out) }
                val shared = cached != null && Assets.shareImage(getApplication(), cached)
                say(
                    when {
                        cached == null -> "写入分享文件失败"
                        shared -> "已打开分享（${out.width}x${out.height}）"
                        else -> "分享失败：没有可用的目标"
                    },
                    !shared,
                )
            } else {
                exportProgress = ExportProgressUi(96, "写入相册", estimate)
                val saved = withContext(Dispatchers.IO) { Assets.saveToGallery(getApplication(), out) }
                say(if (saved != null) "已存入相册：$saved" else "写入相册失败", saved == null)
            }
            announce(status)
            onDone(status)
            exportProgress = ExportProgressUi(100, "已完成", estimate)
            delay(650)
            busy = false
            } finally {
                exportProgress = null
                busy = false
            }
        }
    }

    /**
     * Source-size RAW output goes through overlapping horizontal bands. The
     * overlap is discarded before stitching, so convolution tails and glow
     * cross each seam without a visible hard edge. Other export tiers keep
     * using the original whole-frame path.
     */
    private suspend fun renderSourceTiled(
        handle: EngineHandle,
        file: java.io.File?,
        opened: Session,
        baseParams: JSONObject,
        width: Int,
        height: Int,
        onProgress: (Int, String) -> Unit,
    ): Pair<Bitmap?, String> = withContext(native) {
        if (width <= 0 || height <= 0) return@withContext null to "片源尺寸未知"
        val halo = 160
        val baseCropX = baseParams.optDouble("geometry_crop_x", 0.0)
        val baseCropY = baseParams.optDouble("geometry_crop_y", 0.0)
        val baseCropW = baseParams.optDouble("geometry_crop_w", 1.0)
        val baseCropH = baseParams.optDouble("geometry_crop_h", 1.0)
        onProgress(4, "全局测光")
        val meterParams = JSONObject(baseParams.toString()).apply {
            put("overscan_active", false)
            put("date_imprint_active", false)
            put("geometry_crop_x", 0.0)
            put("geometry_crop_y", 0.0)
            put("geometry_crop_w", 1.0)
            put("geometry_crop_h", 1.0)
        }
        // Use a fresh 3200 px session as the whole-frame reference. Asking the
        // source-size session for a 3400 preview reuses that session's
        // full-resolution decode, which can respond differently from a normal
        // 3200 export. A separate safe session keeps every export size on the
        // same visible tone and colour target.
        val referenceSession = file?.let { openSession(handle, it, 3200, remember = false) }
        val meterSource = referenceSession ?: opened
        val meterFrame: Bitmap
        val progress: JSONObject
        try {
            meterSource.setParams(meterParams.toString())
            meterFrame = meterSource.render("preview", true)
                ?: return@withContext null to meterSource.engineError()
            progress = runCatching { JSONObject(meterSource.progressJson()) }
                .getOrDefault(JSONObject())
        } finally {
            referenceSession?.close()
        }
        val meterSample = Bitmap.createScaledBitmap(meterFrame, 64, 64, true)
        val meterPixels = IntArray(64 * 64)
        meterSample.getPixels(meterPixels, 0, 64, 0, 0, 64, 64)
        val meterMean = DoubleArray(3)
        for (pixel in meterPixels) {
            meterMean[0] += (pixel shr 16) and 0xFF
            meterMean[1] += (pixel shr 8) and 0xFF
            meterMean[2] += pixel and 0xFF
        }
        for (c in 0..2) meterMean[c] /= meterPixels.size.toDouble()
        val globalAutoExposure = if (progress.isNull("auto_exposure_ev")) {
            0.0
        } else {
            progress.optDouble("auto_exposure_ev", 0.0)
        }
        val boostReferenceMax = if (progress.isNull("film_boost_reference_max")) {
            0.0
        } else {
            progress.optDouble("film_boost_reference_max", 0.0)
        }
        val headroomReferenceMax = if (progress.isNull("headroom_reference_max")) {
            0.0
        } else {
            progress.optDouble("headroom_reference_max", 0.0)
        }
        Diag.log(
            "EXPORT",
            "tiled export global_auto_exposure_ev=$globalAutoExposure " +
                "boost_reference_max=$boostReferenceMax " +
                "headroom_reference_max=$headroomReferenceMax " +
                "preview_mean_rgb=${meterMean.joinToString(",")}",
        )
        // Several kernels launch three threads per pixel (RGB channels).
        // 5.2 MP keeps even those dispatches below Adreno's ~16.7 M work-item
        // reliability wall after the overlap area is included.
        val coreRows = (5_200_000 / width - 2 * halo).coerceAtLeast(128)
        val tileCount = (height + coreRows - 1) / coreRows
        val result = runCatching {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }.getOrElse { return@withContext null to "无法分配全尺寸成片：$it" }
        val canvas = Canvas(result)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val samplePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        var exposureForTiles = globalAutoExposure

        fun tileParams(cropTop: Int, cropBottom: Int, exposureEv: Double): JSONObject =
            JSONObject(baseParams.toString()).apply {
                put("overscan_active", false)
                put("date_imprint_active", false)
                // Keep the auto-exposure node active and inject the
                // whole-frame EV. This is the same code path as the preview;
                // adding the EV to manual compensation instead produced a
                // visibly brighter print.
                put("auto_exposure", true)
                put("auto_exposure_override_active", true)
                put("auto_exposure_override_ev", exposureEv)
                put(
                    "exposure_compensation_ev",
                    baseParams.optDouble("exposure_compensation_ev", 0.0),
                )
                if (boostReferenceMax > 0.0) {
                    put("film_boost_reference_max", boostReferenceMax)
                }
                if (headroomReferenceMax > 0.0) {
                    put("headroom_reference_max", headroomReferenceMax)
                }
                // Preserve the selected gate's horizontal crop. The tile band
                // is relative to the already-cropped picture, then mapped back
                // into source-normalised geometry coordinates.
                put("geometry_crop_x", baseCropX)
                put("geometry_crop_y", baseCropY + cropTop.toDouble() / height * baseCropH)
                put("geometry_crop_w", baseCropW)
                put(
                    "geometry_crop_h",
                    (cropBottom - cropTop).toDouble() / height * baseCropH,
                )
            }

        try {
            var coreTop = 0
            var tileIndex = 0
            while (coreTop < height) {
                val coreBottom = minOf(height, coreTop + coreRows)
                val readTop = maxOf(0, coreTop - halo)
                val readBottom = minOf(height, coreBottom + halo)
                var params = tileParams(readTop, readBottom, exposureForTiles)
                opened.setParams(params.toString())
                var tile = opened.render("full", true)
                    ?: return@withContext null to opened.engineError()
                if (tileIndex == 0) {
                    // A full-resolution render and the safe preview can have
                    // slightly different tone response at the same reported
                    // EV. Match the first tile to its preview region, then use
                    // the calibrated EV for every remaining tile. The loop is
                    // bounded and normally converges in one or two passes.
                    for (attempt in 0 until 3) {
                        val scale = tile.height.toDouble() / (readBottom - readTop)
                        val probeTop = ((coreTop - readTop) * scale).roundToInt()
                        val probeBottom = ((coreBottom - readTop) * scale).roundToInt()
                        val tileMean = sampledRegionMean(
                            tile,
                            Rect(0, probeTop, tile.width, probeBottom),
                            samplePaint,
                        )
                        val refTop = (coreTop * meterFrame.height / height)
                            .coerceIn(0, meterFrame.height - 1)
                        val refBottom = (coreBottom * meterFrame.height / height)
                            .coerceIn(refTop + 1, meterFrame.height)
                        val refMean = sampledRegionMean(
                            meterFrame,
                            Rect(0, refTop, meterFrame.width, refBottom),
                            samplePaint,
                        )
                        val correction = exposureCorrectionEv(tileMean, refMean)
                        Diag.log(
                            "EXPORT",
                            "tile exposure calibration attempt=$attempt " +
                                "src=${tileMean.joinToString(",")} " +
                                "ref=${refMean.joinToString(",")} " +
                                "correction_ev=$correction exposure_ev=$exposureForTiles",
                        )
                        if (kotlin.math.abs(correction) < 0.04) break
                        exposureForTiles =
                            (exposureForTiles + correction).coerceIn(-8.0, 8.0)
                        tile.recycle()
                        params = tileParams(readTop, readBottom, exposureForTiles)
                        opened.setParams(params.toString())
                        tile = opened.render("full", true)
                            ?: return@withContext null to opened.engineError()
                    }
                }
                val sourceScale = tile.height.toDouble() / (readBottom - readTop)
                val srcTop = ((coreTop - readTop) * sourceScale).roundToInt()
                val srcBottom = ((coreBottom - readTop) * sourceScale).roundToInt()
                val src = Rect(0, srcTop, tile.width, srcBottom)
                val dst = Rect(0, coreTop, width, coreBottom)
                canvas.drawBitmap(tile, src, dst, paint)
                tile.recycle()
                coreTop = coreBottom
                tileIndex += 1
                onProgress(
                    10 + (78 * tileIndex / tileCount),
                    "分片冲印 $tileIndex/$tileCount",
                )
            }
            result to ""
        } catch (t: Throwable) {
            result.recycle()
            null to (t.message ?: t.toString())
        } finally {
            meterFrame.recycle()
        }
    }

    private fun sampledRegionMean(
        bitmap: Bitmap,
        src: Rect,
        paint: Paint,
        sampleWidth: Int = 96,
    ): DoubleArray {
        if (src.width() <= 0 || src.height() <= 0) return doubleArrayOf(0.0, 0.0, 0.0)
        val sampleHeight = (sampleWidth * src.height().toDouble() / src.width())
            .roundToInt()
            .coerceIn(8, 256)
        val sample = Bitmap.createBitmap(sampleWidth, sampleHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sample)
        canvas.drawBitmap(
            bitmap,
            src,
            Rect(0, 0, sampleWidth, sampleHeight),
            paint,
        )
        val pixels = IntArray(sampleWidth * sampleHeight)
        sample.getPixels(pixels, 0, sampleWidth, 0, 0, sampleWidth, sampleHeight)
        sample.recycle()
        val mean = DoubleArray(3)
        for (pixel in pixels) {
            mean[0] += (pixel shr 16) and 0xFF
            mean[1] += (pixel shr 8) and 0xFF
            mean[2] += pixel and 0xFF
        }
        for (c in 0..2) mean[c] /= pixels.size.toDouble()
        return mean
    }

    private fun exposureCorrectionEv(source: DoubleArray, target: DoubleArray): Double {
        fun linear(channel: Double): Double {
            val value = (channel / 255.0).coerceIn(0.0, 1.0)
            return if (value <= 0.04045) value / 12.92
            else Math.pow((value + 0.055) / 1.055, 2.4)
        }
        fun luminance(rgb: DoubleArray): Double =
            0.2126 * linear(rgb[0]) + 0.7152 * linear(rgb[1]) + 0.0722 * linear(rgb[2])
        val sourceLuma = luminance(source).coerceAtLeast(1e-8)
        val targetLuma = luminance(target).coerceAtLeast(1e-8)
        return (kotlin.math.ln(targetLuma / sourceLuma) / kotlin.math.ln(2.0))
            .coerceIn(-4.0, 4.0)
    }

    private suspend fun renderOverscanTemplate(
        handle: EngineHandle,
        file: File,
        baseParams: JSONObject,
    ): OverscanTemplate {
        val templateSession = openSession(handle, file, 3200, remember = false)
            ?: return OverscanTemplate(null, null, null, "片边模板打开失败")
        return try {
            withContext(native) {
                templateSession.setParams(baseParams.toString())
                val base = templateSession.render("preview", true)
                if (base == null) {
                    return@withContext OverscanTemplate(
                        null,
                        null,
                        null,
                        handle.lastError.orEmpty(),
                    )
                }
                val gate = readFirstOverscanGate(templateSession)
                // The composite must draw the high-resolution photo after the
                // film texture but before the edge print and date back. Render
                // the same session once more without those marks so their
                // pixels can be isolated and placed above the photo.
                val cleanParams = JSONObject(baseParams.toString()).apply {
                    put("overscan_edge_text", "")
                    put("date_imprint_active", false)
                }
                templateSession.setParams(cleanParams.toString())
                val clean = templateSession.render("preview", true)
                OverscanTemplate(base, clean, gate, "")
            }
        } finally {
            withContext(native) { templateSession.close() }
        }
    }

    /** Reads the engine's actual first gate instead of assuming a 135 layout. */
    private fun readFirstOverscanGate(session: Session): FloatArray? {
        return try {
            val geometry = session.overscanGeometryJson()
            val root = JSONObject(geometry)
            if (!root.optBoolean("valid", false)) {
                Diag.log("EXPORT", "overscan geometry invalid: ${geometry.take(240)}")
                return null
            }
            val points = root.optJSONArray("gates")?.optJSONArray(0)
            if (points == null || points.length() < 8) {
                Diag.log("EXPORT", "overscan geometry has no gate: ${geometry.take(240)}")
                return null
            }
            val quad = FloatArray(8) { i -> points.getDouble(i).toFloat() }
            Diag.log("EXPORT", "overscan gate=${quad.joinToString(",")}")
            quad.takeIf { it.all { value -> value.isFinite() && value >= 0f && value <= 1f } }
        } catch (t: Throwable) {
            Diag.log("EXPORT", "overscan geometry read failed: ${t.message ?: t.toString()}")
            null
        }
    }

    /**
     * Recombines the full-resolution tiled photograph with a clean, smaller
     * overscan render. The border is upscaled; the photograph itself remains
     * at source resolution.
     */
    private fun composeFullOverscan(
        photo: Bitmap,
        template: Bitmap,
        cleanTemplate: Bitmap?,
        gateQuad: FloatArray?,
    ): Pair<Bitmap?, String> {
        // Engine-produced, normalised gate corners. The old fixed rectangle
        // was measured only for a 135 strip; using it with 6x7 or 645 put the
        // full frame into the wrong hole and changed the exported aspect.
        val quad = gateQuad ?: floatArrayOf(
            0.018529f, 0.163291f,
            0.974297f, 0.163291f,
            0.974297f, 0.838608f,
            0.018529f, 0.838608f,
        )
        val gatePx = FloatArray(8) { i ->
            if (i % 2 == 0) quad[i] * template.width else quad[i] * template.height
        }
        fun edge(ax: Float, ay: Float, bx: Float, by: Float): Double =
            kotlin.math.hypot((bx - ax).toDouble(), (by - ay).toDouble())
        val gateW = (edge(gatePx[0], gatePx[1], gatePx[2], gatePx[3]) +
            edge(gatePx[6], gatePx[7], gatePx[4], gatePx[5])) * 0.5
        val gateH = (edge(gatePx[0], gatePx[1], gatePx[6], gatePx[7]) +
            edge(gatePx[2], gatePx[3], gatePx[4], gatePx[5])) * 0.5
        if (gateW <= 1.0 || gateH <= 1.0) return null to "片边画幅定位失败"
        val scale = (photo.width / gateW + photo.height / gateH) * 0.5
        val finalW = (template.width * scale).roundToInt()
        val finalH = (template.height * scale).roundToInt()
        val result = runCatching {
            Bitmap.createBitmap(finalW, finalH, Bitmap.Config.ARGB_8888)
        }.getOrElse { return null to "无法分配带片边全尺寸成片：$it" }
        val canvas = Canvas(result)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            isDither = true
        }
        canvas.drawBitmap(cleanTemplate ?: template, null, Rect(0, 0, finalW, finalH), paint)
        val dst = floatArrayOf(
            gatePx[0] * scale.toFloat(), gatePx[1] * scale.toFloat(),
            gatePx[2] * scale.toFloat(), gatePx[3] * scale.toFloat(),
            gatePx[4] * scale.toFloat(), gatePx[5] * scale.toFloat(),
            gatePx[6] * scale.toFloat(), gatePx[7] * scale.toFloat(),
        )
        val src = floatArrayOf(
            0f, 0f,
            photo.width.toFloat(), 0f,
            photo.width.toFloat(), photo.height.toFloat(),
            0f, photo.height.toFloat(),
        )
        val matrix = Matrix()
        if (!matrix.setPolyToPoly(src, 0, dst, 0, 4)) {
            result.recycle()
            return null to "片边画幅透视定位失败"
        }
        canvas.drawBitmap(photo, matrix, paint)
        if (cleanTemplate != null && cleanTemplate.width == template.width &&
            cleanTemplate.height == template.height
        ) {
            val count = template.width * template.height
            val basePixels = IntArray(count)
            val cleanPixels = IntArray(count)
            template.getPixels(basePixels, 0, template.width, 0, 0, template.width, template.height)
            cleanTemplate.getPixels(
                cleanPixels,
                0,
                cleanTemplate.width,
                0,
                0,
                cleanTemplate.width,
                cleanTemplate.height,
            )
            val overlayPixels = IntArray(count)
            var changed = 0
            for (i in 0 until count) {
                val a = basePixels[i]
                val b = cleanPixels[i]
                val dr = kotlin.math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
                val dg = kotlin.math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
                val db = kotlin.math.abs((a and 0xFF) - (b and 0xFF))
                if (dr + dg + db >= 24) {
                    overlayPixels[i] = a
                    changed += 1
                }
            }
            Diag.log(
                "EXPORT",
                "edge-print overlay changed=${changed * 100.0 / count}%",
            )
            val overlay = Bitmap.createBitmap(
                template.width,
                template.height,
                Bitmap.Config.ARGB_8888,
            )
            overlay.setPixels(
                overlayPixels,
                0,
                template.width,
                0,
                0,
                template.width,
                template.height,
            )
            canvas.drawBitmap(overlay, null, Rect(0, 0, finalW, finalH), paint)
            overlay.recycle()
        }
        return result to ""
    }

    private fun Session.engineError(): String =
        this@DarkroomViewModel.engine?.lastError.orEmpty().ifEmpty { "全尺寸分片冲印失败" }

    /**
     * A lost Vulkan device is fatal for the engine that owned it: every later
     * dispatch returns the same error, so nothing short of a new engine brings
     * the app back. Building one costs about a second (the setup tables are
     * the expensive part and they are rebuilt with it), which is worth it to
     * keep the user out of "restart the app and try again".
     */
    private suspend fun recoverEngine(notice: String? = null) {
        exportLongEdge = 3200
        persist()
        postOpenNotice = notice
        runCatching { session?.let { s -> withContext(native) { s.close() } } }
        session = null
        ticket.incrementAndGet()
        preview = null
        clearInspection()
        clearComparison()
        runCatching { engine?.release() }
        engine = null
        ready = false
        val context = getApplication<Application>()
        val dir = withContext(Dispatchers.IO) { Assets.ensureEngine(context) }
        val created = withContext(native) { EngineHandle.create(dir.absolutePath) }
        engine = created
        ready = created.isReady
        engineInfo = withContext(native) {
            if (created.isReady) Engine.nativeBuildInfo() else created.lastError
        }
        Diag.log("ENGINE", "rebuilt after a lost device: ready=$ready")
        if (!created.isReady) {
            say("引擎重建失败：${created.lastError}", error = true)
            return
        }
        // Back on screen at the size that already worked, with the frame still
        // loaded, so trying a smaller export is one tap away.
        if (notice == null) say("引擎已重建，导出长边回到 3200")
        val file = lastFile
        val uri = lastUri
        if (file != null) openFile(file) else if (uri != null) open(uri)
    }

    override fun onCleared() {
        renders.close()
        inspectionJob?.cancel()
        comparisonJob?.cancel()
        benchmarkJob?.cancel()
        session?.let { s -> s.close() }
        engine?.release()
    }

    /**
     * Sampled decode. A 33 MP camera JPEG is 131 MB as an ARGB_8888 bitmap, so
     * decoding at full size and then scaling it down -- which is what this did
     * before -- is an OOM waiting for a big enough frame.
     */
    private fun decodeFile(file: java.io.File, longEdge: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            readNote = "这个文件不是能解码的图片（${file.name}）"
            return null
        }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > longEdge * 2) sample *= 2
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: run {
            readNote = "这个文件解不出像素（${file.name}）"
            return null
        }
        val scale = longEdge.toFloat() / maxOf(decoded.width, decoded.height)
        if (scale >= 1f) decoded
        else Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }.getOrElse {
        readNote = "读不了 ${file.name}：${it.message ?: it.javaClass.simpleName}"
        null
    }

    private fun decode(app: Application, uri: Uri, longEdge: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        app.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > longEdge * 2) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = app.contentResolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: run {
            readNote = "这张照片解不出像素"
            return null
        }
        val scale = longEdge.toFloat() / maxOf(decoded.width, decoded.height)
        if (scale >= 1f) decoded
        else Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }.getOrElse {
        readNote = "读不了这张照片：${it.message ?: it.javaClass.simpleName}"
        null
    }
}
