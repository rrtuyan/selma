# Selma

An Android app that converts audio and video files using **FFmpegKit Next**
(`com.arthenica:ffmpeg-kit-next:9.0.2`, `full-gpl` build, prebuilt AAR from
[XiaoHao560/ffmpeg-kit-next](https://github.com/XiaoHao560/ffmpeg-kit-next),
upstream [arthenica/ffmpeg-kit-next](https://github.com/arthenica/ffmpeg-kit-next)).

## Features

### Video → Video
- Video codecs: **H.264 (libx264)**, **H.265/HEVC (libx265)**, **H.266/VVC (libvvenc)**,
  **VP8 (libvpx)**, **VP9 (libvpx-vp9)**, **AV1 (libsvtav1)**
- Video bitrate: **"Same as source"** (probed via FFprobe and reused) or 500–12000 kbps presets
- Optional **GPU / MediaCodec hardware acceleration** (H.264 & H.265) — offloads encoding
  from the CPU and avoids software-encoder crashes on some chipsets
- Optional audio track, with per-track settings:
  - Audio codec: AAC, Opus, MP3, Vorbis, FLAC (auto-resolved to a codec the
    chosen container can mux — e.g. Opus/Vorbis only for WebM)
  - Audio bitrate: "Same as source" or 32–320 kbps; sample rate displayed in **kHz**
    (Original / 8–96 kHz); channels (Original / Mono / Stereo)
  - Loudness normalization (EBU R128 `loudnorm` filter)
- Audio can be dropped entirely (`-an`)

### Audio → Audio
- Codecs: **AAC-LC (.aac)**, **M4A (AAC)**, **Opus**, **MP3 (libmp3lame)**,
  **Ogg Vorbis (libvorbis)**, **FLAC** (lossless), **WAV/PCM 16-bit**
- Bitrate: "Same as source" or 32–320 kbps (ignored for lossless codecs); sample rate
  in kHz; channels; loudness normalization

### General
- **Playback preview** of the picked input file (VideoView + MediaController, works for
  audio too), and **Play** button to preview the converted output
- System file picker (Storage Access Framework — no storage permission required)
- Real-time progress bar (time-based, via FFmpeg statistics + FFprobe duration)
- Cancel in-flight conversions (cancel is race-safe: controls stay locked until the
  FFmpeg session actually terminates)
- **All controls (tabs, dropdowns, switches, file picker) are locked while a conversion
  runs** to prevent mid-encode state changes that crash the app
- Output saved to app external storage, indexed via `MediaScanner`
- Share the result or save it to a user-chosen location (`FileProvider` + SAF)
- Material 3 Expressive UI (dynamic color on Android 12+, expressive corner shapes)

## Known issues & mitigations (documented on MT6781 / POCO M4 Pro 4G)
- **H.265/HEVC and H.266/VVC software encoding can crash the app.** These codecs are
  flagged in-app: selecting them shows a warning, and starting a conversion with them
  (without GPU acceleration) shows a confirmation dialog. Enabling **GPU acceleration**
  uses `hevc_mediacodec` for H.265 and avoids the software `libx265` path. H.266 has no
  hardware encoder, so it remains software-only — use with caution.
- Cancelling and immediately picking another file no longer leaves the old encode running
  (file picker is disabled mid-conversion; cancel waits for the session to terminate).
- Tabs and all inputs are disabled mid-conversion, eliminating the crash caused by
  changing settings while FFmpeg is running.
- `android:largeHeap="true"` is enabled to reduce OOM risk during heavy encodes.

## Building

The prebuilt FFmpegKit Next Maven repo is vendored under `libs/`. A local
`maven { url = uri("${rootDir}/libs") }` repository is declared in
`settings.gradle`, so no network access to a Maven repo for FFmpegKit is needed.

```bash
export JAVA_HOME=<jdk-17>
export ANDROID_HOME=<android-sdk>
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17, Android SDK platform 34, build-tools 34.0.0.
`minSdk 24`, `targetSdk 34`. Native libraries shipped for `arm64-v8a` and
`armeabi-v7a` only (matching the prebuilt AAR).

## Project layout

```
app/src/main/java/com/example/mediaconverter/
├── CodecPresets.kt   # codec enums, bitrate/sample-rate/channel option lists
├── FFmpegEngine.kt   # FFmpeg argument builders + cancellable async execution
└── MainActivity.kt   # UI (tabs, dropdowns, progress, share/save)
libs/                 # vendored com.arthenica:ffmpeg-kit-next:9.0.2 AAR
```

## License note

The bundled `ffmpeg-kit-next` AAR is a **GPL v3.0** (`full-gpl`) build.
Distributing an app that links it triggers GPL obligations (source disclosure,
etc.). If your app is not GPL-compatible, rebuild an LGPL variant yourself —
see upstream `ffmpeg-kit-next` build scripts.
