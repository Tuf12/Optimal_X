# Android 16 KB page size compliance

**Status:** **COMPLIANT** (arm64 ELF audit) — 2026-05-24 after MediaPipe **0.10.32** bump and removal of redundant `org.tensorflow:tensorflow-lite` AAR.

**Last audit:** `app-debug.apk` — all arm64 `.so` files show `Align 0x4000`.

Use with: [VOICE_SYSTEM.md](../systems/VOICE_SYSTEM.md), [SEMANTIC_SEARCH.md](../architecture/SEMANTIC_SEARCH.md), `gradle/libs.versions.toml`, `app/build.gradle.kts`.

---

## Why this matters for OptimalX

Android historically used **4 KB** memory pages. Newer devices (Android 15+, e.g. Pixel 8/9 and other high-RAM phones) can run with **16 KB** pages. Native libraries (`.so` files) compiled for 4 KB alignment **fail to load** on those devices — the app may not install, may crash at startup, or may crash when a feature loads JNI.

OptimalX is **not Kotlin-only**. It ships native code through the **semantic search** stack (MediaPipe / TFLite). Because we **target SDK 36**, Google Play also requires 16 KB support for store uploads.

**Symptom on a 16 KB device:** app won't run even though it works on older 4 KB devices.

---

## Removed (2026-05-24) — no longer in APK

Local STT paths that shipped non-compliant native libs were removed entirely:

| Removed | Why |
|---------|-----|
| `mx.valdora:whisper-android` + all Whisper Kotlin/settings code | Google STT via `SpeechToTextEngine` is the only mic path |
| Sherpa ONNX assets (`assets/voice/sherpa/`) | Never wired in Gradle; deleted |
| Silero VAD ONNX (`assets/voice/silero_vad_16k_op15.onnx`) | Only used by removed local pipeline |

STT is now **Google SpeechRecognizer only** (see `SpeechToTextEngine.kt`).

---

## What “16 KB compatible” means (two checks)

Both must pass for arm64 (and x86_64 emulator):

| Check | What it is | OptimalX today |
|-------|------------|----------------|
| **ELF segment alignment** | Each `.so` LOAD segment `Align` must be ≥ `0x4000` (16 KB) | **PASS** |
| **APK zip alignment** | `.so` files 16 KB zip-aligned in the package | **PASS** |

Zip alignment alone is not enough. Our blocker is **ELF alignment inside the `.so` files** shipped by outdated SDK binaries.

Official reference: [Support 16 KB page sizes](https://developer.android.com/guide/practices/page-sizes)

---

## Current native library audit (arm64-v8a)

After Whisper removal, remaining **non-compliant** libraries:

| Library | Source | ELF Align | 16 KB OK? | App feature |
|---------|--------|-----------|-----------|-------------|
| `libmediapipe_tasks_text_jni.so` | `com.google.mediapipe:tasks-text:0.10.32` | `0x4000` | YES | Semantic search — runs `universal_sentence_encoder.tflite` |
| `liblitertlm_jni.so` | `com.google.ai.edge.litertlm:litertlm-android:0.13.1` | `0x4000` | YES | LiteRT-LM on-device LLM (Gemma 4) |
| `libLiteRt.so` | LiteRT-LM (transitive) | `0x4000` | YES | LiteRT runtime |
| `libLiteRtClGlAccelerator.so` | LiteRT-LM (transitive) | `0x4000` | YES | LiteRT OpenCL GPU delegate |
| `libandroidx.graphics.path.so` | AndroidX Compose | `0x4000` | YES | Compose graphics |
| `libdatastore_shared_counter.so` | AndroidX DataStore | `0x4000` | YES | Preferences |

**Not shipped:** `libtensorflowlite_jni.so` — removed with redundant `org.tensorflow:tensorflow-lite` Gradle dep. MediaPipe bundles the TFLite runtime inside `libmediapipe_tasks_text_jni.so`. The **`.tflite` model asset is unchanged.**

**No native code:** Room, OkHttp, Compose UI, PDFBox, Google STT — no action needed.

---

## Build toolchain (already OK)

| Item | Required | OptimalX |
|------|----------|----------|
| Android Gradle Plugin | ≥ 8.5.1 | **8.10.0** ✓ |
| `compileSdk` / `targetSdk` | — | **36** (in scope for Play requirement) |
| NDK (if we add our own native code) | r28+ with 16 KB ELF default | Not used for app-owned C++ |

AGP is sufficient for **packaging** 16 KB zip alignment. Remaining work is **MediaPipe / TFLite dependency upgrades**.

---

## Remediation plan

Work in order. Re-run the audit script after each change.

### Phase 1 — MediaPipe / semantic search — **DONE**

- [x] Bump `mediapipeTasksText` to **0.10.32**
- [x] Drop redundant `org.tensorflow:tensorflow-lite` AAR (shipped non-compliant `libtensorflowlite_jni.so`; runtime lives inside MediaPipe JNI)
- [x] Re-audit arm64 — all `0x4000`
- [ ] Smoke-test on 16 KB physical device: cold start + `EmbeddingEngine.diagnostics()` + `search_semantic`

### Phase 2 — Play / release hygiene

- [ ] Build release AAB: `./gradlew :app:bundleRelease`
- [ ] Confirm bundle config: `bundletool dump config --bundle=app-release.aab | grep alignment` → expect `PAGE_ALIGNMENT_16K`
- [ ] Upload to Play internal track; confirm Play Console no longer flags 16 KB issues.
- [ ] Test release build on 16 KB device (not just debug).

---

## Verification commands

Run from repo root after any dependency change.

### 1. List native libraries in APK

```bash
APK=app/build/outputs/apk/debug/app-debug.apk
unzip -l "$APK" | grep '\.so$' | awk '{print $4}' | sort
```

### 2. Check ELF LOAD segment alignment (arm64)

Requires `readelf` (binutils). **Pass = all LOAD segments show `0x4000` or higher.**

```bash
APK=app/build/outputs/apk/debug/app-debug.apk
TMP=$(mktemp -d)
unzip -q "$APK" 'lib/arm64-v8a/*.so' -d "$TMP"
for f in "$TMP"/lib/arm64-v8a/*.so; do
  aligns=$(readelf -lW "$f" | awk '/LOAD/ {print $NF}' | sort -u | tr '\n' ' ')
  echo "$(basename "$f"): $aligns"
done
```

**Pass criteria:** no `0x1000` on any arm64 library we ship.

### 3. Zip alignment check

```bash
ZIPALIGN=$(find ~/Android/Sdk/build-tools -name zipalign | sort -V | tail -1)
"$ZIPALIGN" -v -c -P 16 4 app/build/outputs/apk/debug/app-debug.apk
```

Expect: `Verification successful`

### 4. Android Studio

**Build → Analyze APK** → `lib/` folder → **Alignment** column flags non-compliant libs.

---

## Testing on a 16 KB environment

- [ ] **Physical device** with 16 KB pages (developer device — primary acceptance test).
- [ ] **Emulator:** Android 15+ system image with “16 KB page size” enabled (AVD Manager → show advanced settings).
- [ ] Cold start app — must not crash before UI.
- [ ] Open chat, trigger semantic indexing/search.
- [ ] Mic / STT (Google SpeechRecognizer).
- [ ] Watch logcat for `dlopen` / `UnsatisfiedLinkError` / SIGSEGV in any `lib*.so`.

---

## Compliance checklist (summary)

| Step | Done |
|------|------|
| Document current failing libraries | ✅ 2026-05-24 |
| Remove Whisper + ONNX local STT | ✅ 2026-05-24 |
| Upgrade MediaPipe `tasks-text` to 0.10.32 | ✅ 2026-05-24 |
| Remove redundant `tensorflow-lite` AAR | ✅ 2026-05-24 |
| Re-audit arm64 `.so` ELF alignment | ✅ 2026-05-24 |
| Verified on 16 KB physical device | ☐ |
| Release AAB + Play Console clean | ☐ |
| Verified on 16 KB physical device | ☐ |

---

## Key files

| Area | File(s) |
|------|---------|
| Dependency versions | `gradle/libs.versions.toml` |
| Gradle deps | `app/build.gradle.kts` |
| Semantic / MediaPipe | `app/src/main/java/com/example/optimalx/data/semantic/EmbeddingEngine.kt` |
| Google STT | `app/src/main/java/com/example/optimalx/voice/SpeechToTextEngine.kt` |
| Model assets | `app/src/main/assets/models/universal_sentence_encoder.tflite` |

---

## References

- [Support 16 KB page sizes (Android Developers)](https://developer.android.com/guide/practices/page-sizes)
- [Play requirement announcement (May 2025)](https://android-developers.googleblog.com/2025/05/prepare-play-apps-for-devices-with-16kb-page-size.html)
- [MediaPipe 16 KB issue #5728](https://github.com/google-ai-edge/mediapipe/issues/5728)

---

_Update this doc after each dependency bump: refresh the audit table, check off phases, and note the APK/AAB path tested._
