# patches/

本项目对上游引擎做的全部改动，以补丁形式留档，方便回看、回上游、回滚。

## 0001-android-port.patch

- 基线：`JamesQiu2005/SpektraLab` @ `fd891c6`
- 涉及 10 个文件：`gpu.hpp`、`vulkan_gpu.cpp`、`engine.cpp`、`overscan.cpp`、
  `windows_unported.{cpp,hpp}`，以及新增的 4 个 Vulkan kernel
  `spk_overscan_{canvas,add_mask,film_present,light}.comp`
- 应用：`git apply patches/0001-android-port.patch`

分四类：

1. **FMA 探针**（`vulkan_gpu.cpp`）：Adreno / Mali 会把 `precise fma()` 折回乘法，探针因此
   判"未融合"。引擎本来就为这种情况留了 Dekker 精确乘积（specialization constant 0），
   这里把该支路的准入从 CPU 设备放宽到 GPU，并在 `math_mode` 里如实上报。上游的拒绝行为
   仍可用 `SPEKTRALAB_STRICT_FMA=1` 打开。
2. **Apple 服务分叉**（`overscan.cpp`、`windows_unported.{cpp,hpp}`、`engine.cpp`）：
   `CoreGraphics` / `CoreText` 的路径分支到 `app/src/main/cpp/spk_raster.hpp`，
   `Accelerate` 的卷积换成同一个文件里的标量实现。字体不再依赖系统 ROM。
3. **四个片边 kernel**（`src/shaders/vulkan/*.comp`）：片基曝光、挖掩码、齿孔归零、
   扫描看齿孔的切壁，从 Metal 逐位翻成 GLSL。
4. **`gpu.hpp`**：片边相关的 dispatch 入口。

没有把 `spk_raster.cpp` 放进补丁：它属于 App 侧（`app/src/main/cpp/`），不属于引擎。

## 已并入的历史补丁

`0001-android-unfused-fma.patch` 曾经单独存在，现在被上面的第 1 类完全覆盖，已删除。
