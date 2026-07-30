$ErrorActionPreference = 'Stop'

$Server = 'root@101.200.53.44'
$ProjectRoot = 'D:\demo'
$Jar = Join-Path $ProjectRoot 'target\wechat-agent-platform-1.0.0-boot.jar'
$Installer = Join-Path $ProjectRoot 'deploy\install-wechat-agent-platform.sh'

function Stop-WithMessage([string] $Message) {
    Write-Host "`n$Message" -ForegroundColor Red
    Read-Host 'Press Enter to close this window'
    exit 1
}

if (-not (Test-Path -LiteralPath $Jar)) {
    Stop-WithMessage "Deployment JAR not found: $Jar"
}
if (-not (Test-Path -LiteralPath $Installer)) {
    Stop-WithMessage "Installer not found: $Installer"
}

Write-Host 'Deploying to Alibaba Cloud server 101.200.53.44.' -ForegroundColor Cyan
Write-Host 'For a first-time connection, type yes when asked Are you sure.'
Write-Host 'Then enter the root password. Password input is intentionally invisible.'
Write-Host

& scp -- $Jar $Installer "${Server}:/tmp/"
if ($LASTEXITCODE -ne 0) {
    Stop-WithMessage 'Upload failed. Check the root password, SSH security group, and server login settings.'
}

Write-Host "`nUpload complete. Enter the root password again, then provide the application settings." -ForegroundColor Green
$RemoteCommand = 'sed -i ''s/\r$//'' /tmp/install-wechat-agent-platform.sh && chmod 700 /tmp/install-wechat-agent-platform.sh && /tmp/install-wechat-agent-platform.sh'
& ssh -t $Server $RemoteCommand
if ($LASTEXITCODE -ne 0) {
    Stop-WithMessage 'Remote installation did not complete. Keep the error text shown above.'
}

Write-Host "`nRemote deployment session finished." -ForegroundColor Green
Read-Host 'Press Enter to close this window'
