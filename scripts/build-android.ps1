$gradle = Join-Path $PSScriptRoot "..\.gradle-dist\gradle-8.14.3\bin\gradle.bat"
$project = Join-Path $PSScriptRoot "..\android"

if (-not (Test-Path -LiteralPath $gradle)) {
  throw "Project-local Gradle 8.14.3 not found: $gradle"
}

npm run android:sync
if ($LASTEXITCODE -ne 0) { throw "Capacitor sync failed with exit code $LASTEXITCODE" }

& $gradle -p $project assembleDebug
if ($LASTEXITCODE -ne 0) { throw "Android build failed with exit code $LASTEXITCODE" }
