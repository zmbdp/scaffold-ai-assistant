<#
.SYNOPSIS
    Download bge-large-zh-v1.5 local Embedding model (ONNX format)
.DESCRIPTION
    Downloads Xenova/bge-large-zh-v1.5 ONNX model and tokenizer.
    Uses hf-mirror.com (China mirror) by default for faster access without VPN.
    Use -UseOfficial to download from huggingface.co directly (requires VPN in China).
.PARAMETER TargetDir
    Target directory where bge-large-zh-v1.5/ will be created.
    If omitted, defaults to the script's directory (models/).
.PARAMETER UseOfficial
    Use official huggingface.co instead of China mirror hf-mirror.com.
.EXAMPLE
    .\download-embedding-model.ps1
    # Downloads from hf-mirror.com to .\bge-large-zh-v1.5\ (default, same dir as script)
.EXAMPLE
    .\download-embedding-model.ps1 -UseOfficial
    # Downloads from huggingface.co (requires VPN in China)
.NOTES
    Model: BAAI/bge-large-zh-v1.5 (Chinese Embedding, 1024 dims, ONNX Runtime, zero API calls)
    Size:  model.onnx ~1.3GB, tokenizer.json ~1.2MB
    ONNX output layer: last_hidden_state (config: scaffold.embedding.local.model-output-name)
#>

param(
    [string]$TargetDir = "",
    [switch]$UseOfficial
)

$ErrorActionPreference = "Stop"

$modelName = "bge-large-zh-v1.5"
# China mirror (hf-mirror.com) by default, no VPN needed
$baseHost = if ($UseOfficial) { "huggingface.co" } else { "hf-mirror.com" }
$baseUrl = "https://${baseHost}/Xenova/bge-large-zh-v1.5/resolve/main"
$files = @(
    @{ Name = "model.onnx";    Url = "$baseUrl/onnx/model.onnx" },
    @{ Name = "tokenizer.json"; Url = "$baseUrl/tokenizer.json" }
)

# Resolve target: -TargetDir if provided, otherwise script's own directory (models/)
if ($TargetDir -ne "") {
    $baseDir = $TargetDir
} else {
    $baseDir = Split-Path -Parent $MyInvocation.MyCommand.Path
}
$targetDir = Join-Path $baseDir $modelName

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Download $modelName local Embedding model" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Source: $baseHost"
Write-Host "Target: $targetDir"
Write-Host ""

# Create base directory if it does not exist
if (-not (Test-Path $baseDir)) {
    New-Item -ItemType Directory -Path $baseDir -Force | Out-Null
    Write-Host "Created base dir: $baseDir" -ForegroundColor Green
}

# Create model subdirectory
if (-not (Test-Path $targetDir)) {
    New-Item -ItemType Directory -Path $targetDir -Force | Out-Null
    Write-Host "Created: $targetDir" -ForegroundColor Green
}

foreach ($file in $files) {
    $filePath = Join-Path $targetDir $file.Name

    if (Test-Path $filePath) {
        $existingSize = (Get-Item $filePath).Length
        if ($existingSize -gt 0) {
            Write-Host "[SKIP] $($file.Name) already exists ($([math]::Round($existingSize / 1MB, 2)) MB)" -ForegroundColor Yellow
            continue
        }
    }

    Write-Host "[DOWN] $($file.Name) ..." -ForegroundColor Cyan
    try {
        $ProgressPreference = 'Continue'
        Invoke-WebRequest -Uri $file.Url -OutFile $filePath -UseBasicParsing
        $fileSize = (Get-Item $filePath).Length
        Write-Host "[DONE] $($file.Name) ($([math]::Round($fileSize / 1MB, 2)) MB)" -ForegroundColor Green
    }
    catch {
        Write-Host "[FAIL] $($file.Name) : $($_.Exception.Message)" -ForegroundColor Red
        Write-Host "  Manual download: $($file.Url)" -ForegroundColor Yellow
        Write-Host "  Or try with VPN: .\download-embedding-model.ps1 -UseOfficial" -ForegroundColor Yellow
        exit 1
    }
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Green
Write-Host "  Download complete!" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green
Write-Host ""
Write-Host "Files saved to: $targetDir"
Write-Host ""
Write-Host "After files are in place, restart chat-service to take effect."
