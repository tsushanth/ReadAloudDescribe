# ReadAloud Describe

On-device AI image description for Android. The 90% of Android users who don't have a Pixel 8+ get nothing useful when TalkBack hits an unlabeled image. This app fills that gap — runs entirely on the device, works offline, never sends pixels anywhere.

Sibling product to **ReadAloud Voice** (`com.listenai.voice`, the standalone TTS engine). Reads descriptions back in whichever voice the user picked over there.

---

## Single value prop

> On-device AI image description for Android. Works offline. Nothing leaves your phone. Reads back in your chosen voice.

That's the whole pitch. No tiers, no cloud opt-in, no account.

---

## Stack

| Layer | Tool |
|---|---|
| VLM | Moondream2 (1.86B params, INT8 ONNX, ~1 GB on disk) |
| Inference | ONNX Runtime (same as ReadAloud Voice's Kokoro + Piper) |
| Model delivery | WorkManager download on first run, ~1 GB Wi-Fi-only |
| UI | Single Compose activity, no MainActivity |
| Entry points | Android share-sheet target (`image/*`) + in-app camera capture |
| TTS | Hand off to ReadAloud Voice if installed, otherwise system default |
| Backend | **None** |
| Internet permission | **None required after model download** |

---

## Model: why Moondream2

Considered four candidates before locking the v1 pick:

| Model | INT8 size | RAM | Pixel 6 latency | Pixel 9 latency | Quality vs Gemini Nano | License |
|---|---|---|---|---|---|---|
| **Moondream2 (1.86B)** ← v1 | ~1.0 GB | ~2.5 GB | 5-15 s | 2-6 s | Comparable | Apache 2.0 |
| SmolVLM-500M | ~250 MB | ~800 MB | 1-3 s | < 1 s | Below | Apache 2.0 |
| Florence-2-base | ~150 MB | ~500 MB | 0.5-2 s | < 1 s | Strong on captions, weak on Q&A | MIT |
| Qwen2.5-VL-3B | ~1.5 GB | ~3.5 GB | 8-20 s | 3-8 s | Above | Apache 2.0 |

**Moondream2 picked** because:

- Purpose-built for single-image description (the exact thing we need)
- Quality matches Gemini Nano on documented benchmarks
- vikhyatk publishes INT8 ONNX exports, so the port is "download + integrate" not "export pipeline"
- Apache 2.0 — clean for commercial paid app
- Battle-tested on Apple Silicon + Android community projects

**Fallback if Moondream2 ONNX port breaks during validation**: SmolVLM-500M. Worse quality, but Idefics3 architecture is first-class in `transformers` (no custom modeling code = cleaner ONNX export pipeline).

---

## Architecture (mirrors what ReadAloud Voice shipped)

- Single Compose `DescribeActivity` — no MainActivity. Launcher opens straight into the share/capture UI (same pattern as `VoicePickerLauncher` activity-alias).
- Manifest registers `image/*` as a share-sheet target → user shares an image from Photos / Gmail / Chrome → our activity opens with it.
- Optional in-app camera capture (CameraX) for "describe what I'm pointing at" UX.
- ONNX Runtime via the existing JNI infrastructure — same dependency, same lifecycle pattern.
- WorkManager downloads the ~1 GB model on first run, Wi-Fi-only, foreground-promoted notification (`KokoroModelDownloader` is the template).
- TTS handoff: check if `com.listenai.voice` is installed via `PackageManager.getApplicationInfo`; if yes, use it via `TextToSpeech(context, listener, "com.listenai.voice")`. Otherwise fall through to system default.

---

## Inference pipeline

```
Image (arbitrary size)
  → resize 384×384 (or 768×768 for high-fidelity mode) + normalize
  → vision encoder ONNX session    [~50-300 ms]
  → autoregressive text decoder    [10-150 tokens, 50-200 ms each]
  → assembled description text     [1-15 s total on commodity hardware]
  → speak via chosen TTS
```

Three description modes available:

| Mode | Prompt | Token budget | Use |
|---|---|---|---|
| Short caption | "Describe this image in one sentence." | ~20 | Quick context |
| Detailed | "Describe this image in detail for someone who cannot see it." | ~150 | Default |
| Read aloud text | "What text is visible? Read it exactly." | ~100 | OCR-style for screenshots, signs, menus |

---

## UI

Single-screen Compose activity. Top-down:

1. Image preview (the shared/captured image at top)
2. Description text (large, high-contrast, TalkBack-accessible)
3. Action row: `Speak`, `Describe more`, `Read text`, `Save`, `Share text`
4. Status line below — "Generating… 8 s" or "Done in 6.2 s"

Empty state (app opened directly, no image): `[ Camera ] [ Choose from gallery ]` and a one-line "Or share any image to me from another app."

---

## Engineering effort

| Week | Work | Output |
|---|---|---|
| 1 | Mac validation. Pull Moondream2 INT8 ONNX. Run on a 20-image test corpus (screenshots, photos, scenes, text-heavy). Compare INT8 quality vs FP16 baseline. If INT8 degrades materially, decide: ship FP16 or pick SmolVLM. | Go/no-go on Moondream2 + locked model variant |
| 2 | Android Studio project scaffolding. Manifest with share intent + camera permission. Compose UI shell with placeholder text. WorkManager model downloader (clone Kokoro pattern). | App on Pixel showing static placeholder text after sharing an image |
| 3 | ONNX Runtime inference integration. Image preprocessing (resize, normalize, batch). Vision encoder session. Text decoder session with token loop. Stop conditions (EOS token, max tokens, timeout). | End-to-end: share image → real description appears on screen |
| 4 | TTS handoff (detect `com.listenai.voice`, fall back to system). UI polish (loading state, error state, "describe again"). Play Console listing. Internal Testing release with `warcarr@gmail.com` on the tester list. | Shippable v1 to Play Internal |

**Total focused: ~3 weeks. Realistic calendar at 30-50 % capacity: 5-7 weeks.**

---

## Risks I want to flag now

| Risk | Likelihood | Plan |
|---|---|---|
| Moondream2 INT8 quantization erodes quality below Gemini Nano | Medium | Ship FP16 (2× model size, ~2 GB) if quality drop is unacceptable |
| Token-by-token decode is too slow on older Pixels (15 s+) | Medium | Limit to short-caption mode by default; surface detailed/long modes as opt-in "give me more" tap |
| 1 GB model download deters install | Medium | First-launch flow that says "downloading once, this is the whole AI" before kicking off — and let user opt into a smaller fallback model |
| BVI users are iOS-dominant, Android-only product has a smaller TAM | High but unchangeable for now | Make sure the engine code is portable enough that an iOS port is a 1-month project not a 6-month one |
| Seeing AI being free + Microsoft-funded erodes our differentiation | High | Lean hard on: privacy (offline by design), voice integration with ReadAloud Voice, no Microsoft account requirement, faster on-device for typical use |

---

## Decision log (locked)

| # | Decision | Choice |
|---|---|---|
| 1 | Primary model | Moondream2 |
| 2 | Cloud fallback | **None** — pure on-device |
| 3 | App name | ReadAloud Describe |
| 4 | Application ID | `com.listenai.describe` |
| 5 | Repo location | `~/Documents/GitHub/ReadAloudDescribe/` (this repo) |
| 6 | Pricing | $4.99 one-time, no subscription, no IAP tiers |
| 7 | Distribution | Play Store Internal → Closed → Open → Production, mirroring ReadAloud Voice rollout |

---

## What needs to happen Day 1

**~30-45 min Mac spike in a clean Python venv** to actually run Moondream2 on a test image corpus:

1. Fresh `python3 -m venv` + `pip install transformers==4.45 torch pillow accelerate`
2. Pull `vikhyatk/moondream2` (pinned revision)
3. Run on 10-20 representative images: screenshots, photos, text-heavy, charts, faces
4. Measure: latency, output quality (subjective), output length distribution
5. If quality is acceptable → commit to Moondream2 and start the Android scaffolding
6. If quality is poor → fall to SmolVLM-500M and re-validate

The empirical Mac spike that got blocked tonight by transformers 5.12.1 / pyvips / API drift goes here as the literal first task.
