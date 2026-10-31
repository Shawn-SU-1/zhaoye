# 照夜 / ZHAOYE

[![release](https://img.shields.io/github/v/release/Shawn-SU-1/zhaoye?label=release&color=9a6b16)](https://github.com/Shawn-SU-1/zhaoye/releases/latest)
[![platform](https://img.shields.io/badge/platform-arm64--v8a%20%C2%B7%20Android%208.0%2B-444)](README.ja.md#インストール)
[![engine](https://img.shields.io/badge/engine-Vulkan%20compute-444)](README.ja.md#クレジットとライセンス)
[![license](https://img.shields.io/badge/license-GPL--3.0--or--later-444)](https://github.com/Shawn-SU-1/zhaoye/blob/main/LICENSE)

[简体中文](README.md) · **日本語** · [English](README.en.md)

> **照夜 0.2.1 をダウンロード**
>
> [GitHub Release](https://github.com/Shawn-SU-1/zhaoye/releases/latest) ·
> [中国本土向け Lanzou Cloud ミラー](https://wwbjy.lanzouv.com/i3EI64e0cfvc)
>
> Android 8.0 以上、Vulkan 1.1 対応の arm64 実機が必要です。GitHub Release が
> 正式な `0.2.1` パッケージです。中国本土では Lanzou Cloud も利用できます。
> アプリは起動時に更新を確認し、失敗しても編集を妨げません。

同じ C++20 スペクトル現像エンジンを Vulkan compute でスマートフォン上に
実行する Android 暗室です。効果をフィルターとして描き直すアプリではありません。

インストールできない場合は [インストール](#インストール) を、今後の予定は
[ROADMAP.md](ROADMAP.md) を参照してください。

> **照夜** は唐の玄宗皇帝の名馬「照夜白」に由来します。真っ白な馬体で、夜でも
> 見つけられるほどでした。韓幹が描いた《照夜白図》は現在メトロポリタン美術館に
> あります。暗室も同じような部屋です。暗闇に光を入れ、像が浮かび上がってくる。
> 写真を明るくするのではなく、潜像がもう一度見えるようにする名前です。

パッケージ名は `com.shawn.zhaoye`、ネイティブライブラリ名は
`libspektralab.so` です。

## 0.2 のワークフロー

- 写真、4 つの調整グループ、下部ナビを同じ画面に表示。
- 調整グループは「光と色」「質感」「フィルム」「フィルムエッジ」。
- ピンチズーム、ダブルタップ拡大、静止後に 3400 px の詳細フレームを読み込み。
- 元画像と仕上がりの分割比較。フィルムエッジ有効時は、エッジと日付を切った
  同条件の比較用画像を生成するため、左右の位置が揃います。
- 800 px 本番レンダー、フィルムエッジ、キャッシュ再プリント、1600 px 仕上げを
  実測する照夜専用ベンチマーク。リアルタイムプレビューの推奨設定も表示。
- 保存・共有の前に出力サイズを選択可能。

```text
シーン光 → フィルム露光 → 現像（色素濃度）→ 引き伸ばし機
→ 印画紙 → 現像 → スキャン → sRGB
```

## スクリーンショット

| 暗室 | フィルムエッジ |
|---|---|
| ![darkroom UI](docs/screenshots/darkroom-preview.png) | ![film edge](docs/screenshots/film-edge.png) |

外周の黒い部分は印画紙の縁ではなく、未露光のベース、パーフォレーション、
駒番号、DX コード、日付裏蓋を含むフィルムの縁です。印画紙そのものに縁はありません。

![edge lettering at 4x](docs/screenshots/edge-print-zoom.png)

上の画像は上端の文字を 4 倍に拡大したものです。文字は正立し、切れていません。
どちらも以前は不具合でした。詳細は `docs/acceptance-2026-10-29.md` の項目 10 に
あります。

## クレジットとライセンス

帰属表示は 2 層必要です。

| 内容 | 出典 | ライセンス |
|---|---|---|
| フィルム物理モデル、28 種の実測色設定、プリント LUT | [andreavolpato/spektrafilm](https://github.com/andreavolpato/spektrafilm) by Andrea Volpato | 設定と LUT: **CC BY-SA 4.0** |
| C++/Vulkan ネイティブエンジン移植、C ABI | [JamesQiu2005/SpektraLab](https://github.com/JamesQiu2005/SpektraLab) by Hanze Qiu | **GPL-3.0-or-later** |
| Android 層、JNI、UI、LibRaw 統合 | 本プロジェクト | **GPL-3.0-or-later** |

元プロジェクトは Andrea Volpato の **spektrafilm** です。SpektraLab はその
C++/Metal 移植で、照夜は Vulkan バックエンドを Android に接続しています。
エンジンは `engine/` 以下に必要部分だけを収録しています。上流 `fd891c6` との
差分は `patches/0001-android-port.patch` にあります。

本アプリは GPL-3.0-or-later の派生作品であり、配布時も同じ条件でソースを
公開する必要があります。spektrafilm 由来のプロファイルと LUT は
**CC BY-SA 4.0** です。著作者表示、ライセンス、変更点を保持してください。

## リポジトリ構成

```text
app/src/main/               Compose UI、ViewModel、JNI、アセット
engine/                     上流エンジンの必要部分
scripts/                    LibRaw / ネイティブビルド
patches/                    Android 移植パッチ
```

## ビルド

JDK 21、Android SDK platform 37、build-tools、NDK r28c 以降、
LibRaw 0.22.2 のソースが必要です。

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/Android/Sdk
export ANDROID_NDK_ROOT=/path/to/android-ndk-r28c
export LIBRAW_SRC=/path/to/LibRaw-0.22.2

bash scripts/build-libraw.sh
bash scripts/build-native.sh
./gradlew assembleDebug
```

`scripts/env.sh` は `$ANDROID_NDK_ROOT`、`../deps/`、`third_party/` の順に
NDK と LibRaw を探します。NDK と LibRaw はリポジトリに含みません。

対象 ABI は `arm64-v8a` のみ、`minSdk` は 26、`targetSdk` は 36 です。
エミュレーターでは Vulkan パイプラインが動作しません。

## インストール

[Releases](https://github.com/Shawn-SU-1/zhaoye/releases/latest) から
`Zhaoye-0.2.1-arm64-v8a.apk` を取得するか、
[Lanzou Cloud](https://wwbjy.lanzouv.com/i3EI64e0cfvc) を利用してください。
サイドロードのため、提供元不明アプリのインストール許可を求められる場合があります。

署名証明書 SHA-256:
`c4b5c76576838005bf90de0a55331341d9b3132e0ace0077a581385f3b43ca65`

```bash
sha256sum Zhaoye-0.2.1-arm64-v8a.apk
# cfc1105b59e861fc0f16e5d50acb9139338207ca64365937b1565c4fb9333cc0
```

以前の debug ビルドが入っている場合は、署名が異なるためアンインストールしてから
インストールしてください。

## 実機での性能

Xiaomi `cetus`、Adreno 660、Android 11:

- 800 px フル現像: 約 2.9 fps / 343 ms
- キャッシュ再プリント: 約 9.9 fps / 101 ms
- フィルムエッジ付き 800 px: 約 1.5 fps / 651 ms
- 1600 px 仕上げ: 約 1.5 秒
- 照夜専用スコア: 45/100。この端末ではリアルタイムプレビューをオフにする提案

性能は GPU ドライバーに大きく依存します。アプリ設定のベンチマークで実機の値を
確認してください。

## RAW

LibRaw 0.22.2 を `libspektralab.so` に静的リンクしています。

```text
file → LibRaw（half-size AHD、ProPhoto、16-bit）
     → box downscale → spk_open
```

デコード、縮小、セッション作成は 1 回の JNI 呼び出しで行います。42 MP の
フルデコードはピーク 688 MB になるため、約 3984 px の half-size AHD を使用します。

ARW、NEF、CR2/CR3、DNG、RAF、ORF、RW2 など、LibRaw が対応する形式を扱えます。
Nikon の HE/HE* 圧縮 NEF は LibRaw 0.22.2 では非対応で、明示的なエラーになります。

## フィルムエッジ

`engine/src/pipeline/overscan.cpp` がゲート形状、パーフォレーション、縁の文字、
DX バーコード、日付裏蓋、光漏れ、かぶりを実装しています。Apple の
CoreGraphics/CoreText と Accelerate は Android 用ラスター層に置き換え、
4 つの Metal kernel は Vulkan GLSL 版を追加しています。

同梱の DroidSans フォントを `assets/engine/fonts/` から読み込むため、ROM の
フォントには依存しません。

## 既知の制限

- `arm64-v8a` のみ。Android 8.0 以上、Vulkan 1.1 対応実機が必要です。
- リアルタイムプレビューは古い GPU では 60 fps ではありません。ベンチマークの
  推奨に従ってオフにできます。
- 出力サイズは 3200、4800、6400、元画像サイズ。元画像サイズは RAW 向けで、
  JPEG は 3200 にフォールバックします。
- Adreno 660 では 42 MP の元画像サイズ出力が Vulkan のストレージバッファー上限を
  超え、デバイスロストになる場合があります。アプリはエンジンを再構築し 3200 に
  戻します。
- 半コマ合成、CLI、MCP は未対応です。上流実装が Apple サービスに依存するため、
  移植ではなく再実装が必要です。
- Android の動画付き写真は、多くの場合フォトピッカーが主画像を JPEG/HEIF として
  公開するため、その静止画部分を読み込んで編集できます。内蔵動画の検出・フレーム
  編集・動画付き写真としての再書き出しにはまだ対応していません。

詳しくは [ROADMAP.md](ROADMAP.md) を参照してください。

## 大きな画像の出力

7968×5320 の float32 RGB は 485 MiB で、Adreno 660 が報告する 512 MiB の
ストレージバッファー上限の約 95% です。パイプラインは複数の全解像度画像を
同時に保持するため、`VK_ERROR_DEVICE_LOST (-4)` になることがあります。
6400×4273 は約 315 MiB で完了します。

恒久的な解決策は、grain と halation の halo を含めたオーバーラップタイル
レンダリングですが、まだ実装していません。

## トラブルシューティング

[Issue](https://github.com/Shawn-SU-1/zhaoye/issues/new/choose) を作成し、
**設定 → ログを書き出す** で生成される診断レポートを添付してください。
機種、Android バージョン、ABI、メモリ、エンジン capabilities、直近のアプリ
イベント、logcat が含まれます。写真のファイル名やパスが含まれる場合があるため、
公開したくない行は削除してください。
