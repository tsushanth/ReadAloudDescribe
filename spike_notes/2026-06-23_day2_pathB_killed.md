# Day-2 follow-up (2026-06-23 evening): Path B killed, pivoting to Path C

## Two findings, both kill Path B with Xenova exports

### 1. Projection layer is baked into `vision_encoder.onnx`

Inspecting the Xenova ONNX graph (`onnx.load(...)`):

```
=== last 5 nodes ===
  MatMulNBits          <- [projection/mlp/act/Mul_5_output_0, ...]
  Add                  <- [projection.mlp.fc2.bias, ...]
```

Output is `image_features` shaped `(1, 729, 2048)` — that's **post-projection** features (2048 dim = decoder hidden, not 768 dim raw SigLIP).

Moondream2's reference multi-crop pipeline fuses **pre-projection** features (the raw SigLIP outputs from each crop) and then runs a single projection over the fused tensor. Xenova's ONNX gives us no way to access the pre-projection tensor — we'd have to re-export from PyTorch ourselves to break the encoder/projector apart.

### 2. Phi-2 decoder context = 2048 tokens

`config.json`: `"max_position_embeddings": 2048`.

That blocks the simplest "more crops = more vision tokens" approach:
- Single crop: 729 vision + 20 text = **749** ✅ fits
- 5 crops concat: 3645 vision + 20 text = **3665** ❌ overflows

So the only Xenova-compatible multi-crop fusion is one that **preserves the (729, 2048) shape** — i.e. some pooling over the crops dim.

### Experiment: mean-fusion across 3 crops on the test screenshot

Script: [`scripts/run_multicrop_onnx.py`](../scripts/run_multicrop_onnx.py)

Setup:
- Ported Moondream2's `overlap_crop_image` to Python (PIL instead of pyvips)
- For 960×2142 input, `max_crops=4` → tiling=(2, 1) → 1 global + 2 local crops
- Each crop encoded separately → stacked → mean-pooled along crops dim → fed to decoder

Result:

> "The image shows a computer screen displaying a list of names and their corresponding descriptions. The names are arranged in a table format... The names include 'John', 'Sara', 'David', and 'Emma'. The descriptions... 'friend', 'friend', 'friend', and 'friend'."

**Worse than single-crop output.** Single-crop at least invented voice-sounding names ("Bella, Chad, Chad" — close to actual voice picker context). Mean fusion across crops averages the projected features into a less specific representation; the decoder hallucinates more aggressively because the input signal is mushier.

This makes sense in retrospect: post-projection features are already in the decoder's input embedding space, where any element-wise pooling is semantically arbitrary. Mean(global, local1, local2) doesn't correspond to "look at all three views" — it corresponds to "look at a blurry average."

## Conclusion: Path B is not recoverable with current Xenova exports

To make Path B work would require:
- Re-exporting Moondream2 from PyTorch with the encoder and projector as separate ONNX graphs (probably possible via Optimum, ~1 week)
- Porting Moondream2's `reconstruct_from_crops` spatial-rearrangement to Python/Kotlin (medium effort)
- Calling encoder N times → reassembling spatial grid → single projection → decoder

That's bigger than what Path C (llama.cpp + GGUF) buys us, and Path C's multi-crop pipeline is already implemented + maintained.

## Pivot: Path C (llama.cpp + GGUF)

**What we're switching to:**
- Model: `ggml-org/moondream2-20250414-GGUF` (existing) — two files:
  - `moondream2-mmproj-f16-20250414.gguf` (vision encoder + projector, F16, ~840 MB)
  - `moondream2-text-model-f16_ct-vicuna.gguf` (text decoder, F16, ~2.6 GB)
- Quantize locally with `llama-quantize` to Q4_K_M → estimated ~1.0-1.3 GB total bundle
- Runtime: llama.cpp via [llama.android](https://github.com/ggerganov/llama.cpp/tree/master/examples/llama.android) (maintained, has CMake wrapper for Android Gradle)
- Multi-crop pipeline: built-in to llama.cpp's LLaVA / Moondream multimodal code (the `llama-mtmd-cli` codepath)

**What we gain:**
- Reference-quality output (multi-crop done right by upstream)
- Active maintenance (llama.cpp is much more battle-tested than ONNX Runtime for VLM inference)
- GGUF Q4_K_M is competitive with Q4 ONNX on size and quality

**What we give up:**
- Familiar ONNX Runtime Android infrastructure (we'd already wired it for Kokoro/Piper)
- ~7s/query INT8 ONNX speed — llama.cpp probably 12-25 s on Pixel 9 for similar quant
- Single-runtime simplicity (we'd ship ONNX + llama.cpp side by side)

## Day-3 plan (new path)

1. Pull `ggml-org/moondream2-20250414-GGUF`, quantize the text model with `llama-quantize` → Q4_K_M
2. Run `llama-mtmd-cli` on Mac CPU against the test corpus, validate output quality matches Day-1 FP32 reference
3. Measure Mac latency to derive an honest Android estimate
4. Integration plan for `llama.android` into our existing Android Studio project

## Decision-log update

PROJECT_PLAN.md decisions #8 (Inference runtime) and #9 (Model source) need to flip:

- ~~#8: ONNX Runtime Android~~ → **llama.cpp Android**
- ~~#9: Xenova/moondream2 ONNX exports~~ → **ggml-org/moondream2-20250414-GGUF, locally requantized**
- ~~#10: Mixed-quant ONNX bundle ~1.07 GB~~ → **GGUF Q4_K_M, ~1.0-1.3 GB**
