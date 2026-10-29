// spk_raster.h -- Android port: the two Apple frameworks `overscan.cpp` needs,
// replaced by something that runs anywhere.
//
// `overscan.cpp` reaches for CoreGraphics in exactly one place (`rasterise`:
// the imprint groups become a canvas-aligned coverage bitmap) and CoreText in
// two (`make_text` inside `rasterise`, and `text_width`), plus one Accelerate
// call (the separable Gaussian on the mask). This header is the minimum of
// those three that the file actually uses:
//
//   CoreGraphics  -> spk::port::Canvas   (8-bit coverage + a CTM stack)
//   CoreText      -> Canvas::show_text   (stb_truetype outlines, filled by the
//                                         same path filler, so rotation and
//                                         non-uniform scaling behave)
//   Accelerate    -> gaussian_planar     (the same kernel, scalar)
//
// The fill rule is nonzero winding, as CGContextFillPath's is, and the
// antialiasing is 4x4 supersampled coverage. The mask is then blurred by
// `gaussian_planar`, so the sampling grid's own error is under the blur.
//
// Fonts arrive as memory blobs (`set_font_blob`, called from JNI_OnLoad once
// the AssetManager has read them). Without them, text draws nothing and
// measures as a fixed advance -- `fonts_ready()` says which world you are in.
#pragma once

#include <array>
#include <cstdint>
#include <string>
#include <vector>

namespace spk {
namespace port {

// Face slots. CoreText is asked for a PostScript name; the port maps the names
// `overscan.cpp` uses onto these three, which are the closest thing on Android.
//   0 = sans regular  (HelveticaNeue, HelveticaNeue-Medium, ArialNarrow)
//   1 = sans bold     (HelveticaNeue-Bold)
//   2 = monospace     (Menlo-Bold)
enum FontFace { kFaceSans = 0, kFaceSansBold = 1, kFaceMono = 2, kFaceCount = 3 };

// Hand a TTF/OTF to the port. The pointer must outlive the process; the caller
// keeps the bytes alive (jni_bridge holds them in a static vector).
void set_font_blob(int face, const unsigned char* data, int size);
bool fonts_ready();

// The face a PostScript name maps onto, or -1 if it is not a text face at all.
int face_for_name(const char* postscript_name);

// Advance of `text` in the current user space, the way
// CTLineGetTypographicBounds reports it: glyph advances plus `tracking` between
// characters (CoreText's kern attribute adds to the advance, it does not
// remove it from the glyph). `size` is the em size. Returns 0 for an empty
// string; an unavailable face still measures, from a built-in table, so the
// layout code does not collapse.
double text_advance(const char* postscript_name, double size, const std::string& text, double tracking);

// ---------------------------------------------------------------------------
// Canvas: the subset of CGBitmapContext that `rasterise` uses.
// ---------------------------------------------------------------------------
class Canvas {
public:
    // Single-channel 8-bit, kCGImageAlphaNone -- the same surface
    // CGBitmapContextCreate is given, so `px[i] / 255` is the coverage.
    Canvas(int w, int h);

    int width() const { return w_; }
    int height() const { return h_; }
    bool ok() const { return w_ > 0 && h_ > 0; }

    // CTM. Post-multiplied, exactly like CGContextTranslateCTM and friends, so
    // the call sequence in `rasterise` can be copied across unchanged.
    void translate(double tx, double ty);
    void scale(double sx, double sy);
    void rotate(double radians);
    void concat(const double m[6]);   // {a, b, c, d, tx, ty}: x' = a x + c y + tx
    void save();
    void restore();

    // Fill colour, 0..1, applied to every fill below.
    void set_gray(double g) { gray_ = g; }

    // Path. One path at a time, like CG: fill_path consumes it.
    void begin_path();
    void move_to(double x, double y);
    void line_to(double x, double y);
    void close_path();
    void add_ellipse(double cx, double cy, double rx, double ry);
    void fill_path();

    // Text. `size` is the em size in user units. The text matrix scales the
    // glyphs before the CTM sees them -- `x` widens the shapes, `y` flips them,
    // and (as in `rasterise`) the advances are *not* scaled by it unless the
    // caller scaled the CTM instead. `tracking` is added to each advance.
    void show_text(const char* postscript_name, double size, const std::string& text, double tracking);
    void set_text_matrix(double x, double y);

    const std::vector<uint8_t>& pixels() const { return px_; }

private:
    struct Seg { double x0, y0, x1, y1; };   // a contour edge, in canvas pixels
    void emit_contour(std::vector<double>& pts);   // flushed by fill_path
    void push_point(double x, double y);           // user -> device

    int w_ = 0, h_ = 0;
    std::vector<uint8_t> px_;
    double ctm_[6] = {1, 0, 0, 1, 0, 0};
    std::vector<std::array<double, 6>> stack_;
    double gray_ = 1.0;
    double text_m_[2] = {1, -1};   // {x scale, y scale}
    std::vector<double> path_;     // device-space polyline, one contour at a time
    std::vector<Seg> edges_;       // flattened, device space, for the scan filler
    bool have_subpath_ = false;
};

// Separable Gaussian, the scalar stand-in for vImageConvolve_PlanarF with
// kvImageEdgeExtend. `horizontal` picks the pass. Returns false on a null
// buffer or an empty kernel.
bool gaussian_planar(std::vector<float>& buf, int w, int h, const float* kernel, int taps, bool horizontal);

}  // namespace port
}  // namespace spk
