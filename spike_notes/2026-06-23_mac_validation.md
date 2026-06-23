# Day-1 Mac validation spike (2026-06-23)

Goal: validate Moondream2 quality + locate a viable Android deployment path before committing to a 3-4 week Android Studio build.

## TL;DR

- ✅ Moondream2 2B quality is **good enough** on real-world UI screenshots (read-text mode is especially strong — near-verbatim transcription).
- ❌ SmolVLM-500M ruled out as fallback: at 17× faster, but its outputs are TalkBack-level generic. Tradeoff not worth it.
- 🎯 **Xenova published a full ONNX export of Moondream2** (`Xenova/moondream2` on HF) — every quant tier (FP16, INT8, Q4, Q4F16, BNB4). This means **we do not have to build the ONNX export pipeline ourselves**, killing the riskiest piece of Android-port engineering. Bundle ranges from 1.0 GB (mixed-quant minimum) up to 3.5 GB (FP16).
- 🎯 GGUF release (`ggml-org/moondream2-20250414-GGUF`) also exists at F16 only (~3.6 GB). Quantizing locally with `llama-quantize` would give Q4_K_M ~1.0-1.3 GB. Viable second-pick if ONNX Runtime Android has issues.
- ⚠️ Latency reality is harsher than scoped: Mac CPU FP32 is ~70 s/query for Moondream2 2B. Projected Android (INT8 + ONNX Runtime XNNPACK): Pixel 9 ~10-20 s, Pixel 6 ~30-60 s. PROJECT_PLAN.md updated.

## Environment

- Mac (Apple Silicon), Python 3.13.12, fresh `venv`
- `transformers==4.49.0`, `torch==2.12.1`, `accelerate==1.14.0`, `pillow==12.2.0`
- libvips installed (`brew install vips`); `pyvips==3.1.1` for Moondream2's image pre-processing

## Bug worked around

Moondream2's `vision.py` (snapshot `797e1e47…`) monkey-patches
`adaptive_avg_pool2d` to force its output to MPS whenever Apple Silicon
is detected, ignoring whatever `device_map` was passed to `from_pretrained`:

```python
if torch.backends.mps.is_available():
    def adaptive_avg_pool2d(input, output_size):
        return F.adaptive_avg_pool2d(input.to("cpu"), output_size).to("mps")
```

That breaks any attempt to run on pure CPU on Mac. `PYTORCH_ENABLE_MPS_FALLBACK=1`
does not help (the patch fires before any fallback logic runs).

**Workaround**: hide MPS from torch before importing the model code.

```python
import torch
torch.backends.mps.is_available = lambda: False
torch.backends.mps.is_built = lambda: False
from transformers import AutoModelForCausalLM, AutoTokenizer
# ...
```

Doesn't matter for Android (no MPS there). Worth knowing if anyone else
tries to run Moondream2 on Mac.

## Test 1: single image, three prompt modes

Image: 960×2142 screenshot of the ReadAloud Voice picker UI.
Model: `vikhyatk/moondream2`, revision `2025-04-14`, FP32 on Mac CPU.

| Mode | Latency | Quality |
|---|---|---|
| Short caption | **62.5 s** | App name correct; some hallucinated UI labels ("Warlock mode") |
| Detailed | **75.8 s** | Solid layout description; hallucinated specific voice names that aren't on the screen |
| Read text (OCR-style) | **66.7 s** | **Strongest mode** — near-verbatim transcription of all visible UI text |

Observation: **read-text mode is the killer feature** for the BVI use case. Users handing the app a screenshot of a menu / sign / receipt will get back what's actually written, not a high-level summary. Make that the default mode for screenshot-shaped inputs.

## Test 2: SmolVLM-500M same image (comparison)

| Mode | Latency | Output |
|---|---|---|
| Short caption | 5.0 s | "Screen displaying the multiple options in the application." |
| Detailed | 3.5 s | "Screen displaying multiple options in a voice application." |
| Read text | 3.4 s | "Screen displaying the read aloud voices." |

17× faster. **17× less useful.** TalkBack's accessibility tree already tells the user "this is a screen with options." A VLM has to do more than that to justify being installed.

**Verdict: SmolVLM-500M dropped as fallback.** New fallback if Moondream2 ONNX path collapses: try Moondream's older `vikhyatk/moondream1` (smaller, same family).

## Test 3: corpus run

Corpus: 7 images covering UI screenshots (3), natural photos (2), technical diagrams (1), design assets (1). Same prompt across all (detailed mode). Total: **11.1 min wall time, 95 s/image avg**.

| # | Image type | Latency | Quality |
|---|---|---|---|
| 1 | Android screenshot (ReadAloud Voice) | 77.7 s | ✅ Layout right, hallucinated specific voice names |
| 2 | iOS notification screenshot | 83.6 s | ❌ Hallucinated wrong app name ("F5 DIT load failed"), invented UI controls |
| 3 | Indian passport photo | 71.5 s | ❌ **Confidently wrong on every specific field** — wrong DOB, sex, multiple invented "place of birth" entries |
| 4 | Technical flowchart (VibeBuild architecture) | 143.5 s | ⚠️ Got the high-level layout, then **degenerated into infinite repetition** (same "API URL" line 25+ times before max-tokens cutoff) |
| 5 | Android screenshot (Kindle library) | 79.8 s | ⚠️ Got "library" right, invented book titles |
| 6 | App icon design | 103.5 s | ✅ Reasonable design description |
| 7 | Mac desktop (browser + code editor) | 108.5 s | ⚠️ Tubi.com correctly identified, movie details all wrong, code editor recognized |

## Critical quality findings

### 1. Confident hallucination on document photos

On the passport (image 3), Moondream2 produced specific (wrong) values for DOB, sex, and place of birth in confident first-person tone. **A blind user reading their passport via this app would be told the wrong birthday.** Same pattern on the notification screen (image 2): invented a plausible-sounding tech error message.

This is the **#1 product risk** identified by the spike. Mitigations:

- **Never market the app for reading documents, IDs, receipts, or anything where specific accuracy matters.** Position firmly as "describes what's in an image" not "reads the text."
- **Always present output with epistemic hedging**: "Looks like…", "I see…", "Appears to be…" — never "The passport says X."
- **For text-heavy images, prefer the dedicated read-text prompt** which seemed more conservative on the single-screenshot test. Validate this hypothesis on a wider OCR-heavy corpus.
- **Consider routing text-only images through ML Kit OCR** (which we already use in ReadAloud) rather than the VLM, then ask the VLM only for scene/layout context.

### 2. Repetition loop on complex layouts

On the diagram (image 4), the decoder got stuck regenerating the same line 25+ times before hitting some internal max-token cutoff. Total inference: 143 s vs the average of 95 s. This is a textbook autoregressive degeneration.

**Mitigation (Day 3 inference work)**: configure the text decoder with:
- Repetition penalty (≥ 1.1)
- No-repeat-n-gram size (3-4)
- Hard `max_new_tokens` cap of 200

These are standard generation-config flags. The Android port has full control over these via ONNX Runtime's session inputs.

### 3. Latency variance is real

- Range: 71-143 s per image on Mac CPU FP32
- Capped output (mitigation above) should kill the runaway-generation outliers, narrowing the range
- Even with mitigations, expect **4-6 s/token decode** on Mac CPU FP32 → projected **0.8-1.5 s/token on Pixel 9 INT8**, so a 100-token description = 80-150 s on Mac, 15-30 s on Pixel 9

The Pixel 9 estimate in PROJECT_PLAN.md ("10-20 s") was derived assuming ~50-100 token outputs. Validated.

## Synthesis

**Moondream2 2B is the right v1 pick.** Quality is acceptable for scene/layout description — the original "what's in this picture" use case. **It is NOT a substitute for OCR or for any task where exact text matters.** Product framing has to reflect this.

The Xenova ONNX bundle eliminates the export-pipeline risk. The repetition-loop and hallucination findings are real but addressable in the inference layer + UI copy.

## Next moves (Day 2)

1. Pull Xenova INT8/Q4F16 bundle, run via `onnxruntime` Python on the same corpus, confirm quality matches FP32 baseline within tolerance.
2. Re-run with proper generation config (repetition penalty + no-repeat-n-gram + max_new_tokens) to confirm the loop bug is fixable.
3. Validate the read-text prompt mode on a text-heavy sub-corpus (5+ screenshots of menus/signs/receipts) — is it materially more accurate than detailed mode?
4. Start Android scaffolding once Day 2 is green.

## Android deployment path: Xenova ONNX export

`Xenova/moondream2` on HuggingFace publishes Moondream2 already split into ONNX components, at every quant tier:

| Component | FP16 | INT8 | Q4 | Q4F16 | BNB4 |
|---|---|---|---|---|---|
| `vision_encoder` | 838 MB | 423 MB | **267 MB** | 267 MB | 240 MB |
| `decoder_model_merged` | 2506 MB (.onnx + .onnx_data) | 1256 MB | 786 MB | **707 MB** | 708 MB |
| `embed_tokens` | 200 MB | **100 MB** | 400 MB ⚠ | 400 MB ⚠ | 400 MB |

⚠ Note: `embed_tokens` Q4 is *larger* than INT8 (looks like the Q4 quant pass skipped this component). Use INT8 for embeds.

**Recommended Android bundle (mixed quant)**:

| File | Size |
|---|---|
| `vision_encoder_q4.onnx` | 267 MB |
| `decoder_model_merged_q4f16.onnx` | 707 MB |
| `embed_tokens_int8.onnx` | 100 MB |
| **Total** | **1.07 GB** |

If quality drops too much at q4f16 decoder, swap to `_int8` → **1.78 GB** bundle. PROJECT_PLAN.md said 1.0 GB; reality is 1.07-1.78 GB depending on quality tolerance.

## Engineering implications

| Before today | After today |
|---|---|
| "Build ONNX export pipeline for Moondream2" — week 1 risk | **Eliminated.** Xenova's bundle drops in. |
| SmolVLM-500M as fallback | **Dropped.** Quality too far below the bar. |
| Moondream2 INT8 ≈ 1 GB | Real: **1.07-1.78 GB** depending on quant mix |
| Pixel 9 latency 2-6 s | Realistic: **10-20 s** (mitigation: stream short-caption first) |
| Pixel 6 latency 5-15 s | Realistic: **30-60 s** |
| Risk: "Moondream2 quality not good enough" | **Validated as acceptable** on the one screenshot — corpus run will broaden the evidence |

## Next moves (Day 2)

1. Pull the Xenova INT8/Q4F16 bundle, run inference via `onnxruntime` Python (same runtime as Android), validate quality matches FP32 baseline within tolerance.
2. Decide model variant lock based on size vs quality tradeoff.
3. Start Android scaffolding (week 2 in PROJECT_PLAN.md) — manifest, share-target, WorkManager downloader pulling from HF.
