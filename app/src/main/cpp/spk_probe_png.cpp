// spk_probe_png.cpp -- renders a PNG/JPEG file through the same engine, with
// stb doing the decode instead of Android's BitmapFactory.
//
// The control experiment for the app: same file, same engine, different
// ingest path. If this is right and the app is wrong, the bug is in the
// Bitmap-to-float conversion, not in the pipeline.

#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

#define STB_IMAGE_IMPLEMENTATION
#define STBI_ONLY_PNG
#define STBI_ONLY_JPEG
#include "stb_image.h"

#include "spektrafilm/spk_engine.h"

int main(int argc, char** argv) {
    if (argc < 4) {
        std::printf("usage: spk_probe_png <resources_dir> <image> <out.ppm>\n");
        return 2;
    }
    const char* resources = argv[1];
    const char* image_path = argv[2];
    const char* out_path = argv[3];

    int w = 0, h = 0, channels = 0;
    unsigned char* data = stbi_load(image_path, &w, &h, &channels, 3);
    if (!data) {
        std::printf("decode failed: %s\n", stbi_failure_reason());
        return 1;
    }
    std::printf("decoded %dx%d (%d channels)\n", w, h, channels);

    std::vector<float> pixels(static_cast<size_t>(w) * h * 3);
    for (size_t i = 0; i < pixels.size(); ++i) pixels[i] = data[i] / 255.0f;
    stbi_image_free(data);

    spk_engine* engine = spk_engine_create(resources, nullptr);
    if (!engine) {
        std::printf("CREATE FAILED: %s\n", spk_last_error(nullptr));
        return 1;
    }
    spk_image image{};
    image.data = pixels.data();
    image.width = static_cast<uint32_t>(w);
    image.height = static_cast<uint32_t>(h);
    image.channels = 3;

    const char* delta =
        "{\"film_stock\":\"kodak_portra_400\",\"print_stock\":\"kodak_portra_endura\","
        "\"input_color_space\":\"sRGB\",\"input_cctf_decoding\":true,"
        "\"output_color_space\":\"sRGB\",\"output_cctf_encoding\":true,"
        "\"grain_amount\":0.0,\"halation_amount\":0.0}";
    char* reply = nullptr;
    spk_session* session = spk_open(engine, &image, delta, &reply);
    if (!session) {
        std::printf("OPEN FAILED: %s\n", spk_last_error(engine));
        return 1;
    }
    if (reply) spk_string_free(reply);

    const char* tier = argc > 4 ? argv[4] : "live";
    spk_result result{};
    if (spk_render(session, tier, &result) != SPK_OK) {
        std::printf("RENDER FAILED: %s\n", spk_last_error(engine));
        return 1;
    }
    std::printf("render ok %ux%u stride=%u %.1f ms\n", result.width, result.height,
                result.row_stride_px, result.elapsed_ms);

    if (FILE* file = std::fopen(out_path, "wb")) {
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
        std::printf("wrote %s\n", out_path);
    }
    spk_result_free(&result);
    spk_session_release(session);
    spk_engine_destroy(engine);
    return 0;
}
