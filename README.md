# Overlay Translator

Live screen translation overlay for Android games: captures the screen, runs OCR,
translates, and paints the English over the original text. Built for Chinese/Japanese
mobile games (tested on *Witch Weapon* / 魔女兵器), works for Korean, French, Spanish
and Vietnamese sources too.

## Features

- **Screen capture** via MediaProjection, event-driven, rotation- and cutout-aware
- **OCR**: ML Kit on-device text recognition (Chinese / Japanese / Korean / Latin models)
  - CJK-script filter (English UI noise is skipped), confidence threshold,
    line-grouping gap (name plates vs dialog), ghost persistence for stable boxes
- **Translation chain**, selectable in settings:
  - **OPUS-MT (offline)** — Helsinki-NLP Marian via CTranslate2 int8, models
    downloaded on demand from the configured model URL
  - **Google2** — keyless Google translate-pa web API (paced, rate-limit safe)
  - **On-device ML Kit** — fallback
  - **Auto** — OPUS-MT → Google2 → ML Kit
- **Single-window overlay** drawn in one pass (no per-box windows); text outlines for
  legibility on any background; optional overflow layout; auto-shrink with a 12 sp floor
- **Translation memory**: SQLite cache keyed by source text, optional normalized keys
  (ignores punctuation/whitespace/width variants); cache size shown in settings
- **Glossary** (Chinese → English terms applied before translation)
- **Status chip** in the overlay: OCR/translation timings, cache hits, skips
- Touch passthrough (Android 12+ needs *Allow untrusted touches* in Developer options)

## Requirements

- Android 11+ (minSdk 30), arm64-v8a
- Overlay permission + notification permission
- For optional offline OPUS-MT: internet once to download the model (~65 MB per language)

## Build

```bash
export JAVA_HOME=<jdk17>
export ANDROID_HOME=<sdk>          # ANDROID_SDK_ROOT must match
export PATH=$JAVA_HOME/bin:$PATH
cd app/src/main/cpp
./build-deps.sh                    # CTranslate2 + sentencepiece static libs (arm64)
./assets/...                       # (no assets needed)
./gradlew :app:assembleRelease     # APK at app/build/outputs/apk/release/
./gradlew :app:testDebugUnitTest   # JVM unit tests
```

Native dependencies (NDK 27.2, CMake 3.22) are fetched into `app/src/main/cpp/deps/`
by `build-deps.sh`; the vendored sources and prebuilt archives are git-ignored.

## Models

Pre-converted OPUS-MT int8 models live in [`models/`](models) (one folder per pair:
`opus-mt-<src>-en/` with `model.bin`, `config.json`, `shared_vocabulary.json`,
`source.spm`, `target.spm`). The app downloads them from the **Model base URL**
setting (default: this repo's raw URL) into app storage on first use.

Conversion recipe (from the Helsinki-NLP transformers checkpoints):

```bash
pip install ctranslate2 transformers sentencepiece torch
ct2-transformers-converter --model opus-mt-<src>-en --output_dir out \
  --quantization int8 --copy_files source.spm target.spm
# Then patch out/config.json:
#   "decoder_start_token": "<pad>"   (converter defaults to </s> → garbled output)
#   "add_source_eos": true           (Marian expects the source EOS token)
```

## Architecture (app/src/main/kotlin/com/overlaytranslator)

- `service/CaptureService.kt` — MediaProjection capture loop, frame gate, pipeline
  coalescing, status chip updates, foreground notification
- `ocr/` — ML Kit recognizer + line grouping (`Ocr.groupLines`), block filtering
- `translate/` — `TranslatePipeline` (OCR → glossary → cache → batch translate),
  `TranslationChain` (backend routing), `RemoteTranslator` (Google2),
  `OpusMtTranslator` (CTranslate2 JNI), `Translator` (ML Kit)
- `overlay/` — `TranslationView` (single-window painter), `OverlayConfig`
- `service/OverlayService.kt` — the one overlay window + WM "poke" window
- `cache/` — SQLite translation memory

## Known limitations

- Landscape + portrait both work, but the capture surface is created for the current
  orientation at start; rotating mid-session re-adapts the display
- Comic/vertical Japanese text is not supported (ML Kit limitation)
- The keyless Google2 endpoint can rate-limit; requests are serialized and paced
