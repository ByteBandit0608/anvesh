#!/usr/bin/env bash
# Linux/macOS equivalent of download_model.ps1
set -euo pipefail
base="https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2/resolve/main"
dir="models/paraphrase-multilingual-MiniLM-L12-v2"
mkdir -p "$dir"
curl -L --progress-bar -o "$dir/tokenizer.json" "$base/tokenizer.json"
curl -L --progress-bar -o "$dir/model.onnx" "$base/onnx/model_quint8_avx2.onnx"
ls -lh "$dir"
