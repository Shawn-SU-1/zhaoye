# 照夜 / ZHAOYE

[![release](https://img.shields.io/github/v/release/Shawn-SU-1/zhaoye?label=release&color=9a6b16)](https://github.com/Shawn-SU-1/zhaoye/releases/latest)
[![platform](https://img.shields.io/badge/platform-arm64--v8a%20%C2%B7%20Android%208.0%2B-444)](https://github.com/Shawn-SU-1/zhaoye#装上)
[![engine](https://img.shields.io/badge/engine-Vulkan%20compute-444)](https://github.com/Shawn-SU-1/zhaoye#它借用了什么)
[![license](https://img.shields.io/badge/license-GPL--3.0--or--later-444)](https://github.com/Shawn-SU-1/zhaoye/blob/main/LICENSE)

**简体中文** · [日本語](README.ja.md) · [English](README.en.md)

> **下载照夜 0.2.4.1**
>
> [GitHub 正式版](https://github.com/Shawn-SU-1/zhaoye/releases/latest) ·
> [官方网站](https://zhaoye.8889.hair/) ·
> [蓝奏云国内快速下载（密码 23nt）](https://wwbjy.lanzouv.com/b0hebl34b) ·
> [百度网盘（提取码 h1ec）](https://pan.baidu.com/s/1f_aZvZQsR4no8KrLcxiJHQ?pwd=h1ec)
>
> Android 8.0 以上、支持 Vulkan 1.1 的 arm64 真机。GitHub Release 是正式 `0.2.4.1` 发布包；
> 国内网络可优先使用蓝奏云。App 每次启动也会自动检查新版本，失败时不会影响编辑。

安卓暗房：同一份 C++20 光谱冲印引擎，用 Vulkan compute 在手机上跑，
不是对着效果重画一遍滤镜。

装不上或出问题，先看[装上](#装上)和[出问题了](#出问题了)；打算修什么、不做什么，
见 [ROADMAP.md](ROADMAP.md)。

> **照夜**，取自唐玄宗的名马「照夜白」——一身白毛，黑夜里也照得见。
> 韩幹为它画过像，那张《照夜白图》今天还在大都会博物馆。
> 暗房也正是这样一间屋子：它黑着，得把光放进去，影才浮出来。
> 这个名字不是说要照亮照片，是说让一张底片在没有光的房间里，重新被照亮一次。

包名 `com.shawn.zhaoye`。引擎库仍叫 `libspektralab.so`：它是上游 SpektraLab 的
安卓移植，这个名字就是它的出处。

## 0.2 的工作流

- 单屏暗房：照片、四组参数和底部导航同时可见。
- 光影、质感、胶片、片边四个调参单元，滑动后保留最新一次参数。
- 双指缩放、双击放大；停顿后加载 3400px 细节帧，检查颗粒和片边字。
- 原图/成片分割对比；片边开启时自动使用无片边对比成片，保证画面对齐。
- 设置页内置照夜专用跑分，根据真实 800px 冲印、片边、重印和 1600px 收尾耗时建议是否开启实时预览。
- 保存和分享前可直接选择成片尺寸。

```
场景光 → 胶片曝光 → 显影（染料密度）→ 放大机 → 相纸 → 显影 → 扫描 → sRGB
```

## 长什么样

| 暗房 | 片边 |
|---|---|
| ![暗房界面](docs/screenshots/darkroom-preview.png) | ![片边](docs/screenshots/film-edge.png) |

左边是一张真实照片冲出来的样子：外面那圈带齿孔、片边字符和日期后背的黑边，是负片的
片边，不是相纸的边框。右边是片边参数全开时的样子。

![边缘字 4 倍放大](docs/screenshots/edge-print-zoom.png)

上面是成片顶部边缘字的 4 倍放大——它是**正向**的，也没被裁。这两点曾经都是 bug，见
`docs/acceptance-2026-10-29.md` 第 10 条。

## 它借用了什么

这条链是两层，两层的署名都不能省：

| 内容 | 来源 | 许可 |
|---|---|---|
| 胶片物理模型、28 套实测色配置、放大 LUT | [andreavolpato/spektrafilm](https://github.com/andreavolpato/spektrafilm) by Andrea Volpato | 配置与 LUT：**CC BY-SA 4.0** |
| 原生 C++/Vulkan 引擎移植、C ABI | [JamesQiu2005/SpektraLab](https://github.com/JamesQiu2005/SpektraLab) by Hanze Qiu | **GPL-3.0-or-later** |
| Android 适配层、JNI、UI、LibRaw 接入 | 本项目 | **GPL-3.0-or-later** |

原始项目是 Andrea Volpato 的 **spektrafilm**（Python/numba 参考实现，含全部实测片基数据）；
SpektraLab 是它的原生 C++/Metal 移植，本项目再用这份移植的 Vulkan 后端跑安卓。
引擎源码以**裁剪副本**形式放在本仓库 `engine/` 下：只保留编译 `libspektralab.so` 需要的
`include/` 与 `src/`，以及 `third_party/{volk,stb}`。改了什么、怎么对回上游，见
`engine/README.md` 与 `patches/0001-android-port.patch`（相对上游 `fd891c6`，10 个文件）。

**许可**：本应用是 GPL-3.0-or-later 的衍生作品，分发时必须同样开源。
`assets/engine/profiles/*.json` 与 `print_luts.json` 是 spektrafilm 的原始配置或其直接编码，
沿用 **CC BY-SA 4.0**：必须署名、保留许可、注明修改，衍生版本也要维持 CC BY-SA 4.0。
本项目对引擎的全部改动集中在 `patches/0001-android-port.patch`：Vulkan 后端对移动 GPU 放宽 FMA
探针、`overscan.cpp` 的 Apple 服务分叉、四个片边 kernel 的 GLSL 版本、可移植光栅化层的接入点。
在 `fd891c6` 的干净检出上 `git apply` 即可复现。

## 结构

```
app/src/main/
├── cpp/jni_bridge.cpp      引擎 C ABI ↔ JNI（像素与 JSON 过桥，RAW 解码也在这）
├── cpp/spk_raster.{hpp,cpp} 可移植光栅化层：扫描线填充 + stb_truetype 字形 + 标量卷积
├── cpp/spk_probe.cpp       设备端自检：不开界面直接渲一张灰阶
├── cpp/spk_raw_probe.cpp   设备端 RAW 探针：量解码尺寸、耗时、峰值内存
├── assets/engine/          17 MB 烘焙资源（常量、28 份配置、8 张 LUT、字体）
├── assets/engine/vulkan/   38 个 compute kernel 的 SPIR-V
└── java/com/shawn/zhaoye/
    ├── Engine.kt           引擎句柄与会话
    ├── Stocks.kt           20 款胶片 / 8 款相纸（从 profile 生成）
    ├── DarkroomViewModel   串行化的原生调用、渲染节流、导出
    ├── Settings.kt         设置页与致谢
    ├── Splash.kt           启动动画
    └── MainActivity.kt     暗房界面
engine/                     上游引擎的裁剪副本，见 engine/README.md
├── include/  src/          编译 libspektralab.so 用到的全部源码
└── third_party/{volk,stb}  Vulkan loader 与 stb_truetype
scripts/
├── env.sh                 路径与工具链解析（NDK / LibRaw / clang 前缀）
├── build-libraw.sh        交叉编译 LibRaw → build/libraw/arm64-v8a/libraw.a
├── build-native.sh        交叉编译 libspektralab.so
└── build-probe*.sh        设备端自检 / PNG 探针 / RAW 探针
patches/
└── 0001-android-port.patch  本项目对上游引擎的全部改动（10 个文件）
```

## 构建

需要 JDK 21、Android SDK（platform 37 + build-tools）、NDK r28c+，以及 LibRaw 0.22.2 的源码。

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/Android/Sdk      # 或者写进 local.properties 的 sdk.dir
export ANDROID_NDK_ROOT=/path/to/android-ndk-r28c   # 也可用 SDK 里的 ndk/<ver>
export LIBRAW_SRC=/path/to/LibRaw-0.22.2      # 也可放到 third_party/ 或 ../deps/

bash scripts/build-libraw.sh     # 首次：LibRaw → build/libraw/arm64-v8a/libraw.a
bash scripts/build-native.sh     # 引擎 → app/src/main/jniLibs/arm64-v8a/libspektralab.so
./gradlew assembleDebug
```

`scripts/env.sh` 依次在 `$ANDROID_NDK_ROOT`、`../deps/`、`third_party/` 里找 NDK 与 LibRaw，
三处都没有才报错。NDK 与 LibRaw 都不进仓库。

资源与 SPIR-V 已在仓库里，只有改引擎或改 `.comp` shader 时才需要重跑前两步；改 shader 还要
用 `glslc` 重新烘焙 `assets/engine/vulkan/*.spv`。

没有预编译的 `libspektralab.so`：`app/src/main/jniLibs/` 是 `build-native.sh` 的产物，不入库。

编译目标只有 `arm64-v8a`（Vulkan compute + 16 KB 页对齐），`minSdk` 26、`targetSdk` 36。
x86 模拟器跑不了，真机需要 Vulkan 1.1 以上。

> 国内网络下 Gradle 发行包可能下不动，把 `gradle/wrapper/gradle-wrapper.properties` 里的
> `distributionUrl` 换成镜像即可，例如
> `https\://mirrors.cloud.tencent.com/gradle/gradle-9.4.1-bin.zip`。

## 装上

### 直接装

到 [Releases](https://github.com/Shawn-SU-1/zhaoye/releases/latest) 取
`Zhaoye-0.2.4.1-arm64-v8a.apk`（30.1 MB），在手机上打开安装。国内网络可使用
[蓝奏云（密码 23nt）](https://wwbjy.lanzouv.com/b0hebl34b) 或
[百度网盘（提取码 h1ec）](https://pan.baidu.com/s/1f_aZvZQsR4no8KrLcxiJHQ?pwd=h1ec)。
这是侧载，系统会问一次
要不要允许安装未知来源应用。签名是 RSA-4096 的发布密钥，证书 SHA-256
`c4b5c76576838005bf90de0a55331341d9b3132e0ace0077a581385f3b43ca65`；apk 自身的校验值：

```bash
sha256sum Zhaoye-0.2.4.1-arm64-v8a.apk
# 65169ff10960e10f7ad8637a2e68bf41f9ad5974e706e36b1873b0e33240dac8
```

以前在本地装过 debug 版的，**先卸载再装**：签名不同，覆盖安装会被系统拒绝。

### 自己出包

仓库里不存 APK（`*.apk` 在 `.gitignore` 里）。按上一节出包后：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 跑得动的前提

只有 `arm64-v8a`，要 Android 8.0（API 26）以上且支持 Vulkan 1.1——模拟器跑不了，得真机。
App 不申请存储权限：选图走系统 Photo Picker，RAW 用 `content://` 读进来。

## 手机上的实测

Xiaomi `cetus` / Adreno 660 / Android 11：

```
build: spektrafilm-native 0.1.0 (math=probed)
create ok
render ok 512x512 stride=512 643.2 ms reprint=0
step 0: 3462 3696 4481      step 4: 30489 31245 31226
step 1: 3977 4433 4902      step 5: 40043 40445 40692
step 2: 9162 9720 9486      step 6: 45882 47080 47531
step 3: 20051 20459 20331   step 7: 50527 51002 51200
```

八级中性灰进、八级中性灰出，单调、无偏色，趾部与肩部的压缩符合负片+相纸的响应。
`reprint` 约 300 ms，走的是缓存的负片。

交互实测（33 MP JPEG 源，live 档 1600 px）：

```
曝光 +2.0 EV（SHOOT 层，整段重跑）      约 3 s，画面平均亮度 81.5 -> 123.4
M/Y 滤色 ±30 CC（PRINT 层，重印）        约 0.3 s，72~78% 像素改变
滤色归零后与基线逐像素一致              重印是确定性的
导出（preview 档 3200 px，MediaStore）   约 4 s，3200x2134 / 2.77 MB
```

### 参数被拒会连带整批失效

引擎的 `validate_delta` 对越界值是**拒绝整个 delta**，不是钳制：`m_filter_shift` /
`y_filter_shift` 的取值范围是 −1…1，滑杆若按 0…100 下发，一次 100 CC 的请求会让同一批里
的曝光、颗粒、光晕全部不生效，而 `spk_render` 仍然成功返回，于是表现为"滑杆没反应"。
现在的做法是：滑杆用引擎自己的单位，发车前再 `coerceIn` 一次，并把 `set_params` 返回的
`error` 显示到状态栏，而不是静默丢弃。

两个连带修正：

- **`product_defaults`**：open 的 delta 里加 `"product_defaults": true`。默认的
  `enlarger.print_exposure_compensation` 会拿补偿后的中灰去归一化，把相机曝光补偿在印相
  阶段抵消掉——这正是上游还需要一个"印相自动补偿"开关的原因。不传这个键，曝光滑杆几乎
  看不出变化。
- **`invalidated` 是字符串**：`spk_set_params` 回复的是 `"shoot"` 或 `"print"`，不是数组。
  按数组解析会恒判为 dirty，每次都整段重跑；按 `!= "print"` 判，print 层编辑才能走重印的
  快路径。

## RAW

LibRaw 0.22.2 交叉编译进 `libspektralab.so`（静态链，`.so` 从 1.6 MB 到 2.5 MB）。
`spk_open` 只要像素，所以 RAW 解码在 ABI 的外侧，和引擎同进程：

```
文件 → LibRaw（半尺寸 AHD，ProPhoto，16-bit）→ 箱式降采样 → spk_open
```

解码、降采样、开图在**一次 JNI 调用**里做完，像素不跨边界：42 MP 解出来是 254 MB 的
16-bit RGB，往返一趟等于把它翻倍。

| 设置 | 输出 | 解码 | 峰值 RSS |
|---|---|---|---|
| `half_size=1` + AHD | 3984×2660 | 0.45 s | 253 MB |
| `half_size=0` + AHD | 7968×5320 | 3.1 s | **688 MB** |

选半尺寸：688 MB 在 MIUI 上随时会被杀，而 3984 px 已经高于导出用的 3200 px。
色彩策略与桌面 `raw_render` 一致——as-shot WB、camera matrix、线性 ProPhoto、16-bit、
不自动提亮、不裁切；**唯一的偏离就是 `half_size`**。

实测（Sony ILCE-7RM3，42 MP ARW）：预览解码 680 ms，导出 3200×2136 约 3 s。
Nikon 的 HE/HE* 压缩 NEF 不在 LibRaw 0.22.2 的能力内，会明确报错而不是解出坏图。

## 片边

“相纸有边框”是个常见误解：看到的白边/黑边不是相纸的边，是**负片的片边**——
未曝光的片基、齿孔、片边字符（胶片型号、画幅号）、DX 码。把负片连同片边一起
放进放大机，才印得出那圈边。相纸本身永远是无边的。

`engine/src/pipeline/overscan.cpp` 是完整实现：片门形状、齿孔、片边字符、DX 条形码、
日期后背、漏光与灰雾，2300 行纯 C++ 数学。它原本卡在两处 Apple 服务上，现在都不用了：

| 原来的依赖 | 用来做什么 | 安卓这边 |
|---|---|---|
| `CoreGraphics` / `CoreText` | 把多边形、椭圆、字形光栅化成覆盖率位图 | `app/src/main/cpp/spk_raster.{hpp,cpp}`：扫描线填充器 + `stb_truetype` 字形轮廓，393 行 |
| `Accelerate` (vImage) | 覆盖率掩码的可分离卷积 | 同一个文件里的标量 `gaussian_planar` |
| 4 个 Metal kernel | 片基曝光、挖掩码、齿孔归零、扫描看齿孔的切壁 | `spk_overscan_{canvas,add_mask,film_present,light}.comp`，位对位翻成 GLSL |

`overscan.cpp` 里用 `#if defined(__APPLE__)` 分叉，四处，上游 `git pull` 不冲突。
字体不靠 ROM：`assets/engine/fonts/` 里带了三份 DroidSans（Apache-2.0），运行时读进内存。

已知的一个细节：`CGContextShowTextAtPoint` 的量法是“画”，而扫描线填充器只建路径——
`show_text()` 末尾必须补一次 `fill_path()`，否则片边字全部消失而其他图元正常。

## 已知限制

- **fma 探针**：Adreno 把 `precise fma()` 折回乘法，探针会判"未融合"。引擎本来就为这种情况
  留了 Dekker 精确乘积（specialization constant 0），本项目把这条例径从 CPU 设备放宽到
  GPU，并在 `capabilities.backend.math_mode` 里如实上报。设 `SPEKTRALAB_STRICT_FMA=1`
  可恢复上游的拒绝行为。
- **只有 arm64-v8a**，`minSdk 26`，需要 Vulkan 1.0。
- **输入是 JPEG/PNG/HEIF/RAW**（ARW、NEF、CR2/CR3、DNG、RAF、ORF、RW2 等，见
  `DarkroomViewModel.RAW_EXTENSIONS`）。
- **Android 动态照片可编辑封面帧**：系统图库通常会把 Motion Photo 的主图作为
  JPEG/HEIF 暴露给 App，因此可以直接选入、调整和导出静态成片；目前尚不能识别、
  编辑内嵌视频或重新封装成动态照片。
- 导出四档：3200 / 4800 / 6400 / 片源原尺寸。骁龙 888（Adreno 660）实测能到
  **6400×4273（27 MP）**；片源原尺寸（A7R3 的 7968×5320，42 MP）会让 Vulkan 设备掉线，
  见下节。
- **没有半格拼接、CLI/MCP**——上游这些功能依赖 Apple 服务，移植同样是拒绝而不是模拟。

上面几条哪些在修、哪些不做，以及分块渲染的具体方案，都写在 [ROADMAP.md](ROADMAP.md)。

## 全尺寸导出能到多大

一条管线同时要活几张全尺寸浮点图，而 `spk_capabilities` 会报出这台设备装得下什么：

```
max_storage_buffer_range  536870912      // 512 MiB，一张图的上限
heap_device_local_bytes   12058796032     // 统一内存，没有独立显存
```

7968×5320 的 float32 RGB 一张是 **485 MiB**，已经是 512 MiB 上限的 95%；管线要同时拿
好几张，于是 Adreno 660 在 `IOCTL_KGSL_DEVICE_WAITTIMESTAMP_CTXTID` 上返回
`EDEADLK`，Vulkan 报 `VK_ERROR_DEVICE_LOST (-4)`。6400×4273 一张是 315 MiB，能跑完。

两次实测（同一张 ARW）：

| 导出长边 | 像素 | 结果 |
|---|---|---|
| 6400 | 6400×4273 / 27 MP | 成功，每张浮点图 315 MiB |
| 7968（原图） | 7968×5320 / 42 MP | `VK_ERROR_DEVICE_LOST`，每张 485 MiB |

掉线会毒化整个引擎（之后每次 dispatch 都返回同一个错），所以 App 在识别到 `-4` 之后会
重建引擎、把导出长边退回 3200、把当前底片重新上胶卷，并在状态栏留下那句话。

真正的解法是**分块渲染**（overlapped tiling）：把整帧切成带边距的瓦片，每块跑完整条管线
再拼回，halation/grain 这类卷积靠每块的 halo 保证衔接。这是引擎层的改动，还没做。
- 渲染是同步 dispatch，一张 1600 px 的 live 预览在 Adreno 660 上约 3–5 秒。

## 出问题了

在 [Issues](https://github.com/Shawn-SU-1/zhaoye/issues/new/choose) 开一条。

这个 App 的效果取决于 GPU 和驱动，同一版 APK 在不同机器上差别可能很大，所以
**请附上诊断报告**：设置页 →「导出日志文件」，会生成一个 `zhaoye-YYYYMMDD-HHmmss.txt`，
把那个文件本身拖进 issue 输入框即可（GitHub 附件支持直接拖拽）。

报告里已经带了机型、系统版本、ABI、内存、引擎 capabilities、最近 800 条应用日志，
以及本进程的 logcat——从引擎启动、选片、参数被拒到渲染耗时都在里面。
它是一份自包含的纯文本，不需要 root，也不申请任何权限。

> logcat 会记下打开过哪个文件，所以报告里可能带照片路径或文件名。
> 提交前扫一眼，不愿意公开的就删掉那几行。
