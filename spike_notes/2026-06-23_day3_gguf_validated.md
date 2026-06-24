# Day-3 spike: GGUF + llama.cpp path validated (2026-06-23 evening)

## TL;DR

- ✅ `llama-mtmd-cli` + Moondream2 GGUF runs end-to-end on Mac CPU. **Multi-crop fires natively** (`n_chunks=3` confirmed in logs).
- 🎯 **Quality on natural photos is genuinely better than FP32 HF for the BVI use case** — GGUF stays honestly descriptive where FP32 fabricates specific (wrong) values.
- 🎯 **Q5_K_M is the v1 quant pick**: 1.86 GB total bundle, ~11 s Mac CPU latency, no hallucinated specifics. Q4_K_M (1.75 GB) saves 6% size but invents false text (disqualifying for accessibility use).
- ⚠️ Both published Moondream2 GGUFs (`ggml-org/moondream2-20250414-GGUF`, `moondream/moondream2-gguf`) emit two warnings on load — outdated pre-tokenizer + FFN tensors swapped. Doesn't break output in practice, but the GGUFs should eventually be regenerated from upstream PyTorch.

## llama.cpp install

```bash
brew install llama.cpp     # version 9770 (75ad0b23e)
```

Binaries: `llama-cli`, `llama-mtmd-cli`, `llama-quantize`, `llama-server`.

## GGUF source

Two repos tried, both have same issues:

| Repo | mmproj | text-model | Both warn on load? |
|---|---|---|---|
| `ggml-org/moondream2-20250414-GGUF` | 868 MB F16 | 2.6 GB F16 (ct-vicuna suffix) | yes |
| `moondream/moondream2-gguf` | 868 MB F16 | 2.6 GB F16 | yes |

Both load fine after `--chat-template vicuna`. Warnings:

```
W load: missing pre-tokenizer type, using: 'default'
W load: GENERATION QUALITY WILL BE DEGRADED!
W load_tensors: ffn up/down are swapped
```

These are GGUF-format compat issues from older conversion tools — fixable by regenerating with current `convert_hf_to_gguf.py` against the upstream `vikhyatk/moondream2` PyTorch model. Punted to follow-up.

## Multi-crop is native

Log line during inference:

```
I encoding mtmd batch, n_chunks = 1 (done = 1, total = 3)
```

`total = 3` confirms the image was split into 3 crops (1 global + 2 local for the 960×2142 test screenshot), encoded separately, and fused in the C++ runtime. **This is exactly what Path B with Xenova ONNX couldn't do.**

## Quality comparison on the same passport photo (corpus/03_photo.jpg)

| Path | Output |
|---|---|
| FP32 HF (Day 1) | "...passport from India... biometric... passport number is **R0885436**... name **SUSHANTI**, date of birth **31/08/1985**, place of birth **SAN FRANCISCO**, sex **M**..." (every specific value invented) |
| **GGUF F16** | "...person holding a passport... resting on a white surface... blue cover... background blurred but appears to be a room with a white wall and a red polka dot pattern." (no false specifics) |
| GGUF Q5_K_M | "...passport with a man's face printed on it... resting on a white surface with black polka dots..." (color wrong, but no false text specifics) |
| GGUF Q4_K_M | "...passport appears to be a **birth certificate**, with the name **BARRELL** and **LAS VEGAS** printed on it..." (invented name + city) |

**Critical insight for BVI accessibility**: GGUF F16 and Q5_K_M are *strictly better* than the FP32 HF reference for our use case. FP32's hallucinations are catastrophic — a blind user told their DOB is "31/08/1985" when it isn't has a real-world consequence. The GGUF paths stay honest, describing only what they can see. Q4_K_M slips back to FP32-level confidence in fabrication, so it's out.

## Latency

Mac CPU, F16 mmproj + various text quants, single passport image:

| Quant | Text-model size | Total bundle | Mac CPU wall time |
|---|---|---|---|
| F16 | 2.6 GB | 3.5 GB | 19 s |
| Q5_K_M | 991 MB | 1.86 GB | 11 s |
| Q4_K_M | 877 MB | 1.75 GB | 11 s |

Projected Android (Q5_K_M + ARM NEON via llama.android, 2-3× slower than Mac CPU):
- Pixel 9: ~25-35 s/query
- Mid-range Snapdragon: ~50-90 s/query

Higher than ONNX would have given us (3-5 s on Pixel 9 with the broken-quality single-crop path) but trustworthy descriptions in exchange.

## UX implications

- **Show progress aggressively.** 25-90 s on-device inference cannot feel instant. Stream the description token-by-token (llama.cpp supports this natively) so the user hears it as it generates rather than waiting for full completion.
- **Frame the product as "describes images" not "reads documents."** Even with GGUF's safer behavior, the model fundamentally guesses at fine-grained text. Use ML Kit OCR (already in our stack) for any text-extraction task; route to the VLM only for scene/context.
- **Default sampling temperature should be low** (0.0-0.2). Higher temperatures invite hallucination on uncertain inputs. The whole point is honest descriptions, not creative writing.

## What's still pending

- [ ] Regenerate GGUFs from upstream PyTorch with current `convert_hf_to_gguf.py` (fixes the pre-tokenizer + FFN warnings)
- [ ] Run Q5_K_M on the full corpus (currently only verified on 2 images: screenshot + passport)
- [ ] Wire `llama.android` into a new Android Studio project (Day 4+ — the main engineering work)
- [ ] Measure actual Android latency on Pixel + Samsung (don't trust the 2-3× projection until we benchmark)

## Updated decision log

PROJECT_PLAN.md decisions to be flipped:

- #10: ~~GGUF Q4_K_M ~1.0-1.3 GB~~ → **Q5_K_M ~1.86 GB** (Q4_K_M hallucinates names — disqualifying for accessibility)
- #11 (new): **GGUF source**: `moondream/moondream2-gguf` or `ggml-org/moondream2-20250414-GGUF` (interchangeable for now). Locally requantize text-model to Q5_K_M. Keep mmproj as F16 (it's only 868 MB and quantizing the vision projector tends to hurt more than the text model).
- #12 (new): **Sampling**: greedy/low-temp by default to discourage hallucination on uncertain inputs.
