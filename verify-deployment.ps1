$ErrorActionPreference = 'Stop'

$Server = 'root@101.200.53.44'
$Jar = 'D:\demo\target\demo-0.0.1-SNAPSHOT-boot.jar'
if (-not (Test-Path -LiteralPath $Jar)) {
    throw "JAR not found: $Jar"
}
$ExpectedHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $Jar).Hash.ToLowerInvariant()
$RemoteCommand = @'
set -eu
EXPECTED_SHA256=__EXPECTED_SHA256__
echo '=== SERVICE ==='
systemctl is-active demo-assistant
echo '=== INSTALLED JAR ==='
sha256sum /opt/demo-assistant/demo-assistant.jar
printf '%s  %s\n' "$EXPECTED_SHA256" /opt/demo-assistant/demo-assistant.jar | sha256sum -c -
echo '=== ADMIN STATUS ==='
curl -sS -w '\nHTTP=%{http_code}\n' http://127.0.0.1:8080/api/admin/status
echo '=== RECENT ERRORS ==='
journalctl -u demo-assistant -n 100 --no-pager
'@.Replace('__EXPECTED_SHA256__', $ExpectedHash)

Write-Host "Expected SHA256: $ExpectedHash" -ForegroundColor Cyan
Write-Host 'Verifying deployed service (enter the server password once)...' -ForegroundColor Cyan
& ssh -t $Server $RemoteCommand
if ($LASTEXITCODE -ne 0) {
    Write-Host 'VERIFICATION FAILED' -ForegroundColor Red
} else {
    Write-Host 'VERIFICATION FINISHED' -ForegroundColor Green
}
Read-Host 'Press Enter to close this window'
