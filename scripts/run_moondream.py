#!/usr/bin/env python3
"""Moondream2 inference spike — measure quality + latency for image
description on Mac, as a proxy for what an Android port would deliver
(slower) on commodity hardware."""
import sys, time
import torch
# Moondream2 vision.py monkey-patches adaptive_avg_pool2d to force
# output onto MPS whenever MPS is detected, ignoring the rest of the
# model's device placement. Hide MPS from torch BEFORE importing
# transformers so that patch never fires.
torch.backends.mps.is_available = lambda: False
torch.backends.mps.is_built = lambda: False

from PIL import Image
from transformers import AutoModelForCausalLM, AutoTokenizer

MODEL = "vikhyatk/moondream2"
# 2025-04-14 revision has the MPS/CPU tensor-mismatch fix that the
# 2025-01-09 revision had on Apple Silicon. We force CPU below
# anyway — Android port has no MPS, so CPU latency on Mac is the
# closest proxy to commodity Android hardware.
REVISION = "2025-04-14"
IMAGE = sys.argv[1] if len(sys.argv) > 1 else "/tmp/moondream_spike/test_screenshot.png"

print(f"loading {MODEL} (revision {REVISION}) on CPU…")
t0 = time.time()
model = AutoModelForCausalLM.from_pretrained(
    MODEL, trust_remote_code=True, revision=REVISION,
    torch_dtype=torch.float32, device_map="cpu",
)
tokenizer = AutoTokenizer.from_pretrained(MODEL, revision=REVISION)
print(f"  loaded in {time.time() - t0:.1f}s")

img = Image.open(IMAGE)
print(f"image: {IMAGE} ({img.size[0]}x{img.size[1]})")

# Three useful description modes for BVI
prompts = [
    ("short caption",  "Describe this image in one short sentence."),
    ("detailed",        "Describe this image in detail for someone who cannot see it. Mention the main subject, layout, and any text visible."),
    ("read-aloud text", "What text is visible in this image? Read it out exactly."),
]

for label, prompt in prompts:
    print(f"\n--- {label} ---")
    t = time.time()
    enc_image = model.encode_image(img)
    answer = model.answer_question(enc_image, prompt, tokenizer)
    dt = time.time() - t
    print(f"  [{dt:.1f}s] {answer}")
