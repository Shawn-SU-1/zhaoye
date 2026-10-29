# English launch copy

> For r/AnalogCommunity, r/androidapps, Hacker News (Show HN), AlternativeTo.
> HN wants the technical hook first; Reddit wants the result first. Two versions below.

---

## Show HN

**Title**: Show HN: ZHAOYE – a darkroom on Android, running a full film pipeline in Vulkan compute

**Body**:

Most "film" apps apply a LUT. This one runs the pipeline:

```
scene light → film exposure → development (dye density) → enlarger →
photo paper → development → scan → sRGB
```

The chain and the 28 film stocks come from Andrea Volpato's spektrafilm — the dye density
curves were measured with a spectrophotometer against real film, not colour-matched by eye.
Hanze Qiu ported that to a C++/Vulkan engine (originally Metal); I ported it to Android:
C ABI → JNI, four Metal kernels rewritten as GLSL compute, the CoreGraphics/CoreText
rasteriser replaced with 393 lines of scanline fill + stb_truetype, RAW via LibRaw 0.22.2
statically linked into the .so.

Consequences you can see:
- The exposure slider is the enlarger's exposure time. The M/Y sliders are dichroic CC values.
- Film-layer edits (grain, halation) re-run the whole pipeline (~3 s); print-layer edits
  (filtration) take the reprint path (~0.3 s) and are deterministic — zeroing filtration
  reproduces the baseline pixel for pixel.
- The black border is the *negative's* edge, not a paper border: sprocket holes, edge
  lettering, DX barcode, orange date-back burned onto the base. ~2,300 lines of C++ that
  generate it from real film geometry.

Known rough edges: exporting at full source resolution (42 MP) exceeds the phone GPU's 512 MiB
storage buffer limit and trips `VK_ERROR_DEVICE_LOST` on an Adreno 660 — the app self-heals by
rebuilding the engine and dropping to 3200 px, but you lose that frame. The real fix is
overlapped tiled rendering, not done yet. Live preview is a synchronous dispatch, 3–5 s per
1600 px frame.

arm64-v8a only, Android 8.0+, Vulkan 1.1. No emulator. GPL-3.0-or-later; all my changes to
the upstream engine are in one `git apply`-able patch.

https://github.com/Shawn-SU-1/zhaoye

---

## r/AnalogCommunity

**Title**: I ported a measured-film-simulation engine to Android — it runs the whole
negative→print chain on the phone GPU, not a colour LUT

**Body**:

Two things always bothered me about phone "film" apps:

1. They know what film *looks* like, not why it looks that way.
2. The black border around the frame is presented as a paper border. Paper has no border.
   That's the negative's edge — sprockets, edge lettering, DX code, the orange date-back.

So I took Andrea Volpato's `spektrafilm` (28 stocks, dye densities measured with a
spectrophotometer, published as CC BY-SA) and Hanze Qiu's C++/Vulkan port of it, and put the
whole chain on an Android phone: scene light → film exposure → development → enlarger →
paper → development → scan → sRGB.

What that buys you in practice:

- Switch from Portra 400 to Gold 200 and the pixels change by 3.09 mean — because the
  emulsions actually differ in the data, not in a preset.
- Grain and halation live on the film layer: changing them re-develops the negative.
- Filtration is a dichroic head (M/Y in CC). ±30 CC reprints in ~0.3 s and is deterministic.
- The date-back reads the shot's EXIF (`'25 8 24`), or you can set it by hand.
- RAW straight in — ARW/NEF/CR2/CR3/DNG/RAF/ORF/RW2 via LibRaw, half-size AHD, 0.45 s for a
  42 MP ARW.

Honest limitations: Android 8.0+ arm64 with Vulkan 1.1 only, no emulator. Full-resolution
export of a 42 MP file trips the phone GPU's buffer limit (the app drops to 3200 px and warns
you). Preview is 3–5 s per frame today. Only tested on one device so far — an Adreno 660 —
so reports from Mali and other Adrenos are genuinely useful.

Open source, GPL-3.0-or-later: https://github.com/Shawn-SU-1/zhaoye

Happy to answer anything about the port: the GLSL rewrites, the rasteriser replacement, or
why the date-back was upside down for a week.
