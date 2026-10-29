// spk_probe.cpp -- a device-side smoke test for the Android port.
//
// Opens the engine against a resource directory, renders one 512x512 synthetic
// gradient through the full film/print/scan chain and writes a PPM. Runs under
// `adb shell`, so it proves the Vulkan driver, the SPIR-V kernels and the setup
// tables on the actual handset before the APK is involved.

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "spektrafilm/spk_engine.h"

int main(int argc, char** argv) {
    if (argc < 2) {
        std::printf("usage: spk_probe <resources_dir> [out.ppm]\n");
        return 2;
    }
    const char* resources = argv[1];
    const char* output = argc > 2 ? argv[2] : "/data/local/tmp/spk_probe.ppm";

    std::printf("build: %s\n", spk_build_info());

    spk_engine* engine = spk_engine_create(resources, nullptr);
    if (!engine) {
        std::printf("CREATE FAILED: %s\n", spk_last_error(nullptr));
        return 1;
    }
    const char* caps = spk_capabilities(engine);
    std::printf("create ok\ncaps: %.400s\n", caps ? caps : "(null)");

    const int kSize = 768;
    std::vector<float> pixels(static_cast<size_t>(kSize) * kSize * 3);
    // Eight neutral steps, 64 px each: a monotone ramp is the only thing that
    // separates "the film has a colour cast" from "the readback is wrong".
    for (int y = 0; y < kSize; ++y) {
        for (int x = 0; x < kSize; ++x) {
            const size_t o = (static_cast<size_t>(y) * kSize + x) * 3;
            const float step = static_cast<float>(x / 64) / 7.0f;
            pixels[o + 0] = step;
            pixels[o + 1] = step;
            pixels[o + 2] = step;
        }
    }

    spk_image image{};
    image.data = pixels.data();
    image.width = kSize;
    image.height = kSize;
    image.channels = 3;

    const char* delta =
        "{\"film_stock\":\"kodak_portra_400\",\"print_stock\":\"kodak_portra_endura\","
        "\"input_color_space\":\"sRGB\",\"input_cctf_decoding\":true,"
        "\"output_color_space\":\"sRGB\",\"output_cctf_encoding\":true}";

    char* reply = nullptr;
    spk_session* session = spk_open(engine, &image, delta, &reply);
    if (!session) {
        std::printf("OPEN FAILED: %s\n", spk_last_error(engine));
        if (reply) spk_string_free(reply);
        spk_engine_destroy(engine);
        return 1;
    }
    std::printf("open ok: %.300s\n", reply ? reply : "(no reply)");
    if (reply) spk_string_free(reply);

    spk_result result{};
    const spk_status status = spk_render(session, "live", &result);
    if (status != SPK_OK || !result.rgba16) {
        std::printf("RENDER FAILED (%d): %s\n", int(status), spk_last_error(engine));
        spk_session_release(session);
        spk_engine_destroy(engine);
        return 1;
    }
    std::printf("render ok %ux%u stride=%u %.1f ms reprint=%d\n", result.width, result.height,
                result.row_stride_px, result.elapsed_ms, result.reprint);
    // Raw samples straight out of the RGBA16 plane, one per step.
    const uint32_t mid = result.height / 2;
    const uint16_t* midrow = result.rgba16 + static_cast<size_t>(mid) * result.row_stride_px * 4;
    for (int step = 0; step < 8; ++step) {
        const uint32_t x = step * 64 + 32;
        std::printf("step %d at x=%u: %u %u %u %u\n", step, x, midrow[x * 4 + 0], midrow[x * 4 + 1],
                    midrow[x * 4 + 2], midrow[x * 4 + 3]);
    }

    if (FILE* file = std::fopen(output, "wb")) {
        std::fprintf(file, "P6\n%u %u\n255\n", result.width, result.height);
        for (uint32_t y = 0; y < result.height; ++y) {
            const uint16_t* row = result.rgba16 + static_cast<size_t>(y) * result.row_stride_px * 4;
            for (uint32_t x = 0; x < result.width; ++x) {
                const unsigned char rgb[3] = {
                    static_cast<unsigned char>(row[x * 4 + 0] >> 8),
                    static_cast<unsigned char>(row[x * 4 + 1] >> 8),
                    static_cast<unsigned char>(row[x * 4 + 2] >> 8)};
                std::fwrite(rgb, 1, 3, file);
            }
        }
        std::fclose(file);
        std::printf("wrote %s\n", output);
    }

    // A second render, this time a print-layer edit, to exercise the cached
    // negative path the UI's sliders depend on.
    char* edit_reply = nullptr;
    spk_set_params(session, "{\"exposure_compensation_ev\":0.6}", &edit_reply);
    std::printf("edit: %.200s\n", edit_reply ? edit_reply : "(no reply)");
    if (edit_reply) spk_string_free(edit_reply);

    spk_result second{};
    const spk_status again = spk_reprint(session, "live", &second);
    if (again == SPK_OK) {
        std::printf("reprint ok %.1f ms reprint=%d\n", second.elapsed_ms, second.reprint);
        spk_result_free(&second);
    } else {
        std::printf("REPRINT FAILED (%d): %s\n", int(again), spk_last_error(engine));
    }

    spk_result_free(&result);
    spk_session_release(session);
    spk_engine_destroy(engine);
    std::printf("done\n");
    return 0;
}
