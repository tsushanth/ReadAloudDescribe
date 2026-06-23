#!/usr/bin/env python3
"""SmolVLM 500M spike — small VLM with first-class HF support.
Quality target: useful enough to beat TalkBack's basic image labels on
non-Pixel devices."""
import sys, time
from PIL import Image
import torch
from transformers import AutoProcessor, AutoModelForImageTextToText

MODEL = "HuggingFaceTB/SmolVLM-500M-Instruct"
IMAGE = sys.argv[1] if len(sys.argv) > 1 else "/tmp/moondream_spike/test_screenshot.png"

print(f"loading {MODEL}…")
t0 = time.time()
processor = AutoProcessor.from_pretrained(MODEL)
model = AutoModelForImageTextToText.from_pretrained(MODEL, torch_dtype=torch.float32)
print(f"  loaded in {time.time() - t0:.1f}s")

img = Image.open(IMAGE).convert("RGB")
print(f"image: {IMAGE} ({img.size[0]}x{img.size[1]})")

prompts = [
    ("short caption",    "Describe this image in one short sentence."),
    ("detailed",         "Describe this image in detail for a blind person. Mention the main subject, layout, colors, and any text visible."),
    ("read-aloud text",  "What text appears in this image? Read it out exactly as written."),
]

for label, prompt in prompts:
    print(f"\n--- {label} ---")
    t = time.time()
    messages = [{
        "role": "user",
        "content": [
            {"type": "image"},
            {"type": "text", "text": prompt},
        ],
    }]
    text = processor.apply_chat_template(messages, add_generation_prompt=True)
    inputs = processor(text=text, images=[img], return_tensors="pt")
    out = model.generate(**inputs, max_new_tokens=300)
    answer = processor.batch_decode(out[:, inputs["input_ids"].shape[1]:], skip_special_tokens=True)[0].strip()
    dt = time.time() - t
    print(f"  [{dt:.1f}s] {answer}")
