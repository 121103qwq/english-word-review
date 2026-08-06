param(
  [string]$Profile = "release"
)

$source = Join-Path $PSScriptRoot "..\src-tauri\target\$Profile\english-word-review.exe"
$destinationDirectory = Join-Path $PSScriptRoot "..\release\windows-portable"
$destination = Join-Path $destinationDirectory "english-word-review-8.0.0-portable.exe"

if (-not (Test-Path -LiteralPath $source)) {
  throw "Tauri executable not found: $source"
}

New-Item -ItemType Directory -Force -Path $destinationDirectory | Out-Null
Copy-Item -LiteralPath $source -Destination $destination -Force
Write-Output $destination
