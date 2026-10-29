package com.shawn.zhaoye

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Where a finished print goes: the gallery, or straight out to another app. */
enum class ExportTarget { Gallery, Share }

/**
 * Owns the engine, the current session and the darkroom settings.
 *
 * Every native call runs on one dispatcher: a session is not thread-safe and
 * the engine's pool is shared, so the serialisation is correctness rather than
 * politeness.
 */
class DarkroomViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
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
    private var pending: Job? = null
    /**
     * A line that survives the reopen that follows a recovery: rebuilding the
     * engine reopens the frame, and every step of that overwrites the status,
     * so the one sentence that explains what just happened has to be carried
     * past all of them.
     */
    private var postOpenNotice: String? = null
    private val ticket = AtomicInteger(0)
    private var pendingFile: java.io.File? = null

    // What the current negative was made from, so export can go back to the
    // full-size frame. A launch intent hands over a path rather than a Uri, so
    // both have to be kept -- export used to work only for the picker's Uri.
    private var lastUri: Uri? = null
    private var lastFile: java.io.File? = null
    /** The frame's own shooting date, when its EXIF carried one. */
    private var lastShootMillis: Long? = null

    var preview by mutableStateOf<Bitmap?>(null)
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

    fun engineForDiag(): EngineHandle? = engine

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
            .apply()
    }

    /** Export size is set from the settings page, outside [update]. */
    fun chooseExportLongEdge(pixels: Int) {
        if (exportLongEdge == pixels) return
        exportLongEdge = pixels
        persist()
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
            preview = null
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
                // The RAW's own long edge, from the report's `raw` field: a
                // message about "42 MP" has to be able to name it.
                runCatching {
                    val root = JSONTokener(report).nextValue() as JSONObject
                    val size = root.optString("raw")
                    val w = size.substringBefore('x').toIntOrNull() ?: 0
                    val h = size.substringAfter('x', "").toIntOrNull() ?: 0
                    if (w > 0 && h > 0) {
                        lastSourceEdge = maxOf(w, h)
                        frameW = w
                        frameH = h
                    }
                }
                Log.i("Zhaoye", "raw $report")
                Diag.log("RAW", report)
                opened
            }
        }
        val bitmap = withContext(Dispatchers.IO) { decodeFile(file, longEdge) } ?: return null
        frameW = bitmap.width
        frameH = bitmap.height
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
                frameW = bitmap.width
                frameH = bitmap.height
                lastSourceEdge = maxOf(bitmap.width, bitmap.height)
            }
            session?.let { s -> withContext(native) { s.close() } }
            session = null
            preview = null
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
        block()
        persist()
        render(full = false)
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
        }
        if (paper != null) paperId = paper
        persist()
        render(full = true)
    }

    /** `raw` switches the input contract: LibRaw hands back linear ProPhoto. */
    private fun openingDelta(raw: Boolean): String = JSONObject()
        .put("film_stock", filmId)
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
        .put("print_stock", paperId)
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
        // Auto already picked the closest gate; whatever is left is under a
        // percent and not worth a sentence. A gate the user picked by hand is
        // worth one, with the way out.
        if (filmFormat != "auto") {
            fitNote = " · %s 片门比照片%s，已居中裁 %.0f%%（画幅选自动可避开）"
                .format(FORMAT_NAME[fmt] ?: fmt, if (alongX) "窄" else "扁", lost)
        }
        return doubleArrayOf((1.0 - w) / 2.0, (1.0 - h) / 2.0, w, h)
    }

    private class Applied(val error: String?, val needsFull: Boolean)

    fun render(full: Boolean) {
        val current = session ?: return
        val myTicket = ticket.incrementAndGet()
        pending?.cancel()
        pending = viewModelScope.launch {
            // A drag emits dozens of values; the last one wins.
            kotlinx.coroutines.delay(160)
            busy = true
            val applied = withContext(native) {
                val reply = current.setParams(delta().toString())
                val root = runCatching { JSONTokener(reply).nextValue() }.getOrNull() as? JSONObject
                val error = root?.optString("error")?.takeIf { it.isNotEmpty() }
                // `invalidated` is the string "shoot" or "print": shoot means
                // the negative itself is stale, print means a reprint from the
                // cached negative is enough. Anything else -- a missing field,
                // an error reply -- must fall back to the expensive path.
                val invalidated = root?.optString("invalidated").orEmpty()
                Applied(error, full || invalidated != "print")
            }
            if (myTicket != ticket.get()) return@launch   // a newer edit already won
            if (applied.error != null) {
                // Silently swallowing this is what made a rejected delta look
                // like a slider that does nothing.
                say("参数被退回：${applied.error}", error = true)
                busy = false
                return@launch
            }
            val started = System.currentTimeMillis()
            var failure = ""
            val bitmap = withContext(native) {
                val rendered = current.render("live", applied.needsFull)
                if (rendered == null) failure = engine?.lastError.orEmpty()
                rendered
            }
            Diag.log(
                "R",
                "live ${if (applied.needsFull) "full" else "reprint"} " +
                    "${System.currentTimeMillis() - started} ms -> " +
                    (bitmap?.let { "${it.width}x${it.height}" } ?: "null"),
            )
            if (myTicket != ticket.get()) return@launch   // a newer edit already won
            if (bitmap != null) {
                preview = bitmap
                val notice = postOpenNotice
                if (notice != null) {
                    postOpenNotice = null
                    say(notice, error = true)
                } else {
                    // The film and paper already sit on the frame as a caption,
                    // so this line reports the print itself: how big it came out
                    // and how long it took, plus the gate crop when there is one.
                    val ms = System.currentTimeMillis() - started
                    say("冲印完成 · ${bitmap.width}×${bitmap.height} · ${ms} ms$fitNote")
                }
            } else if (failure.contains("gate's shape") && filmFormat != "auto") {
                // The engine will only lay film on a frame that already has the
                // gate's shape. Falling back to 自动 is better than a dead
                // canvas: auto picks the closest gate and crops the last few
                // percent itself.
                filmFormat = "auto"
                persist()
                say("这个画幅装不下这张照片的形状，已切回「画幅 自动」")
                render(full = true)
                return@launch
            } else {
                say("渲染失败：${failure.ifEmpty { "引擎没有给出原因" }}", error = true)
            }
            busy = false
        }
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
            say("按原图重新冲印…")
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
            val opened = if (file != null) {
                openSession(handle, file, target, remember = false)
            } else {
                val frame = withContext(Dispatchers.IO) { decode(getApplication(), uri!!, target) }
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
            val started = System.currentTimeMillis()
            // `preview` renders at 3400 (the tier the app was built around);
            // a request for the source's own size has to ask for `full`, or the
            // long decode is thrown away by a 3400 px render.
            // The source was decoded to `target`, so the render has to be
            // `full` (= the source's own size) for any target above the
            // 3400 px `preview` tier, or the long decode is thrown away.
            val tier = if (target == 0 || target > 3400) "full" else "preview"
            var failure = ""
            val out = withContext(native) {
                opened.setParams(delta().toString())
                val rendered = opened.render(tier, true)
                // Thread-local error: read it before leaving this thread.
                if (rendered == null) failure = handle.lastError.orEmpty()
                opened.close()
                rendered
            }
            Diag.log(
                "R",
                "export long-edge=${if (target == 0) "source" else target.toString()} " +
                    "${System.currentTimeMillis() - started} ms -> " +
                    (out?.let { "${it.width}x${it.height}" } ?: "null"),
            )
            if (out == null) {
                val message = failure
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
                        "GPU 掉线了：${size}${mp}超出这台手机的显存。已重建引擎，导出长边退回 3200。",
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
                val saved = withContext(Dispatchers.IO) { Assets.saveToGallery(getApplication(), out) }
                say(if (saved != null) "已存入相册：$saved" else "写入相册失败", saved == null)
            }
            announce(status)
            onDone(status)
            busy = false
        }
    }

    /**
     * A lost Vulkan device is fatal for the engine that owned it: every later
     * dispatch returns the same error, so nothing short of a new engine brings
     * the app back. Building one costs about a second (the setup tables are
     * the expensive part and they are rebuilt with it), which is worth it to
     * keep the user out of "restart the app and try again".
     */
    private suspend fun recoverEngine(notice: String? = null) {
        pending?.cancel()
        exportLongEdge = 3200
        persist()
        postOpenNotice = notice
        runCatching { session?.let { s -> withContext(native) { s.close() } } }
        session = null
        preview = null
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
        pending?.cancel()
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
