$ErrorActionPreference = 'Stop'

$Server = 'root@101.200.53.44'
$Jar = 'D:\demo\target\demo-0.0.1-SNAPSHOT-boot.jar'
$RedisSetup = 'D:\demo\deploy\setup-redis.sh'
if (-not (Test-Path -LiteralPath $Jar)) {
    throw "JAR not found: $Jar. Run .\mvnw.cmd clean test package first."
}
$ExpectedHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $Jar).Hash.ToLowerInvariant()
$RemoteCommand = @'
set -e
EXPECTED_SHA256=__EXPECTED_SHA256__
echo REMOTE_UPLOAD_SHA256
sha256sum /tmp/demo-0.0.1-SNAPSHOT-boot.jar
printf '%s  %s\n' "$EXPECTED_SHA256" /tmp/demo-0.0.1-SNAPSHOT-boot.jar | sha256sum -c -
sed -i 's/\r$//' /tmp/setup-redis.sh
chmod 700 /tmp/setup-redis.sh
/tmp/setup-redis.sh
install -o root -g root -m 0644 /tmp/demo-0.0.1-SNAPSHOT-boot.jar /opt/demo-assistant/demo-assistant.jar
systemctl restart demo-assistant
sleep 12
echo INSTALLED_SHA256
sha256sum /opt/demo-assistant/demo-assistant.jar
printf '%s  %s\n' "$EXPECTED_SHA256" /opt/demo-assistant/demo-assistant.jar | sha256sum -c -
echo SERVICE_STATUS
systemctl is-active demo-assistant
echo REDIS_STATUS
redis-cli ping
redis-cli INFO server | grep redis_version
echo RECENT_LOGS
journalctl -u demo-assistant -n 40 --no-pager
'@.Replace('__EXPECTED_SHA256__', $ExpectedHash)

Write-Host "Expected SHA256: $ExpectedHash" -ForegroundColor Cyan
Write-Host 'Uploading latest JAR...' -ForegroundColor Cyan
& scp -- $Jar $RedisSetup "${Server}:/tmp/"
if ($LASTEXITCODE -ne 0) {
    Write-Host 'UPLOAD FAILED' -ForegroundColor Red
    Read-Host 'Press Enter to close this window'
    exit 1
}
& ssh -t $Server $RemoteCommand
if ($LASTEXITCODE -ne 0) {
    Write-Host 'DEPLOYMENT FAILED' -ForegroundColor Red
} else {
    Write-Host 'DEPLOYMENT FINISHED' -ForegroundColor Green
}
Read-Host 'Press Enter to close this window'
