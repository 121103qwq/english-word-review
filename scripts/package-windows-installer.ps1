$compiler = Join-Path $PSScriptRoot "..\.tauri-tools\nsis-3.11\makensis.exe"
$script = Join-Path $PSScriptRoot "..\installer\windows.nsi"
$outputDirectory = Join-Path $PSScriptRoot "..\release\windows-installer"

if (-not (Test-Path -LiteralPath $compiler)) {
  throw "NSIS compiler not found. Extract the official NSIS 3.11 zip to .tauri-tools/nsis-3.11."
}
if (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot "..\src-tauri\target\release\english-word-review.exe"))) {
  throw "Tauri release executable not found. Run npm run tauri:compile first."
}

New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
& $compiler /V2 $script
if ($LASTEXITCODE -ne 0) { throw "NSIS installer build failed with exit code $LASTEXITCODE" }

Write-Output (Join-Path $outputDirectory "english-word-review-8.0.0-setup.exe")
