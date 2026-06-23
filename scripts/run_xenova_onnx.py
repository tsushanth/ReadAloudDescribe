#!/usr/bin/env python3
"""Manual ONNX Runtime inference for Xenova/moondream2 mixed-quant
bundle. Writes the pipeline as straight procedural code that maps
1:1 to what the Android Kotlin port will do — no framework magic.

Bundle (Day-1 pick, ~1.07 GB total):
  vision_encoder_q4.onnx         — image -> visual embeddings
  embed_tokens_int8.onnx          — token IDs -> text embeddings
  decoder_model_merged_q4f16.onnx — autoregressive text decoder w/ KV cache

Validates two things at once:
  1. The bundle loads in onnxruntime CPU (the closest proxy for what
     ONNX Runtime Android does — the Android port adds XNNPACK on top).
  2. Quality stays close to the FP32 baseline (compare to Day-1 corpus).
"""
import sys, time, json
from pathlib import Path
import numpy as np
from PIL import Image
import onnxruntime as ort
from transformers import AutoTokenizer

BUNDLE = Path("/tmp/moondream_spike/xenova_onnx")
IMAGE = sys.argv[1] if len(sys.argv) > 1 else "/tmp/moondream_spike/test_screenshot.png"
PROMPT = sys.argv[2] if len(sys.argv) > 2 else "Describe this image in detail for someone who cannot see it."
MAX_NEW_TOKENS = 200

# ---------------------------------------------------------------
# 1. Load tokenizer + the three ONNX sessions on CPU
# ---------------------------------------------------------------
print("loading tokenizer + onnx sessions…")
t0 = time.time()
# Use the upstream vikhyatk/moondream2 tokenizer — same vocab (Phi-2/CodeGen),
# without Xenova's <image>:-200 sentinel which trips the Rust tokenizers crate.
# We splice visual embeddings ourselves, so we never need to tokenize <image>.
tokenizer = AutoTokenizer.from_pretrained("vikhyatk/moondream2", revision="2025-04-14", trust_remote_code=True)

sess_opts = ort.SessionOptions()
sess_opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL

# The Q4F16 decoder crashes with ONNXRuntime 1.27's SimplifiedLayerNormFusion
# graph optimization — Xenova's export has constants that the optimizer
# tries to fuse but can't locate. Disable graph opts for that session only.
decoder_opts = ort.SessionOptions()
decoder_opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_DISABLE_ALL

providers = ["CPUExecutionProvider"]

vision_sess = ort.InferenceSession(
    str(BUNDLE / "onnx/vision_encoder_q4.onnx"), sess_opts, providers=providers
)
embed_sess = ort.InferenceSession(
    str(BUNDLE / "onnx/embed_tokens_int8.onnx"), sess_opts, providers=providers
)
DECODER_FILE = "onnx/decoder_model_merged_int8.onnx"  # try _int8 / _q4 / _q4f16
decoder_sess = ort.InferenceSession(
    str(BUNDLE / DECODER_FILE), decoder_opts, providers=providers
)
print(f"  decoder: {DECODER_FILE}")
print(f"  loaded in {time.time()-t0:.1f}s")

# Helpful for the Android port — what does each session expect?
print("\n=== vision_encoder inputs/outputs ===")
for i in vision_sess.get_inputs():
    print(f"  in:  {i.name:<30} {i.type} {i.shape}")
for o in vision_sess.get_outputs():
    print(f"  out: {o.name:<30} {o.type} {o.shape}")

print("\n=== embed_tokens inputs/outputs ===")
for i in embed_sess.get_inputs():
    print(f"  in:  {i.name:<30} {i.type} {i.shape}")
for o in embed_sess.get_outputs():
    print(f"  out: {o.name:<30} {o.type} {o.shape}")

print("\n=== decoder inputs/outputs ===")
for i in decoder_sess.get_inputs():
    print(f"  in:  {i.name:<30} {i.type} {i.shape}")
for o in decoder_sess.get_outputs()[:3]:  # there will be many KV outputs
    print(f"  out: {o.name:<30} {o.type} {o.shape}")
print(f"  ...({len(decoder_sess.get_outputs())} total outputs incl. KV cache)")

# ---------------------------------------------------------------
# 2. Preprocess image (SigLIP: 378x378, normalize to [-1,1])
# ---------------------------------------------------------------
print(f"\nimage: {IMAGE}")
img = Image.open(IMAGE).convert("RGB").resize((378, 378), Image.BICUBIC)
pixels = np.asarray(img).astype(np.float32) / 255.0  # (378, 378, 3) in [0,1]
pixels = (pixels - 0.5) / 0.5                          # normalize to [-1,1]
pixels = pixels.transpose(2, 0, 1)[None, ...]          # (1, 3, 378, 378)
print(f"  preprocessed -> {pixels.shape} {pixels.dtype}")

# ---------------------------------------------------------------
# 3. Run vision encoder
# ---------------------------------------------------------------
t = time.time()
vision_input_name = vision_sess.get_inputs()[0].name
visual_embeds = vision_sess.run(None, {vision_input_name: pixels})[0]
print(f"vision encoder: {time.time()-t:.2f}s, output shape {visual_embeds.shape} {visual_embeds.dtype}")

# ---------------------------------------------------------------
# 4. Tokenize prompt + embed text tokens
# ---------------------------------------------------------------
# Moondream prompt template (per Xenova model card)
text = f"<image>\n\nQuestion: {PROMPT}\n\nAnswer:"
# We need text tokens BEFORE and AFTER the image position separately
# because we splice visual embeddings in the middle.
# Easier: split the text on <image>, tokenize each side.
parts = text.split("<image>", 1)
left_ids = tokenizer(parts[0], return_tensors="np", add_special_tokens=True)["input_ids"]
right_ids = tokenizer(parts[1], return_tensors="np", add_special_tokens=False)["input_ids"]
print(f"\ntokens: left={left_ids.shape[1]}, right={right_ids.shape[1]}, image_embeds={visual_embeds.shape[1]}")

# Embed the text token IDs to the same embedding dim as the visual embeds
embed_input_name = embed_sess.get_inputs()[0].name
left_embeds = embed_sess.run(None, {embed_input_name: left_ids.astype(np.int64)})[0]
right_embeds = embed_sess.run(None, {embed_input_name: right_ids.astype(np.int64)})[0]

# Concatenate: [BOS+prefix text] [image] [suffix text]
# All three need to be FP32 to feed back into the decoder. The embed
# session output may be FP16 (it's int8 quant for weights but FP32/16 io).
def to_decoder_dtype(x):
    return x.astype(np.float32) if x.dtype != np.float32 else x

inputs_embeds = np.concatenate([
    to_decoder_dtype(left_embeds),
    to_decoder_dtype(visual_embeds),
    to_decoder_dtype(right_embeds),
], axis=1)
print(f"merged inputs_embeds: {inputs_embeds.shape} {inputs_embeds.dtype}")

# ---------------------------------------------------------------
# 5. Decoder loop with KV cache
# ---------------------------------------------------------------
# decoder_model_merged.onnx is a "merged" decoder — it handles both
# prefill (no cache) and decode (with cache) via the use_cache_branch
# input. Let's find what inputs it actually wants.

decoder_input_names = [i.name for i in decoder_sess.get_inputs()]
decoder_output_names = [o.name for o in decoder_sess.get_outputs()]
print(f"\ndecoder input names: {decoder_input_names[:6]} ...({len(decoder_input_names)} total)")

# Locate KV cache input names
kv_in_names = [n for n in decoder_input_names if n.startswith("past_key_values")]
kv_out_names = [n for n in decoder_output_names if n.startswith("present")]
print(f"KV pairs: {len(kv_in_names)//2} layers (in={len(kv_in_names)}, out={len(kv_out_names)})")

EOS = 50256

# Initial KV cache: empty (shape [1, num_heads, 0, head_dim])
# Phi config: 24 layers, 32 heads, hidden 2048 -> head_dim = 64
NUM_LAYERS, NUM_HEADS, HEAD_DIM = 24, 32, 64
empty_kv = {n: np.zeros((1, NUM_HEADS, 0, HEAD_DIM), dtype=np.float32) for n in kv_in_names}

generated_ids = []
t_decode = time.time()
T = inputs_embeds.shape[1]  # full prefix length

# Prefill pass (use_cache_branch=False)
attention_mask = np.ones((1, T), dtype=np.int64)
position_ids = np.arange(T, dtype=np.int64)[None, :]

feed = {
    "inputs_embeds": inputs_embeds.astype(np.float32),
    "attention_mask": attention_mask,
    "position_ids": position_ids,
    **empty_kv,
}
out = decoder_sess.run(None, feed)
logits = out[0]
# Update KV cache from the present_* outputs (positions 1..N)
present_kvs = {kv_in_names[i]: out[1+i] for i in range(len(kv_in_names))}

next_id = int(np.argmax(logits[0, -1, :]))
generated_ids.append(next_id)
print(f"prefill done in {time.time()-t_decode:.1f}s, first token: {next_id} ({tokenizer.decode([next_id])!r})")

# Decode loop (use_cache_branch=True, one token at a time)
for step in range(1, MAX_NEW_TOKENS):
    if next_id == EOS:
        print(f"\nEOS at step {step}")
        break
    # Embed the new token
    tok_embed = embed_sess.run(None, {embed_input_name: np.array([[next_id]], dtype=np.int64)})[0]
    tok_embed = to_decoder_dtype(tok_embed)

    cur_T = T + step
    feed = {
        "inputs_embeds": tok_embed.astype(np.float32),
        "attention_mask": np.ones((1, cur_T), dtype=np.int64),
        "position_ids": np.array([[cur_T - 1]], dtype=np.int64),
        **present_kvs,
    }
    out = decoder_sess.run(None, feed)
    logits = out[0]
    present_kvs = {kv_in_names[i]: out[1+i] for i in range(len(kv_in_names))}
    next_id = int(np.argmax(logits[0, -1, :]))
    generated_ids.append(next_id)
    if step % 20 == 0:
        elapsed = time.time() - t_decode
        print(f"  step {step}/{MAX_NEW_TOKENS}, {elapsed:.1f}s elapsed, last token: {tokenizer.decode([next_id])!r}")

total_decode = time.time() - t_decode
print(f"\ndecode done: {len(generated_ids)} tokens in {total_decode:.1f}s ({total_decode/len(generated_ids):.2f}s/tok)")

# ---------------------------------------------------------------
# 6. Final answer
# ---------------------------------------------------------------
answer = tokenizer.decode(generated_ids, skip_special_tokens=True)
print("\n" + "=" * 60)
print("ANSWER:")
print(answer)
print("=" * 60)
