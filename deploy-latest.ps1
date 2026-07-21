$ErrorActionPreference = 'Stop'

$Server = 'root@101.200.53.44'
$Jar = 'D:\demo\target\demo-0.0.1-SNAPSHOT-boot.jar'
$ExpectedHash = '7024ca11fa73cfdf5d1f9c1fc8b9a8cf230bc9ad3e6c7cdfbbb3ebc6e135a08f'
$RemoteCommand = @'
set -e
echo REMOTE_UPLOAD_SHA256
sha256sum /tmp/demo-0.0.1-SNAPSHOT-boot.jar
install -o root -g root -m 0644 /tmp/demo-0.0.1-SNAPSHOT-boot.jar /opt/demo-assistant/demo-assistant.jar
systemctl restart demo-assistant
sleep 12
echo INSTALLED_SHA256
sha256sum /opt/demo-assistant/demo-assistant.jar
echo SERVICE_STATUS
systemctl is-active demo-assistant
echo RECENT_LOGS
journalctl -u demo-assistant -n 40 --no-pager
'@

Write-Host "Expected SHA256: $ExpectedHash" -ForegroundColor Cyan
Write-Host 'Uploading latest JAR...' -ForegroundColor Cyan
& scp -- $Jar "${Server}:/tmp/"
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
