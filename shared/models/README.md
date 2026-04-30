# Model Downloads

Model files are gitignored (`*.litertlm`). Download before running any inference.

## Gemma 4 E4B (recommended, ~3.5 GB)

```python
from huggingface_hub import hf_hub_download
hf_hub_download(
    repo_id="litert-community/gemma-4-E4B-it-litert-lm",
    filename="gemma-4-E4B-it.litertlm",
    local_dir="shared/models",
)
```

## Gemma 4 E2B (fallback, ~2 GB)

```python
from huggingface_hub import hf_hub_download
hf_hub_download(
    repo_id="litert-community/gemma-4-E2B-it-litert-lm",
    filename="gemma-4-E2B-it.litertlm",
    local_dir="shared/models",
)
```

After downloading, run the smoke test:
```
python ai-pipeline/inference/test_local.py
```
