// Off-path adapters for upstream effects that still depend on Apple services.
// Mac build.sh globs pipeline/*.cpp, so the whole translation unit is guarded.
#ifndef __APPLE__
#include "pipeline.hpp"
#include "windows_unported.hpp"

namespace spk {

// Android port: the overscan / date-imprint stubs that used to live here are
// gone -- pipeline/overscan.cpp is compiled into this backend now, and it
// supplies overscan_wanted(), overscan_gate_long_mm(), overscan_frame(),
// set_overscan_frame(), overscan_geometry_json() and the three node bodies.
// Its CoreGraphics/CoreText/Accelerate calls are branched to spk_raster.hpp
// and its four Metal kernels to src/shaders/vulkan/spk_overscan_*.comp.
// What remains below is contrast_mask, which is still unported.

bool Pipeline::contrast_mask_wanted() const { return params_.print_render.contrast_mask.active; }

void Pipeline::release_contrast_mask() {
    mask_on_ = false;
    mask_coef_ = gpu::BufferRef{};
    band_row0_ = 0;
}

bool Pipeline::prepare_contrast_mask(const Image&, std::string& error, std::vector<float>* delta) {
    release_contrast_mask();
    if (delta) delta->clear();
    if (!contrast_mask_wanted()) return true;
    error = windows_unported_error(false, false, true, false, false);
    return false;
}

bool Pipeline::contrast_mask_field(const Image& cmy, std::vector<float>& delta, uint32_t& gw,
                                   uint32_t& gh, std::string& error) {
    gw = gh = 0;
    return prepare_contrast_mask(cmy, error, &delta);
}

bool Pipeline::node_contrast_mask_epilogue(const Image&, Image&, std::string& error) {
    error = windows_unported_error(false, false, true, false, false);
    return false;
}

}  // namespace spk
#endif  // !__APPLE__
