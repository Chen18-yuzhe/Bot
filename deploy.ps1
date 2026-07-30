param(
    [string] $Server = 'root@101.200.53.44',
    [switch] $SkipBuild
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = $PSScriptRoot
$Jar = Join-Path $ProjectRoot 'target\wechat-agent-platform-1.0.0-boot.jar'
$Installer = Join-Path $ProjectRoot 'deploy\install-wechat-agent-platform.sh'
$Verifier = Join-Path $ProjectRoot 'deploy\verify-production.sh'

if (-not $SkipBuild) {
    Write-Host 'Running release tests and coverage gate...' -ForegroundColor Cyan
    Push-Location $ProjectRoot
    try {
        & (Join-Path $ProjectRoot 'mvnw.cmd') clean verify
        if ($LASTEXITCODE -ne 0) { throw 'Release build failed.' }
    } finally {
        Pop-Location
    }
}

foreach ($file in @($Jar, $Installer, $Verifier)) {
    if (-not (Test-Path -LiteralPath $file)) {
        throw "Required release file not found: $file"
    }
}

$expectedHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $Jar).Hash.ToLowerInvariant()
$remoteCommand = @'
set -Eeuo pipefail
sed -i 's/\r$//' /tmp/install-wechat-agent-platform.sh /tmp/verify-production.sh
chmod 700 /tmp/install-wechat-agent-platform.sh /tmp/verify-production.sh
FOLLOW_LOGS=false bash /tmp/install-wechat-agent-platform.sh
EXPECTED_SHA256=__EXPECTED_SHA256__ bash /tmp/verify-production.sh
'@.Replace('__EXPECTED_SHA256__', $expectedHash)

Write-Host "Uploading release $expectedHash to $Server..." -ForegroundColor Cyan
& scp -- $Jar $Installer $Verifier "${Server}:/tmp/"
if ($LASTEXITCODE -ne 0) { throw 'Release upload failed.' }

& ssh -t $Server $remoteCommand
if ($LASTEXITCODE -ne 0) { throw 'Remote deployment or verification failed.' }

Write-Host 'Deployment and production verification passed.' -ForegroundColor Green
