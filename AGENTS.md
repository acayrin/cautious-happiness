# AGENTS.md — working on Overlay Translator

## Build / test (from repo root)

```bash
export JAVA_HOME=<jdk17>
export ANDROID_HOME=<android-sdk>       # ANDROID_SDK_ROOT must equal ANDROID_HOME
export PATH=$JAVA_HOME/bin:$PATH
./gradlew :app:assembleRelease :app:testDebugUnitTest --console=plain
adb install -r app/build/outputs/apk/release/app-release.apk
```

Native build (only when C++ changes): `bash app/src/main/cpp/build-deps.sh`
(fetchs CTranslate2 v4.8.2 + sentencepiece v0.2.1 + whisper.cpp, builds arm64
statics into `deps/build-ct2` / `deps/build-spm` / `deps/build-whisper`, linked by
`app/src/main/cpp/CMakeLists.txt`).

## Code conventions

- Kotlin, no new dependencies unless unavoidable (stdlib/Android first).
- Translation backends implement `TextTranslator` (`translateAll(texts): List<String?>`).
- OCR engines implement `OcrEngine`. Keep pure logic JVM-testable (primitive overloads;
  Android `Rect`/`Bitmap` are stubs under unit tests and construct zero-init).
- Unit tests live in `app/src/test/kotlin`; every non-trivial pure function gets one.
- Settings: add a field + key in `OverlayConfig`, load/save in `MainActivity` AND
  `CaptureService` (they read prefs independently), UI in `activity_main.xml`/strings.

## Hard-won gotchas (do not regress)

- **Capture**: event-driven `ImageReader.OnImageAvailableListener`; close every unused
  frame immediately. Never poll `acquireLatestImage()` on a timer — the VDS producer
  wedges. Only ONE `MediaProjection#createVirtualDisplay` per instance; adapt rotation
  via `setSurface`+`resize`. The WM screen mirror dies permanently when the app's own
  overlay windows are visible to it — all overlay windows must carry `FLAG_SECURE`.
- **Overlay**: single full-screen window drawn in one pass; hysteresis matches old boxes
  by text+overlap; only inherit previous text when the source text is identical.
- **Pipeline**: frames processed in order; apply every result. A block whose text is
  still growing (typewriter) is held back (`maxDefer`). Unknown translation = render
  nothing (never raw OCR text).
- **Cache**: keyed by source-text hash (post-glossary); optional normalization folds
  punctuation/whitespace/width. Never cache failed translations.
- **Remote**: Google2 endpoint `translate-pa.googleapis.com/v1/translateHtml`, needs the
  public `X-Goog-API-Key` and JSON+protobuf body `[[["text"],from,to],"wt_lib"]`.
  Serialize + pace requests; cooldown after failures.
- **OPUS-MT**: CTranslate2 int8 models need `decoder_start_token: <pad>` and
  `add_source_eos: true` in `config.json`, else the model degenerates into repetition.
- **Bergamot**: removed on purpose (slow/unreliable on-device); leftover files are
  git-ignored — do not resurrect without a strong reason.
- **Model hosting**: binaries live on the **`models` branch**, not `main` — `main` is
  app source only (its history was rewritten to drop the 1.3 GB of models; do not
  re-commit `models/`, it is git-ignored). Default base URL:
  `https://raw.githubusercontent.com/acayrin/cautious-happiness/models`, files fetched as
  `<base>/opus-mt-<lang>-en/*` and `<base>/whisper/*` (ggml-tiny-q5_1 = fast default,
  ggml-base-q5_1 = accurate). The pre-split URL `.../main/models` is dead;
  `OverlayConfig.modelUrl()` maps it back to the default so old installs keep working.
  `Helsinki-NLP/opus-mt-pt-en` doesn't exist — the pt folder is the converted
  `opus-mt-ROMANCE-en` (covers pt; don't "fix" the naming).
- **Audio subtitles**: AudioPlaybackCapture rides on the SAME MediaProjection consent
  (no RECORD_AUDIO, no extra VirtualDisplay). Apps with `allowAudioCapture=false` or
  FLAG_SECURE are silent. whisper.cpp runs greedy/2 threads (leave CPU for OPUS-MT);
  `SubtitleChunker` is the pure tumbling-window + RMS VAD gate (unit-tested). Audio
  translation uses its own `TranslationChain` instance — the JNI `nativeTranslate`
  has no locking.

## File layout

- `service/CaptureService.kt` — capture, gate, coalescing, status; `service/OverlayService.kt` — window
- `ocr/OcrProcessor.kt` — ML Kit wrapper, grouping, filters
- `translate/TranslatePipeline.kt` — orchestration; `Translator.kt` — chain + ML Kit; `RemoteTranslator.kt` — Google2; `OpusMtTranslator.kt` — JNI + model download
- `overlay/TranslationView.kt` — painter; `overlay/OverlayConfig.kt` — settings model
- `cache/TranslationCache.kt`, `cache/TranslationKeys.kt` — translation memory
- `audio/` — `AudioSubtitleEngine.kt` (capture→chunk→transcribe→translate), `WhisperTranscriber.kt` (JNI + model download), `SubtitleChunker.kt` (pure)
- `app/src/main/cpp/` — JNI shims (`opusmt_jni.cpp`, `whisper_jni.cpp`) + CMake; `models/` — converted OPUS-MT models + whisper ggml models
