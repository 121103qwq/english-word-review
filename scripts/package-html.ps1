$source = Join-Path $PSScriptRoot "..\dist\index.html"
$destinationDirectory = Join-Path $PSScriptRoot "..\release\html"
$destination = Join-Path $destinationDirectory "english-word-review-8.0.0.html"

if (-not (Test-Path -LiteralPath $source)) {
  throw "Built HTML not found: $source"
}

New-Item -ItemType Directory -Force -Path $destinationDirectory | Out-Null
Copy-Item -LiteralPath $source -Destination $destination -Force
Write-Output $destination
