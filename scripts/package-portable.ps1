param(
  [string]$Profile = "release"
)

$source = Join-Path $PSScriptRoot "..\src-tauri\target\$Profile\english-word-review.exe"
$destinationDirectory = Join-Path $PSScriptRoot "..\release\windows-portable"
$version = (Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot "..\package.json") | ConvertFrom-Json).version
if ($version -notmatch '^\d+\.\d+\.\d+$') { throw "Invalid package version: $version" }
$destination = Join-Path $destinationDirectory "english-word-review-$version-portable.exe"

if (-not (Test-Path -LiteralPath $source)) {
  throw "Tauri executable not found: $source"
}

New-Item -ItemType Directory -Force -Path $destinationDirectory | Out-Null
Copy-Item -LiteralPath $source -Destination $destination -Force
Write-Output $destination
