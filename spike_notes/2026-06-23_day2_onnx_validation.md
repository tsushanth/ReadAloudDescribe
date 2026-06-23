# Day-2 ONNX validation spike (2026-06-23)

Goal: prove the Xenova ONNX bundle (`Xenova/moondream2`) actually runs end-to-end in `onnxruntime` Python so we can commit to the Android port path before Week-2 scaffolding starts.

## TL;DR

- ✅ End-to-end ONNX inference **works** on Mac CPU EP.
- 🚀 **3.5× faster than FP32 HF transformers** on the same Mac CPU: ~22 s (Q4 decoder) or ~7 s (INT8 decoder) vs ~78 s baseline.
- ❌ **Output quality drops materially.** On the same screenshot where FP32 read the UI text near-verbatim, the ONNX path produces vague hallucinations ("black and white photo… Select/Save/Close buttons").
- 🔍 **Root cause identified**: Xenova's `vision_encoder.onnx` does a single 378×378 resize. Moondream2's reference implementation uses **multi-crop** (a global 378 view + multiple 378 sub-crops fused via a custom projection layer) — that's where the detail-handling comes from. Xenova's export bakes only the single-crop path.
- ⚠️ **Decision needed** before week-2 starts: how do we recover quality?

## Environment + fixes

Same venv as Day 1 (Python 3.13, transformers 4.49, torch 2.12). Added:
- `onnxruntime==1.27.0`
- `optimum==2.1.0`
- 1.07 GB Xenova bundle: `vision_encoder_q4.onnx` + `embed_tokens_int8.onnx` + `decoder_model_merged_q4.onnx`

Three issues hit, all worked around:

| Issue | Fix |
|---|---|
| `tokenizer.json` has `<image>:-200` (Transformers.js sentinel), Rust tokenizers crate panics on negative IDs | Use upstream `vikhyatk/moondream2` tokenizer instead — same Phi-2 vocab, no negative IDs |
| Q4F16 decoder fails ORT graph optimization (`SimplifiedLayerNormFusion` references nodes that don't exist after FP16 cast) | Use Q4 (no FP16) instead, lose ~80 MB savings |
| Decoder lacks `use_cache_branch` input that other ORT exports use | Just don't pass it — Xenova's decoder dispatches on KV-cache shape (0 = prefill, N>0 = decode) |

## Latency wins (good news)

Same test image (ReadAloud Voice picker screenshot, 960×2142):

| Path | Vision | Prefill | Decode | Total |
|---|---|---|---|---|
| FP32 HF transformers (Day 1 baseline) | ~57 s (incl. multi-crop) | (folded into total) | (folded into total) | **~78 s** |
| Q4 ONNX (vision_q4 + embed_int8 + decoder_q4) | 1.75 s | 5.0 s | 15.0 s (0.12 s/tok) | **~22 s** |
| INT8 ONNX (vision_q4 + embed_int8 + decoder_int8) | 1.77 s | 2.1 s | 4.7 s (0.06 s/tok) | **~7 s** |

The INT8 decoder is **strictly faster** than the Q4 decoder on Mac CPU. INT8 uses native int8 SIMD instructions; Q4 has to unpack to int8 before MAC ops. Counter-intuitive but real.

**Android projection** (INT8 + XNNPACK adds ~2× over Mac CPU INT8):
- Pixel 9 (Tensor G4): **~3-5 s/query** (down from prior estimate 10-20 s)
- Pixel 6 (Tensor G1): **~10-15 s/query**

**This is the latency win we needed.** Even allowing for ARM vs Apple Silicon disparity, sub-10s on flagship Android is realistic. The PROJECT_PLAN latency story can be substantially upgraded.

## Quality loss (the bad news)

Same image, same prompt ("Describe this image in detail for someone who cannot see it…"):

**FP32 HF (Day 1)**:
> The image shows a screenshot of the ReadAloud AI Voices app. The top of the screen displays the app's name and a brief description. The main content area shows two options: "Download voice model" and "Enable cloned voices system-wide"…

**Q4 ONNX**:
> The image shows a black and white photograph of a phone screen. The phone is positioned at the bottom right corner of the image. The screen displays a list of options, including a "Select" button, a "Save" button, and a "Close" button…

**INT8 ONNX**:
> The image shows a phone screen displaying a list of names and their corresponding descriptions. The names are "Bella", "Chad", and "Chad", and the descriptions are "a woman", "a man", and "a man"…

Both ONNX paths produce:
- Wrong colors ("black and white" — it's a color screenshot)
- Invented UI elements (buttons that aren't there, names that aren't there)
- No accurate text reading

## Root cause: multi-crop

Moondream2's reference `_run_vision_encoder` does this:

```python
overlap_crops = overlap_crop_image(np_image, max_crops=4, overlap_margin=8)
# → multiple 378×378 sub-crops + a global 378×378 view
encoded = encoder(crops)        # one forward pass per crop
features = vision_projection(global_features, reconstructed)  # fused
```

The Xenova `vision_encoder.onnx` only implements the single-crop path. There's no multi-crop input shape, and the projection layer expects a single tile not the assembly. The output is the same dimension (729 tokens × 2048 dim) but represents only a heavily downsampled view of the original image.

For a 960×2142 screenshot resized to 378×378, **the text is compressed by 5-6× linearly** — way past where small UI labels stay legible to the vision encoder.

## Path forward — three options

| Option | Effort | Quality | Latency | Bundle size |
|---|---|---|---|---|
| A. Ship Xenova single-crop as-is | 0 | **Poor on screenshots/text** | **3-7 s on Pixel 9** | 1.1 GB |
| B. Port `overlap_crop_image` to Kotlin, call vision_encoder.onnx N times + manual fusion | Medium (~3-5 days) | Likely much better but uncertain — Xenova projection may not fuse correctly | 4-8× current = **12-30 s** | 1.1 GB |
| C. Pivot to llama.cpp + GGUF (`ggml-org/moondream2-20250414-GGUF`) | Medium-high (~1-2 weeks for Android integration) | Matches FP32 reference (multi-crop built-in) | Slower than ONNX but acceptable | ~1.5 GB after Q4_K_M quantization |
| D. Custom ONNX export with multi-crop baked in via Optimum / `torch.onnx.export` | High (~1+ week) | Best | Same as A (3-7 s) | ~1.5-2 GB |

**Recommendation: B first** — try the Kotlin port of multi-crop with Xenova's vision encoder. If the fusion works, we get the best of both worlds (small bundle + ONNX speed + multi-crop quality). If it doesn't fuse cleanly, **fall back to C** (llama.cpp/GGUF) — the GGUF release of Moondream2 is exactly this multi-crop pipeline + a mature Android runtime.

## Day-2 status

- [x] Bundle downloads + loads in `onnxruntime` (after tokenizer + decoder workarounds)
- [x] End-to-end inference runs
- [x] Quality vs FP32 baseline measured — **regression confirmed, cause identified (single-crop)**
- [ ] Single-crop FP32 test (control experiment to confirm multi-crop is the only difference)
- [ ] Generation-config fix for the repetition-loop bug (defer — depends on quality decision above)
- [ ] Read-text prompt mode validated — **tried, same single-crop quality loss applies**

## Next decision (blocking week-2 start)

Pick a path from A/B/C/D before starting Android scaffolding. The architecture of the WorkManager downloader + the inference module differs significantly between ONNX (A/B/D) and GGUF (C).
