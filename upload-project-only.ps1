$ErrorActionPreference = 'Stop'

$Server = 'root@101.200.53.44'
$ProjectRoot = 'D:\demo'
$Stage = Join-Path $env:TEMP ("demo-project-upload-" + [guid]::NewGuid().ToString('N'))
$Archive = Join-Path $env:TEMP ("demo-project-" + [guid]::NewGuid().ToString('N') + '.tar.gz')

New-Item -ItemType Directory -Path $Stage -Force | Out-Null

Get-ChildItem -LiteralPath $ProjectRoot -Force |
    Where-Object { $_.Name -notin @('.git', 'target', '.idea') } |
    Copy-Item -Destination $Stage -Recurse -Force

tar -czf $Archive -C $Stage .
if ($LASTEXITCODE -ne 0) {
    Write-Host 'Could not create the archive.' -ForegroundColor Red
    Read-Host 'Press Enter to close'
    exit 1
}

Write-Host 'Uploading source project only to /opt/demo-project ...' -ForegroundColor Cyan
Write-Host 'Enter the root password when prompted. Password input is invisible.'
& scp -- $Archive "${Server}:/tmp/demo-project.tar.gz"
if ($LASTEXITCODE -ne 0) {
    Write-Host 'Upload failed.' -ForegroundColor Red
    Read-Host 'Press Enter to close'
    exit 1
}

$RemoteCommand = 'set -e; rm -rf /opt/demo-project.new; mkdir -p /opt/demo-project.new; tar -xzf /tmp/demo-project.tar.gz -C /opt/demo-project.new; rm -rf /opt/demo-project; mv /opt/demo-project.new /opt/demo-project; rm -f /tmp/demo-project.tar.gz; chmod -R u+rwX,go+rX /opt/demo-project; echo "Project uploaded to /opt/demo-project"'

Write-Host 'Extracting on the server ...' -ForegroundColor Green
& ssh -t $Server $RemoteCommand
if ($LASTEXITCODE -ne 0) {
    Write-Host 'Remote extraction failed.' -ForegroundColor Red
    Read-Host 'Press Enter to close'
    exit 1
}

Remove-Item -LiteralPath $Archive -Force -ErrorAction SilentlyContinue
Write-Host 'Done. No service was installed or started.' -ForegroundColor Green
Read-Host 'Press Enter to close'
