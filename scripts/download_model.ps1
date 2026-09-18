# Downloads the multilingual embedding model (int8-quantised ONNX, ~118 MB) + tokenizer.
# Usage (from project root):  powershell -ExecutionPolicy Bypass -File scripts\download_model.ps1
$ErrorActionPreference = "Stop"
$base = "https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2/resolve/main"
$dir  = "models\paraphrase-multilingual-MiniLM-L12-v2"
New-Item -ItemType Directory -Force -Path $dir | Out-Null

# Invoke-WebRequest is painfully slow with the progress bar on; disable it.
$ProgressPreference = "SilentlyContinue"

Write-Host "Downloading tokenizer.json (9 MB)..."
Invoke-WebRequest -Uri "$base/tokenizer.json" -OutFile "$dir\tokenizer.json"

Write-Host "Downloading model.onnx (118 MB, quantised)... this takes a few minutes"
Invoke-WebRequest -Uri "$base/onnx/model_quint8_avx2.onnx" -OutFile "$dir\model.onnx"

Get-ChildItem $dir | Format-Table Name, @{n="MB";e={[math]::Round($_.Length/1MB,1)}}
Write-Host "`nDone. Run the app with:  `$env:EMBEDDING_PROVIDER = 'onnx'"
