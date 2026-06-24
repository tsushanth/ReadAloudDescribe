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
| **Moondream2 (1.86B)** ← v1 | ~1.0 GB | ~2.5 GB | 30-60 s* | 10-20 s* | Comparable | Apache 2.0 |
| SmolVLM-500M | ~250 MB | ~800 MB | 1-3 s | < 1 s | **Far below — empirically ruled out** | Apache 2.0 |
| Florence-2-base | ~150 MB | ~500 MB | 0.5-2 s | < 1 s | Strong on captions, weak on Q&A | MIT |
| Qwen2.5-VL-3B | ~1.5 GB | ~3.5 GB | 60-120 s* | 20-40 s* | Above | Apache 2.0 |

\* latency revised on 2026-06-23 after Mac CPU FP32 baseline showed ~70 s/query for Moondream2 2B. INT8 quant + XNNPACK gives 2.5–4× speedup vs Mac CPU FP32, hence the new envelopes.

**Moondream2 picked** because:

- Purpose-built for single-image description (the exact thing we need)
- Quality matches Gemini Nano on documented benchmarks
- vikhyatk publishes INT8 ONNX exports, so the port is "download + integrate" not "export pipeline"
- Apache 2.0 — clean for commercial paid app
- Battle-tested on Apple Silicon + Android community projects

**Fallback if Moondream2 ONNX port breaks during validation**: ~~SmolVLM-500M~~ — **ruled out empirically on 2026-06-23**. SmolVLM-500M produces TalkBack-level generic captions ("Screen displaying multiple options in a voice application") on screenshots that Moondream2 transcribes near-verbatim. The 17× latency advantage doesn't matter if the output adds zero information. New fallback: **Moondream 0.5B variant** (smaller quant of the same family) — quality should degrade gracefully, not architecturally.

### Day-1 Mac validation findings (2026-06-23)

Test image: 960×2142 screenshot of the ReadAloud Voice picker UI. Mac M-series CPU, FP32, full 2B model via HF transformers (workaround for MPS bug: monkey-patch `torch.backends.mps.is_available` to False before model import — Moondream2's `vision.py` force-routes outputs to MPS otherwise).

| Mode | Moondream2 2B FP32 latency | Quality |
|---|---|---|
| Short caption | 62.5 s | App name correct, some hallucination of UI labels |
| Detailed | 75.8 s | Solid — gets layout + options right, hallucinates the specific voice names |
| Read text (OCR-style) | 66.7 s | **Strong** — near-verbatim transcription of visible UI text |

**Implication for Android latency estimates** (revised downward from initial scoping):

| Device | Original estimate | Revised estimate (INT8 + ONNX Runtime + XNNPACK, derived from Mac CPU baseline / 2.5–4×) |
|---|---|---|
| Pixel 9 (Tensor G4) | 2–6 s | **10–20 s** |
| Pixel 6 (Tensor G1) | 5–15 s | **30–60 s** |
| Mid-range Snapdragon | (not stated) | **45–90 s** |

This is a UX problem — the difference between "fast enough to feel instant" and "user has to wait." Mitigation has to be in the app UI: surface short-caption first (~5 s), stream detailed/read-text in background, with clear progress indicators.

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

**Day-1 discovery (2026-06-23)**: `Xenova/moondream2` on HuggingFace publishes the full ONNX export at every quant tier (FP16, INT8, Q4, Q4F16, BNB4). **This eliminates the riskiest piece of week-1 work** — we don't have to build the export pipeline ourselves. We just download Xenova's bundle.

| Week | Work | Output |
|---|---|---|
| 1 | ~~Build the ONNX export pipeline~~ — eliminated by Xenova's prebuilt bundle. Instead: pull Xenova's INT8 + Q4F16 bundles, run inference via `onnxruntime` Python (same runtime as Android), validate quality holds vs the FP32 baseline on a 10-image corpus. Lock the variant (mixed-quant bundle ~1.07 GB or pure INT8 ~1.78 GB). | Go/no-go on the quantized bundle + final variant locked |
| 2 | Android Studio project scaffolding. Manifest with share intent + camera permission. Compose UI shell with placeholder text. WorkManager downloader pulling the three ONNX files from HuggingFace at first launch. | App on Pixel showing static placeholder text after sharing an image |
| 3 | ONNX Runtime Android inference integration. Image preprocessing (resize, normalize, batch). Three sessions (vision encoder + embed tokens + decoder). Token loop with KV cache. Stop conditions (EOS, max tokens, timeout). | End-to-end: share image → real description appears on screen |
| 4 | TTS handoff (detect `com.listenai.voice`, fall back to system). UI polish (loading state with progress, error state, "describe again"). Play Console listing. Internal Testing release with `warcarr@gmail.com` on the tester list. | Shippable v1 to Play Internal |

**Total focused: ~3 weeks (down from 4 thanks to the Xenova discovery). Realistic calendar at 30-50 % capacity: 4-6 weeks.**

---

## Risks I want to flag now

| Risk | Likelihood | Plan |
|---|---|---|
| **Confident hallucination on document text** (passport, receipts, IDs) — observed on Day-1 corpus, Moondream2 invented specific DOB/sex/place-of-birth on a real passport | **High and confirmed** | **Do NOT market as document/text reader.** Frame the app as "describes the scene in your image", route text-only images through ML Kit OCR (already in our stack), present all VLM output with epistemic hedging ("looks like…", "I see…") |
| Repetition-loop generation on complex layouts (diagram corpus image) | Medium, mitigatable | Set `repetition_penalty=1.1`, `no_repeat_ngram_size=3`, hard `max_new_tokens=200` in the decoder generation config. Standard ONNX Runtime session inputs. |
| Moondream2 INT8 quantization erodes quality below the FP32 baseline | Medium | Day-2 corpus re-run on Xenova's INT8/Q4F16 bundle confirms or rejects. Fall back to FP16 (~3.5 GB) if needed. |
| Token-by-token decode is too slow on older Pixels (~30-60 s) | **High, confirmed by Mac CPU baseline** | Limit default to short-caption mode (~50 tokens, ~15-20 s on Pixel 9). Surface detailed/long modes as "give me more" tap. Show progress indicator throughout. |
| ~1.1 GB model download deters install | Medium | First-launch flow that says "downloading once, this is the whole AI" before kicking off — Wi-Fi-only, WorkManager foreground notification |
| BVI users are iOS-dominant, Android-only product has a smaller TAM | High but unchangeable for now | Make sure the engine code is portable enough that an iOS port is a 1-month project not a 6-month one |
| Seeing AI being free + Microsoft-funded erodes our differentiation | High | Lean hard on: privacy (offline by design), voice integration with ReadAloud Voice, no Microsoft account requirement, faster on-device for typical use |

---

## Decision log (locked)

| # | Decision | Choice |
|---|---|---|
| 1 | Primary model | Moondream2 (vikhyatk/moondream2 revision 2025-04-14) |
| 2 | Cloud fallback | **None** — pure on-device |
| 3 | App name | ReadAloud Describe |
| 4 | Application ID | `com.listenai.describe` |
| 5 | Repo location | `~/Documents/GitHub/ReadAloudDescribe/` (this repo) |
| 6 | Pricing | $4.99 one-time, no subscription, no IAP tiers |
| 7 | Distribution | Play Store Internal → Closed → Open → Production, mirroring ReadAloud Voice rollout |
| 8 | ~~Inference runtime~~ | ~~ONNX Runtime Android~~ → **llama.cpp Android** (Path-B-killed pivot 2026-06-23 evening) |
| 9 | ~~Model source~~ | ~~`Xenova/moondream2` ONNX exports~~ → **`ggml-org/moondream2-20250414-GGUF`** (multi-crop baked into the upstream LLaVA pipeline; Xenova exports drop quality because their encoder is single-crop only) |
| 10 | ~~Quant bundle (v1)~~ | ~~ONNX mixed-quant 1.07 GB~~ → **GGUF Q4_K_M ~1.0-1.3 GB** (locally requantized from upstream F16) |
| 11 | Fallback if Moondream2 path collapses | Older `vikhyatk/moondream1` (same family, smaller) — **not** SmolVLM-500M (empirically ruled out 2026-06-23 for being TalkBack-level generic) |

### Why we pivoted from ONNX (2026-06-23 evening)

The Xenova ONNX export has two structural problems that together kill Path B (multi-crop in Kotlin against Xenova's vision encoder):

1. **Projection layer is baked into `vision_encoder.onnx`** — output is post-projection (2048-dim decoder embeddings). Moondream2's reference fuses **pre-projection** features (raw SigLIP 768-dim) before a single shared projection pass. Xenova gives us no API to access pre-projection outputs.
2. **Phi-2 decoder context is 2048 tokens.** 5 crops × 729 vision tokens = 3645, overflows the context. So concat-style multi-crop is impossible; only shape-preserving fusion (mean/pool) is viable.

**Experiment confirmed mean fusion is strictly worse than single-crop** ("John, Sara, David, Emma — friend, friend, friend, friend" instead of the actual UI text). Averaging post-projection features mushes signal instead of adding detail.

llama.cpp + GGUF (Path C) is the way forward. Multi-crop is implemented natively in `llama-mtmd-cli` and the model files exist (`ggml-org/moondream2-20250414-GGUF`). Cost: ~1-2 weeks to wire `llama.android` into the project; slower than ONNX (~12-25 s/query Pixel 9 estimate vs ONNX 3-5 s); larger bundle (1.0-1.3 GB Q4_K_M vs 1.07 GB ONNX). Benefit: reference quality output.

See [`spike_notes/2026-06-23_day2_pathB_killed.md`](./spike_notes/2026-06-23_day2_pathB_killed.md) for the full investigation.

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
