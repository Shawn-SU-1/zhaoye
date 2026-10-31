// jni_bridge.cpp -- 照夜's Android front door onto the SpektraLab C ABI.
//
// The engine is the desktop one: same core setup maths, same node sequence,
// same Vulkan compute kernels. This file only moves pixels and JSON across the
// JNI boundary, and keeps the two handles (engine, session) that Kotlin owns.

#include <jni.h>
#include <android/log.h>

#include <libraw/libraw.h>

#include <cstdint>
#include <new>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "spektrafilm/spk_engine.h"
#include "spk_raster.hpp"

#define LOG_TAG "Zhaoye"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::string to_string(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

jstring to_jstring(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

// Owns a session plus the engine pointer it belongs to, so a session can never
// outlive the engine that made it.
struct SessionHandle {
    spk_engine* engine = nullptr;
    spk_session* session = nullptr;
};

void free_result_json(char* text) {
    if (text) spk_string_free(text);
}

double now_ms() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000.0 + ts.tv_nsec / 1e6;
}

// RAW decoding reports through an out-parameter rather than a second return
// value: the session handle is the result, and the caller wants the decode
// numbers (camera, sizes, timings) alongside it.
// The three faces the edge print asks for by PostScript name. They ship inside
// assets/engine/fonts, so Assets.kt unpacks them next to the profiles and the
// kernels; the buffers must outlive the process because stb_truetype keeps
// pointers into them.
std::vector<unsigned char> g_font_sans, g_font_bold, g_font_mono;

bool read_file(const std::string& path, std::vector<unsigned char>& out) {
    FILE* f = std::fopen(path.c_str(), "rb");
    if (!f) return false;
    if (std::fseek(f, 0, SEEK_END) != 0) { std::fclose(f); return false; }
    const long n = std::ftell(f);
    std::rewind(f);
    if (n <= 0) { std::fclose(f); return false; }
    out.resize(size_t(n));
    const size_t got = std::fread(out.data(), 1, size_t(n), f);
    std::fclose(f);
    out.resize(got);
    return got == size_t(n);
}

void load_fonts(const std::string& dir) {
    const std::string fonts = dir + "/fonts/";
    bool ok = read_file(fonts + "DroidSans.ttf", g_font_sans);
    ok &= read_file(fonts + "DroidSans-Bold.ttf", g_font_bold);
    ok &= read_file(fonts + "DroidSansMono.ttf", g_font_mono);
    if (!ok) {
        // A missing face is not fatal: the edge print measures from a fallback
        // advance and draws nothing, which is what the engine did before the
        // port. Say so once so the log explains an empty rebate.
        LOGE("fonts: cannot read the three faces from %s", fonts.c_str());
    }
    spk::port::set_font_blob(spk::port::kFaceSans, g_font_sans.data(), int(g_font_sans.size()));
    spk::port::set_font_blob(spk::port::kFaceSansBold, g_font_bold.data(), int(g_font_bold.size()));
    spk::port::set_font_blob(spk::port::kFaceMono, g_font_mono.data(), int(g_font_mono.size()));
    LOGI("fonts: sans=%zu bold=%zu mono=%zu bytes, ready=%d", g_font_sans.size(), g_font_bold.size(),
         g_font_mono.size(), int(spk::port::fonts_ready()));
}

void set_info(JNIEnv* env, jobjectArray info, const std::string& text) {
    if (!info || env->GetArrayLength(info) < 1) return;
    jstring value = env->NewStringUTF(text.c_str());
    env->SetObjectArrayElement(info, 0, value);
    env->DeleteLocalRef(value);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_shawn_zhaoye_Engine_nativeCreate(JNIEnv* env, jclass, jstring resources_dir) {
    const std::string dir = to_string(env, resources_dir);
    load_fonts(dir);
    spk_engine* engine = spk_engine_create(dir.c_str(), nullptr);
    if (!engine) {
        LOGE("spk_engine_create failed: %s", spk_last_error(nullptr));
    }
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL
Java_com_shawn_zhaoye_Engine_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    if (auto* engine = reinterpret_cast<spk_engine*>(handle)) spk_engine_destroy(engine);
}

JNIEXPORT jstring JNICALL
Java_com_shawn_zhaoye_Engine_nativeLastError(JNIEnv* env, jclass, jlong handle) {
    auto* engine = reinterpret_cast<spk_engine*>(handle);
    const char* message = spk_last_error(engine);
    return to_jstring(env, message ? message : "");
}

JNIEXPORT jstring JNICALL
Java_com_shawn_zhaoye_Engine_nativeCapabilities(JNIEnv* env, jclass, jlong handle) {
    auto* engine = reinterpret_cast<spk_engine*>(handle);
    const char* text = engine ? spk_capabilities(engine) : nullptr;
    return to_jstring(env, text ? text : "{}");
}

JNIEXPORT jstring JNICALL
Java_com_shawn_zhaoye_Engine_nativeMemoryReport(JNIEnv* env, jclass, jlong handle) {
    auto* engine = reinterpret_cast<spk_engine*>(handle);
    const char* text = engine ? spk_memory_report(engine) : nullptr;
    return to_jstring(env, text ? text : "{}");
}

JNIEXPORT jstring JNICALL
Java_com_shawn_zhaoye_Engine_nativeParamsSchema(JNIEnv* env, jclass, jlong handle) {
    auto* engine = reinterpret_cast<spk_engine*>(handle);
    const char* text = engine ? spk_params_schema(engine) : nullptr;
    return to_jstring(env, text ? text : "{}");
}

// pixels: tightly packed float32 RGB, row major, top row first, in the space
// named by io.input_color_space in `delta_json`.
JNIEXPORT jlong JNICALL
Java_com_shawn_zhaoye_Engine_nativeOpen(JNIEnv* env, jclass, jlong handle,
                                           jfloatArray pixels, jint width, jint height,
                                           jstring delta_json) {
    auto* engine = reinterpret_cast<spk_engine*>(handle);
    if (!engine || !pixels) return 0;

    const jsize length = env->GetArrayLength(pixels);
    if (length < width * height * 3) {
        LOGE("pixel buffer too small: %d < %d", length, width * height * 3);
        return 0;
    }
    std::vector<float> buffer(static_cast<size_t>(length));
    env->GetFloatArrayRegion(pixels, 0, length, buffer.data());

    spk_image image{};
    image.data = buffer.data();
    image.width = static_cast<uint32_t>(width);
    image.height = static_cast<uint32_t>(height);
    image.channels = 3;

    const std::string delta = to_string(env, delta_json);
    char* reply = nullptr;
    spk_session* session = spk_open(engine, &image, delta.empty() ? "{}" : delta.c_str(), &reply);
    free_result_json(reply);
    if (!session) {
        LOGE("spk_open failed: %s", spk_last_error(engine));
        return 0;
    }
    auto* out = new SessionHandle{engine, session};
    return reinterpret_cast<jlong>(out);
}

JNIEXPORT jstring JNICALL
Java_com_shawn_zhaoye_Engine_nativeSetParams(JNIEnv* env, jclass, jlong handle,
                                                 jstring delta_json) {
    auto* h = reinterpret_cast<SessionHandle*>(handle);
    if (!h) return to_jstring(env, "{}");
    const std::string delta = to_string(env, delta_json);
    char* reply = nullptr;
    const spk_status status = spk_set_params(h->session, delta.c_str(), &reply);
    std::string out = reply ? reply : "{}";
    free_result_json(reply);
    if (status != SPK_OK) {
        LOGE("spk_set_params failed: %s", spk_last_error(h->engine));
        out = "{\"error\":\"" + std::string(spk_last_error(h->engine)) + "\"}";
    }
    return to_jstring(env, out);
}

JNIEXPORT jstring JNICALL
Java_com_shawn_zhaoye_Engine_nativeGetParams(JNIEnv* env, jclass, jlong handle) {
    auto* h = reinterpret_cast<SessionHandle*>(handle);
    if (!h) return to_jstring(env, "{}");
    char* reply = nullptr;
    const spk_status status = spk_get_params(h->session, &reply);
    std::string out = reply ? reply : "{}";
    free_result_json(reply);
    if (status != SPK_OK) out = "{}";
    return to_jstring(env, out);
}

JNIEXPORT jdouble JNICALL
Java_com_shawn_zhaoye_Engine_nativeProgressPercent(JNIEnv*, jclass, jlong handle) {
    auto* h = reinterpret_cast<SessionHandle*>(handle);
    return h ? spk_progress_percent(h->session) : 0.0;
}

JNIEXPORT jstring JNICALL
Java_com_shawn_zhaoye_Engine_nativeProgressJson(JNIEnv* env, jclass, jlong handle) {
    auto* h = reinterpret_cast<SessionHandle*>(handle);
    if (!h) return to_jstring(env, "{}");
    char* reply = nullptr;
    const spk_status status = spk_progress(h->session, nullptr, &reply);
    std::string out = reply ? reply : "{}";
    free_result_json(reply);
    if (status != SPK_OK) out = "{}";
    return to_jstring(env, out);
}

JNIEXPORT jstring JNICALL
Java_com_shawn_zhaoye_Engine_nativeOverscanGeometry(JNIEnv* env, jclass, jlong handle) {
    auto* h = reinterpret_cast<SessionHandle*>(handle);
    if (!h) return to_jstring(env, "{\"valid\":false}");
    char* reply = nullptr;
    const spk_status status = spk_overscan_geometry(h->session, &reply);
    std::string out = reply ? reply : "{\"valid\":false}";
    free_result_json(reply);
    if (status != SPK_OK) out = "{\"valid\":false}";
    return to_jstring(env, out);
}

// Returns header (int32 width, int32 height) followed by RGBA8 pixels. The
// engine hands back RGBA16; narrowing here keeps the boundary one array.
JNIEXPORT jbyteArray JNICALL
Java_com_shawn_zhaoye_Engine_nativeRender(JNIEnv* env, jclass, jlong handle,
                                              jstring tier_name, jboolean full_render) {
    auto* h = reinterpret_cast<SessionHandle*>(handle);
    if (!h) return nullptr;

    const std::string tier = to_string(env, tier_name);
    spk_result result{};
    const spk_status status = full_render ? spk_render(h->session, tier.c_str(), &result)
                                          : spk_reprint(h->session, tier.c_str(), &result);
    if (status != SPK_OK || !result.rgba16) {
        LOGE("render failed (%d): %s", int(status), spk_last_error(h->engine));
        if (result.texture) spk_result_free(&result);
        return nullptr;
    }

    const size_t pixel_count = static_cast<size_t>(result.width) * result.height;
    std::vector<uint8_t> out(8 + pixel_count * 4);
    const int32_t w = static_cast<int32_t>(result.width);
    const int32_t hgt = static_cast<int32_t>(result.height);
    std::memcpy(out.data(), &w, 4);
    std::memcpy(out.data() + 4, &hgt, 4);
    for (uint32_t y = 0; y < result.height; ++y) {
        const uint16_t* row = result.rgba16 + static_cast<size_t>(y) * result.row_stride_px * 4;
        uint8_t* dst = out.data() + 8 + static_cast<size_t>(y) * result.width * 4;
        for (uint32_t x = 0; x < result.width; ++x) {
            dst[x * 4 + 0] = static_cast<uint8_t>(row[x * 4 + 0] >> 8);
            dst[x * 4 + 1] = static_cast<uint8_t>(row[x * 4 + 1] >> 8);
            dst[x * 4 + 2] = static_cast<uint8_t>(row[x * 4 + 2] >> 8);
            dst[x * 4 + 3] = static_cast<uint8_t>(row[x * 4 + 3] >> 8);
        }
    }
    spk_result_free(&result);

    jbyteArray array = env->NewByteArray(static_cast<jsize>(out.size()));
    if (array) env->SetByteArrayRegion(array, 0, static_cast<jsize>(out.size()),
                                       reinterpret_cast<const jbyte*>(out.data()));
    return array;
}

JNIEXPORT void JNICALL
Java_com_shawn_zhaoye_Engine_nativeCloseSession(JNIEnv*, jclass, jlong handle) {
    auto* h = reinterpret_cast<SessionHandle*>(handle);
    if (!h) return;
    spk_session_release(h->session);
    delete h;
}

JNIEXPORT jstring JNICALL
Java_com_shawn_zhaoye_Engine_nativeBuildInfo(JNIEnv* env, jclass) {
    const char* text = spk_build_info();
    return to_jstring(env, text ? text : "");
}

// Decodes a RAW file and opens it in one call.
//
// It has to be one call: at 42 MP the decoded frame is 254 MB as 16-bit RGB and
// 506 MB as float, so handing it to Kotlin as a jfloatArray and passing it back
// would double the peak for no reason. The decode, the downscale to the tier we
// actually render at, and `spk_open` all happen on this side.
//
// The policy differs from `<engine>/src/io/raw_decoder.cpp` in exactly one
// respect, measured on this device: `half_size`. That file keeps the desktop's
// full-size AHD, which needs 688 MB and 3.1 s here; half size needs 253 MB and
// 0.45 s, and still resolves 3984 px against the 3200 px we export at. The
// colour policy is the desktop one -- as-shot WB, camera matrix, linear
// ProPhoto, 16-bit, no auto brightening.
JNIEXPORT jlong JNICALL
Java_com_shawn_zhaoye_Engine_nativeOpenRaw(JNIEnv* env, jclass, jlong handle,
                                               jstring path, jint long_edge,
                                               jstring delta_json, jobjectArray info) {
    auto* engine = reinterpret_cast<spk_engine*>(handle);
    if (!engine || !path) return 0;

    const std::string file = to_string(env, path);
    const std::string delta = to_string(env, delta_json);
    // 0 means "the source's own size": the export setting offers it so a RAW
    // can be rendered past 3200, and the caller already knows the cost.
    const int edge = long_edge > 0 ? long_edge : 0;
    const double t0 = now_ms();

    LibRaw raw;
    int st = raw.open_file(file.c_str());
    if (st != LIBRAW_SUCCESS) {
        set_info(env, info, std::string("{\"error\":\"open: ") + LibRaw::strerror(st) + "\"}");
        LOGE("libraw open: %s", LibRaw::strerror(st));
        return 0;
    }

    // Nikon's HE/HE* NEFs are not in this LibRaw; say so instead of failing
    // inside unpack with a generic error.
    const char* decoder = raw.unpack_function_name();
    if (decoder && std::strstr(decoder, "nikon_he_load_raw")) {
        set_info(env, info, "{\"error\":\"Nikon HE/HE* is not supported by LibRaw 0.22.2\"}");
        raw.recycle();
        return 0;
    }

    const uint32_t open_w = raw.imgdata.sizes.width, open_h = raw.imgdata.sizes.height;
    // Copied out now: `recycle()` clears idata, and the summary is assembled
    // after it.
    const std::string camera_make = raw.imgdata.idata.make;
    const std::string camera_model = raw.imgdata.idata.model;
    auto& p = raw.imgdata.params;
    p.user_qual = 3;   // AHD
    // Half size is what makes a 42 MP ARW survive on a phone: the full demosaic
    // is 7952 x 5320 x 3 x 2 B = 254 MB and the float frame built from it 506,
    // and MIUI's killer took both. A request past 4000 px is a deliberate
    // "give me everything", so it gets the full demosaic and pays for it.
    p.half_size = (edge > 0 && edge <= 4000) ? 1 : 0;
    p.four_color_rgb = 0;
    p.output_color = 4;  // ProPhoto
    p.output_bps = 16;
    p.gamm[0] = p.gamm[1] = 1.0;
    p.no_auto_bright = 1;
    p.no_auto_scale = 0;
    p.adjust_maximum_thr = 0.0f;
    p.bright = 1.0f;
    p.use_camera_wb = 1;
    p.use_auto_wb = 0;
    p.use_camera_matrix = 1;
    p.highlight = 0;
    p.exp_correc = 0;
    p.output_profile = nullptr;
    p.camera_profile = nullptr;

    const double t1 = now_ms();
    st = raw.unpack();
    if (st != LIBRAW_SUCCESS) {
        set_info(env, info, std::string("{\"error\":\"unpack: ") + LibRaw::strerror(st) + "\"}");
        raw.recycle();
        return 0;
    }
    const double t_unpack = now_ms() - t1;

    const double t2 = now_ms();
    st = raw.dcraw_process();
    if (st != LIBRAW_SUCCESS) {
        set_info(env, info, std::string("{\"error\":\"process: ") + LibRaw::strerror(st) + "\"}");
        raw.recycle();
        return 0;
    }
    const double t_process = now_ms() - t2;

    int mem_error = 0;
    const double t3 = now_ms();
    libraw_processed_image_t* image = raw.dcraw_make_mem_image(&mem_error);
    if (!image) {
        set_info(env, info, "{\"error\":\"demosaic output unavailable\"}");
        raw.recycle();
        return 0;
    }
    const double t_convert = now_ms() - t3;

    const uint32_t src_w = image->width, src_h = image->height;
    const uint32_t long_edge_src = src_w > src_h ? src_w : src_h;
    const double scale = edge > 0 ? static_cast<double>(edge) / static_cast<double>(long_edge_src) : 1.0;
    const uint32_t dst_w = scale >= 1.0 ? src_w : static_cast<uint32_t>(src_w * scale);
    const uint32_t dst_h = scale >= 1.0 ? src_h : static_cast<uint32_t>(src_h * scale);

    // One pass: box-average down and emit the float RGB `spk_open` wants. The
    // intermediate float frame at source size is never materialised.
    std::vector<float> rgb;
    try {
        rgb.resize(static_cast<size_t>(dst_w) * dst_h * 3);
    } catch (const std::bad_alloc&) {
        set_info(env, info, "{\"error\":\"out of memory for the float frame\"}");
        raw.dcraw_clear_mem(image);
        raw.recycle();
        return 0;
    }
    const auto* src = reinterpret_cast<const uint16_t*>(image->data);
    const double t4 = now_ms();
    for (uint32_t y = 0; y < dst_h; ++y) {
        const uint32_t y0 = static_cast<uint32_t>(static_cast<uint64_t>(y) * src_h / dst_h);
        uint32_t y1 = static_cast<uint32_t>(static_cast<uint64_t>(y + 1) * src_h / dst_h);
        if (y1 <= y0) y1 = y0 + 1;
        for (uint32_t x = 0; x < dst_w; ++x) {
            const uint32_t x0 = static_cast<uint32_t>(static_cast<uint64_t>(x) * src_w / dst_w);
            uint32_t x1 = static_cast<uint32_t>(static_cast<uint64_t>(x + 1) * src_w / dst_w);
            if (x1 <= x0) x1 = x0 + 1;
            uint32_t acc[3] = {0, 0, 0};
            uint32_t n = 0;
            for (uint32_t sy = y0; sy < y1; ++sy) {
                const uint16_t* row = src + (static_cast<size_t>(sy) * src_w + x0) * 3;
                for (uint32_t sx = x0; sx < x1; ++sx, row += 3) {
                    acc[0] += row[0]; acc[1] += row[1]; acc[2] += row[2];
                    ++n;
                }
            }
            float* dst = rgb.data() + (static_cast<size_t>(y) * dst_w + x) * 3;
            const float inv = 1.0f / (static_cast<float>(n) * 65535.0f);
            dst[0] = static_cast<float>(acc[0]) * inv;
            dst[1] = static_cast<float>(acc[1]) * inv;
            dst[2] = static_cast<float>(acc[2]) * inv;
        }
    }
    const double t_down = now_ms() - t4;
    raw.dcraw_clear_mem(image);
    raw.recycle();

    spk_image input{};
    input.data = rgb.data();
    input.width = dst_w;
    input.height = dst_h;
    input.channels = 3;
    char* reply = nullptr;
    spk_session* session = spk_open(engine, &input, delta.empty() ? "{}" : delta.c_str(), &reply);
    free_result_json(reply);
    if (!session) {
        set_info(env, info, "{\"error\":\"spk_open rejected the decoded frame\"}");
        LOGE("spk_open(raw) failed: %s", spk_last_error(engine));
        return 0;
    }

    const double total = now_ms() - t0;
    char summary[512];
    std::snprintf(summary, sizeof(summary),
                  "{\"libraw\":\"%s\",\"camera\":\"%s %s\",\"raw\":\"%ux%u\","
                  "\"decoded\":\"%ux%u\",\"downscaled\":\"%ux%u\","
                  "\"unpack_ms\":%.0f,\"process_ms\":%.0f,\"convert_ms\":%.0f,"
                  "\"downscale_ms\":%.0f,\"total_ms\":%.0f}",
                  LibRaw::version(), camera_make.c_str(), camera_model.c_str(),
                  open_w, open_h, src_w, src_h, dst_w, dst_h,
                  t_unpack, t_process, t_convert, t_down, total);
    set_info(env, info, summary);
    auto* out = new SessionHandle{engine, session};
    return reinterpret_cast<jlong>(out);
}

}  // extern "C"
