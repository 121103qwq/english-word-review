$source = Join-Path $PSScriptRoot "..\dist\index.html"
$destinationDirectory = Join-Path $PSScriptRoot "..\release\html"
$version = (Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot "..\package.json") | ConvertFrom-Json).version
if ($version -notmatch '^\d+\.\d+\.\d+$') { throw "Invalid package version: $version" }
$destination = Join-Path $destinationDirectory "english-word-review-$version.html"

if (-not (Test-Path -LiteralPath $source)) {
  throw "Built HTML not found: $source"
}

New-Item -ItemType Directory -Force -Path $destinationDirectory | Out-Null
$content = [System.IO.File]::ReadAllText($source).Replace("`r`n", "`n").Replace("`r", "`n")
[System.IO.File]::WriteAllText($destination, $content, [System.Text.UTF8Encoding]::new($false))
Write-Output $destination
