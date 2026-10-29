# engine/

上游引擎的裁剪副本：`JamesQiu2005/SpektraLab` 的 `engine/` 目录，基线提交 `fd891c6`，
外加本项目在 `patches/0001-android-port.patch` 里的全部改动。

许可 **GPL-3.0-or-later**，见仓库根的 `LICENSE`。它本体又是 Andrea Volpato 的
[`spektrafilm`](https://github.com/andreavolpato/spektrafilm) 的原生移植，那部分配置与 LUT
的许可另见 `LICENSE-SPEKTRAFILM.txt`。

## 保留了哪些

| 目录 | 内容 | 说明 |
|---|---|---|
| `include/` | 对外 C ABI 头 | 原样 |
| `src/core/` | 光谱、显影、曲线、profile、LUT | 原样 |
| `src/pipeline/` | 管线、片边、场景纬度 | 原样（`overscan.cpp` 有分叉） |
| `src/gpu/` | Metal 与 Vulkan 后端 | 原样；只有 `vulkan_gpu.cpp` 参与安卓构建 |
| `src/io/` `src/desktop/` | 只给 macOS/Windows 目标用 | 原样保留，安卓不编译 |
| `src/shaders/` | Metal 与 GLSL 源码 | 原样；Vulkan 的 `.comp` 含本项目新增的 4 个片边 kernel |
| `third_party/volk` | Vulkan loader | 原样（MIT） |
| `third_party/stb` | `stb_truetype` 等 | 原样（public domain / MIT） |
| `PORT_NOTES.md` | 上游的移植笔记 | 原样 |

## 没有带哪些

- `third_party/metal-cpp`（1.3 MB，Apple 专用）。`metal_gpu.cpp` / `metal_impl.cpp` 因此在本
  仓库里无法编译——安卓构建本来也不编译它们。
- `resources/`（15 MB 原始资源）与 `tests/`：运行时资源已经烘焙进
  `app/src/main/assets/engine/`，不需要再来一份。
- 各平台的构建脚本与桌面壳。

## 和上游对齐

```bash
git clone https://github.com/JamesQiu2005/SpektraLab.git
cd SpektraLab && git checkout fd891c6
git apply /path/to/zhaoye/patches/0001-android-port.patch
```

反过来，要拿新版上游同步进本仓库：

```bash
# 在干净的上游检出上打好补丁，然后把 engine/{include,src,third_party/volk,third_party/stb}
# 覆盖过来，再重跑 scripts/build-native.sh 验证。
```

`patches/0001-android-port.patch` 里改到的每一处都带注释说明为什么改，冲突通常只在
`overscan.cpp` 与 `vulkan_gpu.cpp` 这两处集中分叉点。
