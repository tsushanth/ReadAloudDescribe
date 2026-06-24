#!/usr/bin/env python3
"""Multi-crop fusion experiment for Xenova ONNX path.

Hypothesis: Xenova's vision_encoder.onnx implements single-crop only,
losing detail on high-res inputs. If we call it on multiple overlapping
crops + fuse the outputs, can we recover Moondream2's reference quality
without retraining?

Constraints:
  - Decoder context = 2048 tokens. Single crop = 729 vision tokens + ~20
    text = 749 (fits). Concat 5 crops = 3645 vision tokens — overflows.
  - Therefore only shape-preserving fusion (e.g. mean) is viable.
  - Projection is baked into vision_encoder.onnx so we fuse POST-projection
    features, not pre-projection (which is what Moondream2 reference does).

This experiment tries two shape-preserving fusion strategies and compares
output quality on the same test image used in Day 1 + Day 2.
"""
import sys, time, math
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
# Port of Moondream2's select_tiling + overlap_crop_image
# (from vikhyatk/moondream2/image_crops.py) — verbatim except we
# return PIL images instead of relying on pyvips/torch.
# ---------------------------------------------------------------
def select_tiling(height, width, crop_size, max_crops):
    if height <= crop_size or width <= crop_size:
        return (1, 1)
    min_h = math.ceil(height / crop_size)
    min_w = math.ceil(width / crop_size)
    if min_h * min_w > max_crops:
        ratio = math.sqrt(max_crops / (min_h * min_w))
        return (max(1, math.floor(min_h * ratio)), max(1, math.floor(min_w * ratio)))
    h_tiles = math.floor(math.sqrt(max_crops * height / width))
    w_tiles = math.floor(math.sqrt(max_crops * width / height))
    h_tiles = max(h_tiles, min_h)
    w_tiles = max(w_tiles, min_w)
    if h_tiles * w_tiles > max_crops:
        if w_tiles > h_tiles:
            w_tiles = math.floor(max_crops / h_tiles)
        else:
            h_tiles = math.floor(max_crops / w_tiles)
    return (max(1, h_tiles), max(1, w_tiles))


def overlap_crop_image(image_np, max_crops=4, overlap_margin=4,
                       base_size=(378, 378), patch_size=14):
    """Returns list of PIL crops (global first, then local)."""
    H, W = image_np.shape[:2]
    margin_px = patch_size * overlap_margin
    total_margin = margin_px * 2
    crop_patches = base_size[0] // patch_size
    crop_window_patches = crop_patches - 2 * overlap_margin
    crop_window_size = crop_window_patches * patch_size

    tiling = select_tiling(H - total_margin, W - total_margin,
                            crop_window_size, max_crops)
    print(f"  multicrop: tiling={tiling}, total crops={tiling[0]*tiling[1]+1}")

    # Resize source to fit the chosen tiling
    target_h = tiling[0] * crop_window_size + total_margin
    target_w = tiling[1] * crop_window_size + total_margin
    pil_src = Image.fromarray(image_np)
    pil_resized = pil_src.resize((target_w, target_h), Image.BICUBIC)
    img_resized = np.asarray(pil_resized)

    crops = []
    # 0: global crop (whole image resized to base_size)
    global_crop = pil_src.resize((base_size[1], base_size[0]), Image.BICUBIC)
    crops.append(np.asarray(global_crop))

    # 1..N: local overlapping crops
    for i in range(tiling[0]):
        for j in range(tiling[1]):
            y0 = i * crop_window_size
            x0 = j * crop_window_size
            crop = np.zeros((base_size[0], base_size[1], 3), dtype=np.uint8)
            patch = img_resized[y0:y0+base_size[0], x0:x0+base_size[1]]
            crop[:patch.shape[0], :patch.shape[1]] = patch
            crops.append(crop)

    return crops, tiling


# ---------------------------------------------------------------
# Standard preprocessing for SigLIP encoder
# ---------------------------------------------------------------
def to_encoder_input(crop_uint8):
    pixels = crop_uint8.astype(np.float32) / 255.0
    pixels = (pixels - 0.5) / 0.5
    pixels = pixels.transpose(2, 0, 1)[None, ...]
    return pixels


# ---------------------------------------------------------------
# Load sessions
# ---------------------------------------------------------------
print("loading ONNX sessions...")
t0 = time.time()
sess_opts = ort.SessionOptions()
sess_opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
decoder_opts = ort.SessionOptions()
decoder_opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_DISABLE_ALL
providers = ["CPUExecutionProvider"]

vision_sess = ort.InferenceSession(str(BUNDLE / "onnx/vision_encoder_q4.onnx"), sess_opts, providers=providers)
embed_sess = ort.InferenceSession(str(BUNDLE / "onnx/embed_tokens_int8.onnx"), sess_opts, providers=providers)
decoder_sess = ort.InferenceSession(str(BUNDLE / "onnx/decoder_model_merged_int8.onnx"), decoder_opts, providers=providers)
tokenizer = AutoTokenizer.from_pretrained("vikhyatk/moondream2", revision="2025-04-14", trust_remote_code=True)
print(f"  loaded in {time.time()-t0:.1f}s\n")

vision_in_name = vision_sess.get_inputs()[0].name
embed_in_name = embed_sess.get_inputs()[0].name
decoder_in_names = [i.name for i in decoder_sess.get_inputs()]
kv_in_names = [n for n in decoder_in_names if n.startswith("past_key_values")]

NUM_LAYERS, NUM_HEADS, HEAD_DIM = 24, 32, 64
EOS = 50256

# ---------------------------------------------------------------
# Encode image with multicrop
# ---------------------------------------------------------------
print(f"image: {IMAGE}")
img_pil = Image.open(IMAGE).convert("RGB")
img_np = np.asarray(img_pil)
print(f"  size: {img_pil.size[0]}x{img_pil.size[1]}")

crops, tiling = overlap_crop_image(img_np, max_crops=4)
print(f"  {len(crops)} crops at 378x378 (1 global + {len(crops)-1} local)")

# Run vision encoder on each crop
crop_embeds = []
t_vision = time.time()
for i, crop in enumerate(crops):
    inp = to_encoder_input(crop)
    out = vision_sess.run(None, {vision_in_name: inp})[0]  # (1, 729, 2048)
    crop_embeds.append(out)
print(f"vision encoder ({len(crops)}x): {time.time()-t_vision:.1f}s total")

# Stack: (N+1, 729, 2048)
stacked = np.stack(crop_embeds, axis=0).squeeze(1)
print(f"stacked crops shape: {stacked.shape}")

# ---------------------------------------------------------------
# Fusion strategy: MEAN across crops -> (1, 729, 2048)
# Concat would be (1, 3645, 2048) -> overflows Phi-2's 2048 context.
# ---------------------------------------------------------------
fused = stacked.mean(axis=0, keepdims=True)  # (1, 729, 2048)
print(f"fused (mean): {fused.shape}")

# ---------------------------------------------------------------
# Text + decoder
# ---------------------------------------------------------------
text = f"<image>\n\nQuestion: {PROMPT}\n\nAnswer:"
left, right = text.split("<image>", 1)
left_ids = tokenizer(left, return_tensors="np", add_special_tokens=True)["input_ids"]
right_ids = tokenizer(right, return_tensors="np", add_special_tokens=False)["input_ids"]
left_embeds = embed_sess.run(None, {embed_in_name: left_ids.astype(np.int64)})[0].astype(np.float32)
right_embeds = embed_sess.run(None, {embed_in_name: right_ids.astype(np.int64)})[0].astype(np.float32)

inputs_embeds = np.concatenate([left_embeds, fused.astype(np.float32), right_embeds], axis=1)
T = inputs_embeds.shape[1]
print(f"inputs_embeds: {inputs_embeds.shape}")

# Prefill
empty_kv = {n: np.zeros((1, NUM_HEADS, 0, HEAD_DIM), dtype=np.float32) for n in kv_in_names}
feed = {
    "inputs_embeds": inputs_embeds,
    "attention_mask": np.ones((1, T), dtype=np.int64),
    "position_ids": np.arange(T, dtype=np.int64)[None, :],
    **empty_kv,
}
t_dec = time.time()
out = decoder_sess.run(None, feed)
logits = out[0]
present_kvs = {kv_in_names[i]: out[1+i] for i in range(len(kv_in_names))}
next_id = int(np.argmax(logits[0, -1, :]))
generated = [next_id]

# Decode loop
for step in range(1, MAX_NEW_TOKENS):
    if next_id == EOS:
        break
    tok_embed = embed_sess.run(None, {embed_in_name: np.array([[next_id]], dtype=np.int64)})[0].astype(np.float32)
    cur_T = T + step
    feed = {
        "inputs_embeds": tok_embed,
        "attention_mask": np.ones((1, cur_T), dtype=np.int64),
        "position_ids": np.array([[cur_T - 1]], dtype=np.int64),
        **present_kvs,
    }
    out = decoder_sess.run(None, feed)
    present_kvs = {kv_in_names[i]: out[1+i] for i in range(len(kv_in_names))}
    next_id = int(np.argmax(out[0][0, -1, :]))
    generated.append(next_id)

print(f"decode: {len(generated)} tokens in {time.time()-t_dec:.1f}s")
print("\n" + "=" * 60)
print("ANSWER (multicrop mean fusion):")
print(tokenizer.decode(generated, skip_special_tokens=True))
print("=" * 60)
