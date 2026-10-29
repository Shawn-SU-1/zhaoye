// spk_raster.cpp -- see spk_raster.hpp. This is the Android port's stand-in for
// CoreGraphics + CoreText + the one Accelerate call `overscan.cpp` makes.
//
// The scanner is the usual analytic-x / supersampled-y filler: per sub-scanline
// the edges that cross it are sorted, the nonzero winding rule decides which
// spans are inside, and each span is added to the pixels it covers with its
// exact horizontal overlap. Four sub-scanlines per pixel is enough here because
// every group is Gaussian-blurred afterwards; at four the residual is a 1/16
// quantisation of vertical position, well under a 2-px sigma.
#define STB_TRUETYPE_IMPLEMENTATION
#define STBTT_STATIC
#include "spk_raster.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>

#include "stb_truetype.h"

namespace spk {
namespace port {
namespace {

constexpr int kSub = 4;   // sub-scanlines per pixel row

struct Face {
    const unsigned char* data = nullptr;
    int size = 0;
    stbtt_fontinfo info{};
    bool ok = false;
    int units_per_em = 1000;
};

Face g_faces[kFaceCount];

// Advances for a face that failed to load: a Helvetica-ish 0.556 em average.
// The layout code divides by measured widths, so a plausible number keeps the
// edge print where it belongs instead of collapsing it to zero.
constexpr double kFallbackAdvance = 0.556;

int tt_u16(const unsigned char* p) { return int(p[0]) << 8 | int(p[1]); }

}  // namespace

void set_font_blob(int face, const unsigned char* data, int size) {
    if (face < 0 || face >= kFaceCount) return;
    Face& f = g_faces[face];
    f.data = data;
    f.size = size;
    f.ok = false;
    if (!data || size <= 0) return;
    const int offset = stbtt_GetFontOffsetForIndex(data, 0);
    if (offset < 0 || !stbtt_InitFont(&f.info, data, offset)) return;
    // head.unitsPerEm lives at head+18. stbtt keeps the table offsets but not
    // this field, and the em size is what CTFont's `size` means.
    f.units_per_em = f.info.head ? tt_u16(data + f.info.head + 18) : 1000;
    if (f.units_per_em <= 0) f.units_per_em = 1000;
    f.ok = true;
}

bool fonts_ready() {
    for (const Face& f : g_faces) if (f.ok) return true;
    return false;
}

int face_for_name(const char* postscript_name) {
    if (!postscript_name) return -1;
    const std::string n(postscript_name);
    auto has = [&](const char* s) { return n.find(s) != std::string::npos; };
    if (has("Mono") || has("Menlo") || has("Courier") || has("Consol") || has("Typewriter")) return kFaceMono;
    if (has("Bold") || has("Heavy") || has("Black") || has("Semibold") || has("SemiBold")) return kFaceSansBold;
    // ArialNarrow and every other Helvetica-family name the edge print uses.
    return kFaceSans;
}

namespace {

const Face& face_or_empty(int face) {
    static const Face empty;
    if (face < 0 || face >= kFaceCount) return empty;
    return g_faces[face];
}

// One glyph's advance, in em units of the font.
double glyph_advance_em(const Face& f, int glyph) {
    if (!f.ok) return kFallbackAdvance;
    int adv = 0, lsb = 0;
    stbtt_GetGlyphHMetrics(&f.info, glyph, &adv, &lsb);
    return double(adv) / double(f.units_per_em);
}

}  // namespace

double text_advance(const char* postscript_name, double size, const std::string& text, double tracking) {
    const Face& f = face_or_empty(face_for_name(postscript_name));
    double x = 0.0;
    for (unsigned char ch : text) {
        const int glyph = f.ok ? stbtt_FindGlyphIndex(&f.info, ch) : 0;
        x += (f.ok && glyph ? glyph_advance_em(f, glyph) : kFallbackAdvance) * size;
        x += tracking;
    }
    // CoreText's typographic width stops after the last advance, so the trailing
    // kern is not part of the ink -- but `rasterise`'s bbox budget adds an em
    // anyway, and the callers compare against a slot, so keep it simple and
    // drop it the way CTLine does.
    if (!text.empty() && tracking != 0.0) x -= tracking;
    return x;
}

// ---------------------------------------------------------------------------
// Canvas
// ---------------------------------------------------------------------------

Canvas::Canvas(int w, int h) : w_(w), h_(h) {
    if (w > 0 && h > 0) px_.assign(size_t(w) * size_t(h), 0);
}

void Canvas::concat(const double m[6]) {
    const double a = ctm_[0], b = ctm_[1], c = ctm_[2], d = ctm_[3], tx = ctm_[4], ty = ctm_[5];
    ctm_[0] = a * m[0] + c * m[1];
    ctm_[1] = b * m[0] + d * m[1];
    ctm_[2] = a * m[2] + c * m[3];
    ctm_[3] = b * m[2] + d * m[3];
    ctm_[4] = a * m[4] + c * m[5] + tx;
    ctm_[5] = b * m[4] + d * m[5] + ty;
}

void Canvas::translate(double tx, double ty) {
    const double m[6] = {1, 0, 0, 1, tx, ty};
    concat(m);
}
void Canvas::scale(double sx, double sy) {
    const double m[6] = {sx, 0, 0, sy, 0, 0};
    concat(m);
}
void Canvas::rotate(double radians) {
    const double c = std::cos(radians), s = std::sin(radians);
    const double m[6] = {c, s, -s, c, 0, 0};
    concat(m);
}
void Canvas::save() { stack_.push_back({ctm_[0], ctm_[1], ctm_[2], ctm_[3], ctm_[4], ctm_[5]}); }
void Canvas::restore() {
    if (stack_.empty()) return;
    const std::array<double, 6>& m = stack_.back();
    for (int i = 0; i < 6; ++i) ctm_[i] = m[i];
    stack_.pop_back();
}

void Canvas::set_text_matrix(double x, double y) { text_m_[0] = x; text_m_[1] = y; }

// --- path -----------------------------------------------------------------

void Canvas::push_point(double x, double y) {
    const double dx = ctm_[0] * x + ctm_[2] * y + ctm_[4];
    const double dy = ctm_[1] * x + ctm_[3] * y + ctm_[5];
    path_.push_back(dx);
    path_.push_back(dy);
}

// Close the contour being built and turn it into edges. A contour of fewer
// than three points (a degenerate move/line pair) contributes nothing.
void Canvas::emit_contour(std::vector<double>& pts) {
    const size_t n = pts.size() / 2;
    if (n >= 2) {
        for (size_t i = 0; i < n; ++i) {
            const size_t j = (i + 1) % n;
            Seg s;
            s.x0 = pts[2 * i]; s.y0 = pts[2 * i + 1];
            s.x1 = pts[2 * j]; s.y1 = pts[2 * j + 1];
            if (s.y0 == s.y1 && s.x0 == s.x1) continue;
            edges_.push_back(s);
        }
    }
    pts.clear();
}

void Canvas::begin_path() { path_.clear(); edges_.clear(); have_subpath_ = false; }

void Canvas::move_to(double x, double y) {
    if (have_subpath_ && !path_.empty()) emit_contour(path_);
    path_.clear();
    push_point(x, y);
    have_subpath_ = true;
}

void Canvas::line_to(double x, double y) {
    if (!have_subpath_) { move_to(x, y); return; }
    push_point(x, y);
}

void Canvas::close_path() {
    if (have_subpath_) emit_contour(path_);
    have_subpath_ = false;
}

void Canvas::add_ellipse(double cx, double cy, double rx, double ry) {
    // 64 segments: the largest imprint ellipse is a data-back window a couple of
    // millimetres across, and the mask is blurred after this anyway.
    const int n = 64;
    for (int i = 0; i < n; ++i) {
        const double a = 2.0 * M_PI * double(i) / double(n);
        const double x = cx + rx * std::cos(a), y = cy + ry * std::sin(a);
        if (i == 0) move_to(x, y); else line_to(x, y);
    }
    close_path();
}

// The filler. Analytic along x, kSub sub-scanlines along y, nonzero winding.
void Canvas::fill_path() {
    if (have_subpath_ && !path_.empty()) emit_contour(path_);
    have_subpath_ = false;
    if (edges_.empty() || !ok() || gray_ <= 0.0) { edges_.clear(); return; }

    double ymin = edges_[0].y0, ymax = edges_[0].y0;
    for (const Seg& e : edges_) {
        ymin = std::min({ymin, e.y0, e.y1});
        ymax = std::max({ymax, e.y0, e.y1});
    }
    const int py0 = std::max(0, int(std::floor(ymin)));
    const int py1 = std::min(h_ - 1, int(std::ceil(ymax)));
    if (py1 < py0) { edges_.clear(); return; }

    const double weight = std::clamp(gray_, 0.0, 1.0) / double(kSub);
    struct Hit { double x; int dir; };
    std::vector<Hit> hits;
    hits.reserve(edges_.size());
    std::vector<double> acc;   // per-pixel coverage on this sub-scanline

    for (int py = py0; py <= py1; ++py) {
        bool any = false;
        int cmin = w_, cmax = -1;
        for (int s = 0; s < kSub; ++s) {
            const double sy = double(py) + (double(s) + 0.5) / double(kSub);
            hits.clear();
            for (const Seg& e : edges_) {
                const double y0 = e.y0, y1 = e.y1;
                if ((sy >= y0 && sy < y1) || (sy >= y1 && sy < y0)) {
                    const double x = e.x0 + (sy - y0) * (e.x1 - e.x0) / (y1 - y0);
                    hits.push_back({x, y1 > y0 ? 1 : -1});
                }
            }
            if (hits.size() < 2) continue;
            std::sort(hits.begin(), hits.end(), [](const Hit& a, const Hit& b) { return a.x < b.x; });
            int winding = 0;
            double xL = 0.0;
            for (size_t i = 0; i < hits.size(); ++i) {
                const int before = winding;
                winding += hits[i].dir;
                if (before == 0 && winding != 0) {
                    xL = hits[i].x;                     // a span opens here
                } else if (before != 0 && winding == 0) {
                    const double xR = hits[i].x;        // and closes here
                    if (xR <= xL) continue;
                    const int c0 = std::max(0, int(std::floor(xL)));
                    const int c1 = std::min(w_ - 1, int(std::ceil(xR)) - 1);
                    if (c1 < c0) continue;
                    if (acc.empty()) acc.assign(size_t(w_), 0.0);
                    for (int c = c0; c <= c1; ++c) {
                        const double l = std::max(xL, double(c)), r = std::min(xR, double(c + 1));
                        if (r > l) acc[size_t(c)] += (r - l);
                    }
                    cmin = std::min(cmin, c0);
                    cmax = std::max(cmax, c1);
                    any = true;
                }
            }
        }
        if (!any) continue;
        // CG's bitmap memory is row 0 = top while its user space is y-up, so a
        // user y of py lands on memory row h - 1 - py. `rasterise` builds its
        // CTM exactly as the Apple branch does (translate(0,h), scale(1,-1)), and
        // the mask shader reads row 0 as the box's top, so the port has to make
        // the same conversion -- without it every imprint came out mirrored
        // about the box's middle: the date back printed upside down, and an edge
        // print near the canvas edge lost its top half outside the box.
        uint8_t* row = px_.data() + size_t(h_ - 1 - py) * size_t(w_);
        for (int c = cmin; c <= cmax; ++c) {
            const double a = acc[size_t(c)];
            if (a <= 0.0) continue;
            const double v = double(row[c]) + a * weight * 255.0;
            row[c] = uint8_t(std::min(255.0, v + 0.5));
            acc[size_t(c)] = 0.0;
        }
    }
    edges_.clear();
}

// --- text -----------------------------------------------------------------

void Canvas::show_text(const char* postscript_name, double size, const std::string& text, double tracking) {
    const int face = face_for_name(postscript_name);
    const Face& f = face_or_empty(face);
    const double em = size / double(f.units_per_em);
    double pen = 0.0;
    for (unsigned char ch : text) {
        if (ch < 0x20) { pen += kFallbackAdvance * size + tracking; continue; }
        if (!f.ok) { pen += kFallbackAdvance * size + tracking; continue; }
        const int glyph = stbtt_FindGlyphIndex(&f.info, ch);
        if (glyph) {
            stbtt_vertex* verts = nullptr;
            const int n = stbtt_GetGlyphShape(&f.info, glyph, &verts);
            if (n > 0) {
                // Every contour starts at a moveto; within one, lines and
                // quadratic/cubic curves. The curves are flattened in glyph
                // space (before the transform), which is where their own
                // curvature is uniform.
                double sx = 0, sy = 0, cx = 0, cy = 0;
                bool started = false;
                auto put = [&](double gx, double gy) {
                    const double ux = (pen + gx * em) * text_m_[0];
                    const double uy = (gy * em) * text_m_[1];
                    if (!started) { move_to(ux, uy); started = true; }
                    else line_to(ux, uy);
                };
                for (int i = 0; i < n; ++i) {
                    const stbtt_vertex& v = verts[i];
                    if (v.type == STBTT_vmove) {
                        if (started) close_path();
                        started = false;
                        sx = v.x; sy = v.y;
                        put(sx, sy);
                        cx = sx; cy = sy;
                    } else if (v.type == STBTT_vline) {
                        put(v.x, v.y);
                        cx = v.x; cy = v.y;
                    } else if (v.type == STBTT_vcurve) {
                        const double x0 = cx, y0 = cy;
                        const double qx = v.cx, qy = v.cy, x1 = v.x, y1 = v.y;
                        const int steps = 8;
                        for (int k = 1; k <= steps; ++k) {
                            const double t = double(k) / double(steps), it = 1.0 - t;
                            put(it * it * x0 + 2 * it * t * qx + t * t * x1,
                                it * it * y0 + 2 * it * t * qy + t * t * y1);
                        }
                        cx = x1; cy = y1;
                    } else if (v.type == STBTT_vcubic) {
                        const double x0 = cx, y0 = cy;
                        const int steps = 10;
                        for (int k = 1; k <= steps; ++k) {
                            const double t = double(k) / double(steps), it = 1.0 - t;
                            put(it * it * it * x0 + 3 * it * it * t * double(v.cx) +
                                    3 * it * t * t * double(v.cx1) + t * t * t * double(v.x),
                                it * it * it * y0 + 3 * it * it * t * double(v.cy) +
                                    3 * it * t * t * double(v.cy1) + t * t * t * double(v.y));
                        }
                        cx = v.x; cy = v.y;
                    }
                }
                if (started) close_path();
            }
            stbtt_FreeShape(&f.info, verts);
            pen += glyph_advance_em(f, glyph) * size;
        }
        pen += tracking;
    }
    // CTLineDraw paints; building the outlines alone paints nothing. The
    // caller's begin_path() opened the path, so close it out here.
    fill_path();
}

// ---------------------------------------------------------------------------

bool gaussian_planar(std::vector<float>& buf, int w, int h, const float* kernel, int taps, bool horizontal) {
    if (buf.empty() || w <= 0 || h <= 0 || !kernel || taps <= 0) return false;
    const int r = taps / 2;
    std::vector<float> src(buf.size());
    src.swap(buf);
    if (horizontal) {
        for (int y = 0; y < h; ++y) {
            const float* in = src.data() + size_t(y) * size_t(w);
            float* out = buf.data() + size_t(y) * size_t(w);
            for (int x = 0; x < w; ++x) {
                float s = 0.0f;
                for (int k = -r; k <= r; ++k) {
                    int xx = x + k;
                    xx = xx < 0 ? 0 : (xx >= w ? w - 1 : xx);   // kvImageEdgeExtend
                    s += in[xx] * kernel[k + r];
                }
                out[x] = s;
            }
        }
    } else {
        for (int x = 0; x < w; ++x) {
            for (int y = 0; y < h; ++y) {
                float s = 0.0f;
                for (int k = -r; k <= r; ++k) {
                    int yy = y + k;
                    yy = yy < 0 ? 0 : (yy >= h ? h - 1 : yy);
                    s += src[size_t(yy) * size_t(w) + size_t(x)] * kernel[k + r];
                }
                buf[size_t(y) * size_t(w) + size_t(x)] = s;
            }
        }
    }
    return true;
}

}  // namespace port
}  // namespace spk
