#!/usr/bin/env python3
"""Run Moondream2 on a small image corpus. One prompt per image
(detailed mode) — quality consistency across image types is what we
need to validate, not prompt sensitivity."""
import os, sys, time, glob, json
import torch
torch.backends.mps.is_available = lambda: False
torch.backends.mps.is_built = lambda: False

from PIL import Image
from transformers import AutoModelForCausalLM, AutoTokenizer

MODEL = "vikhyatk/moondream2"
REVISION = "2025-04-14"
CORPUS_DIR = "/tmp/moondream_spike/corpus"

print(f"loading {MODEL} ({REVISION}) on CPU…")
t0 = time.time()
model = AutoModelForCausalLM.from_pretrained(
    MODEL, trust_remote_code=True, revision=REVISION,
    torch_dtype=torch.float32, device_map="cpu",
)
tokenizer = AutoTokenizer.from_pretrained(MODEL, revision=REVISION)
print(f"  loaded in {time.time() - t0:.1f}s\n")

PROMPT = "Describe this image in detail for someone who cannot see it. Mention the main subject, layout, and any text visible."

results = []
images = sorted(glob.glob(f"{CORPUS_DIR}/*.png") + glob.glob(f"{CORPUS_DIR}/*.jpg"))
print(f"corpus: {len(images)} images\n")

for i, path in enumerate(images, 1):
    name = os.path.basename(path)
    img = Image.open(path).convert("RGB")
    print(f"--- [{i}/{len(images)}] {name} ({img.size[0]}x{img.size[1]}) ---")

    t = time.time()
    enc = model.encode_image(img)
    dt_enc = time.time() - t

    t = time.time()
    answer = model.answer_question(enc, PROMPT, tokenizer)
    dt_ans = time.time() - t

    total = dt_enc + dt_ans
    print(f"  encode {dt_enc:.1f}s + answer {dt_ans:.1f}s = {total:.1f}s")
    print(f"  > {answer}\n")

    results.append({
        "image": name,
        "size": img.size,
        "encode_s": round(dt_enc, 2),
        "answer_s": round(dt_ans, 2),
        "total_s": round(total, 2),
        "answer": answer,
    })

# Summary
print("=" * 60)
print(f"corpus summary ({len(results)} images):")
total_time = sum(r["total_s"] for r in results)
print(f"  total wall time: {total_time:.1f}s ({total_time/60:.1f}m)")
print(f"  avg per image:   {total_time/len(results):.1f}s")
print(f"  min/max:         {min(r['total_s'] for r in results):.1f}s / {max(r['total_s'] for r in results):.1f}s")

with open("/tmp/moondream_spike/corpus_results.json", "w") as f:
    json.dump(results, f, indent=2)
print(f"\nresults written to /tmp/moondream_spike/corpus_results.json")
