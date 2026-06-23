# scripts/

Mac validation spike scripts. Not part of the Android app — these
exist to validate model quality + latency before committing to a
particular VLM + deployment path.

## Setup

```bash
brew install vips                        # libvips for Moondream2 preprocessing
python3.13 -m venv .venv
./.venv/bin/pip install --upgrade pip
./.venv/bin/pip install \
    'transformers==4.49.0' torch pillow accelerate einops pyvips
```

## What's here

| Script | Purpose |
|---|---|
| `run_moondream.py` | Moondream2 2B FP32, one image, three prompt modes |
| `run_smolvlm.py` | SmolVLM-500M same shape (kept for comparison — ruled out 2026-06-23) |
| `run_corpus.py` | Moondream2 across a corpus directory, JSON-out for diffing |

## Known bug worked around

Moondream2's `vision.py` monkey-patches `adaptive_avg_pool2d` to
force its output onto MPS whenever Apple Silicon is detected.
`device_map="cpu"` doesn't help — the patch fires inside the model.

All scripts hide MPS from torch before importing the model:

```python
import torch
torch.backends.mps.is_available = lambda: False
torch.backends.mps.is_built = lambda: False
```

Doesn't affect Android (no MPS there).
