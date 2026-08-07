$source = Join-Path $PSScriptRoot "..\android\app\build\outputs\apk\debug\app-debug.apk"
$destinationDirectory = Join-Path $PSScriptRoot "..\release\android"
$version = (Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot "..\package.json") | ConvertFrom-Json).version
if ($version -notmatch '^\d+\.\d+\.\d+$') { throw "Invalid package version: $version" }
$destination = Join-Path $destinationDirectory "english-word-review-$version-android8-plus.apk"

if (-not (Test-Path -LiteralPath $source)) {
  throw "Android APK not found: $source"
}

New-Item -ItemType Directory -Force -Path $destinationDirectory | Out-Null
Copy-Item -LiteralPath $source -Destination $destination -Force
Write-Output $destination
