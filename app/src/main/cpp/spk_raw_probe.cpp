// spk_raw_probe.cpp -- measures what a phone-side RAW decode actually costs.
//
// The desktop policy in `<engine>/src/io/raw_decoder.cpp` decodes full size and
// keeps the result as float RGB. At 42 MP that float buffer alone is 506 MB,
// which a phone will not give us. This probe answers three questions on the
// device before the app commits to a policy: how many pixels LibRaw hands back
// for each setting, how long it takes, and what the peak RSS was.
//
//   spk_raw_probe <file> [half_size 0|1] [user_qual] [long_edge]

#include <libraw/libraw.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

namespace {

double now_ms() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000.0 + ts.tv_nsec / 1e6;
}

std::string hwm_kb() {
    FILE* f = std::fopen("/proc/self/status", "r");
    if (!f) return "?";
    char line[256];
    std::string out = "?";
    while (std::fgets(line, sizeof(line), f)) {
        if (std::strncmp(line, "VmHWM:", 6) == 0) {
            out = std::string(line + 6);
            while (!out.empty() && (out.back() == '\n' || out.back() == ' ')) out.pop_back();
            while (!out.empty() && out.front() == ' ') out.erase(out.begin());
            break;
        }
    }
    std::fclose(f);
    return out;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: %s <raw> [half_size] [user_qual] [long_edge]\n", argv[0]);
        return 2;
    }
    const char* path = argv[1];
    const int half_size = argc > 2 ? std::atoi(argv[2]) : 1;
    const int qual = argc > 3 ? std::atoi(argv[3]) : 3;
    const int long_edge = argc > 4 ? std::atoi(argv[4]) : 1600;

    std::printf("libraw %s\n", LibRaw::version());

    const double t0 = now_ms();
    LibRaw raw;
    int st = raw.open_file(path);
    if (st != LIBRAW_SUCCESS) {
        std::fprintf(stderr, "open_file: %s\n", LibRaw::strerror(st));
        return 1;
    }
    const double t_open = now_ms() - t0;
    const auto& s = raw.imgdata.sizes;
    std::printf("camera      %s %s\n", raw.imgdata.idata.make, raw.imgdata.idata.model);
    std::printf("sizes       raw %ux%u -> %ux%u  flip=%d  filters=0x%x\n",
                s.raw_width, s.raw_height, s.width, s.height, s.flip, raw.imgdata.idata.filters);

    auto& p = raw.imgdata.params;
    p.user_qual = qual;
    p.half_size = half_size ? 1 : 0;
    p.output_color = 4;      // ProPhoto
    p.output_bps = 16;
    p.gamm[0] = p.gamm[1] = 1.0;
    p.no_auto_bright = 1;
    p.bright = 1.0f;
    p.use_camera_wb = 1;
    p.use_camera_matrix = 1;
    p.highlight = 0;
    p.adjust_maximum_thr = 0.0f;

    const double t1 = now_ms();
    st = raw.unpack();
    if (st != LIBRAW_SUCCESS) {
        std::fprintf(stderr, "unpack: %s\n", LibRaw::strerror(st));
        return 1;
    }
    const double t_unpack = now_ms() - t1;

    const double t2 = now_ms();
    st = raw.dcraw_process();
    if (st != LIBRAW_SUCCESS) {
        std::fprintf(stderr, "process: %s (%s)\n", LibRaw::strerror(st),
                     raw.unpack_function_name() ? raw.unpack_function_name() : "?");
        return 1;
    }
    const double t_proc = now_ms() - t2;

    int err = 0;
    const double t3 = now_ms();
    libraw_processed_image_t* img = raw.dcraw_make_mem_image(&err);
    if (!img) {
        std::fprintf(stderr, "make_mem_image: %d\n", err);
        return 1;
    }
    const double t_conv = now_ms() - t3;

    std::printf("output      %ux%u  colors=%u  bits=%u  bytes=%.1f MB\n",
                img->width, img->height, img->colors, img->bits,
                img->data_size / 1e6);

    // One pass: box-average down to the target edge and emit float RGB, which
    // is what spk_open wants. Nothing bigger than the target is materialised.
    const uint32_t w = img->width, h = img->height;
    const double scale = double(long_edge) / double(w > h ? w : h);
    const uint32_t tw = scale >= 1.0 ? w : uint32_t(w * scale);
    const uint32_t th = scale >= 1.0 ? h : uint32_t(h * scale);
    std::vector<float> rgb(size_t(tw) * th * 3);
    const auto* src = reinterpret_cast<const uint16_t*>(img->data);
    const uint32_t bx = w / tw ? w / tw : 1;
    const uint32_t by = h / th ? h / th : 1;
    const double t4 = now_ms();
    for (uint32_t y = 0; y < th; ++y) {
        for (uint32_t x = 0; x < tw; ++x) {
            uint32_t acc[3] = {0, 0, 0};
            uint32_t n = 0;
            const uint32_t y0 = y * h / th, y1 = std::max(y0 + 1, (y + 1) * h / th);
            const uint32_t x0 = x * w / tw, x1 = std::max(x0 + 1, (x + 1) * w / tw);
            for (uint32_t sy = y0; sy < y1; ++sy)
                for (uint32_t sx = x0; sx < x1; ++sx) {
                    const uint16_t* px = src + (size_t(sy) * w + sx) * 3;
                    acc[0] += px[0]; acc[1] += px[1]; acc[2] += px[2];
                    ++n;
                }
            float* dst = rgb.data() + (size_t(y) * tw + x) * 3;
            dst[0] = float(acc[0]) / (n * 65535.0f);
            dst[1] = float(acc[1]) / (n * 65535.0f);
            dst[2] = float(acc[2]) / (n * 65535.0f);
        }
    }
    const double t_down = now_ms() - t4;
    raw.dcraw_clear_mem(img);

    double sum = 0.0;
    for (float v : rgb) sum += v;
    std::printf("scaled      %ux%u  box=%ux%u  mean=%.4f\n", tw, th, bx, by,
                sum / double(rgb.size()));
    std::printf("timing      open %.0f ms | unpack %.0f ms | process %.0f ms | convert %.0f ms | downscale %.0f ms | total %.0f ms\n",
                t_open, t_unpack, t_proc, t_conv, t_down,
                t_open + t_unpack + t_proc + t_conv + t_down);
    std::printf("peak rss    %s\n", hwm_kb().c_str());
    return 0;
}
