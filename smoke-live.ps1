$ErrorActionPreference = 'Stop'
$Server = 'root@101.200.53.44'
$Audio = 'D:\demo\nihao-test.wav'

Write-Host 'Uploading audio fixture (password 1/2)...' -ForegroundColor Cyan
& scp -- $Audio "${Server}:/tmp/nihao-test.wav"
if ($LASTEXITCODE -ne 0) { throw 'Audio fixture upload failed' }

$RemoteCommand = @'
set -u
BASE=http://127.0.0.1:8080
echo '=== 1 SERVICE ==='
systemctl is-active demo-assistant
echo '=== 2 NORMAL CHAT ==='
curl -sS -X POST "$BASE/api/chat" -H 'Content-Type: application/json' --data '{"question":"请只回复：普通对话正常"}'
echo
echo '=== 3 CURRENT WEATHER FUNCTION CALL ==='
curl -sS -X POST "$BASE/api/chat" -H 'Content-Type: application/json' --data '{"question":"北京现在天气怎么样？"}'
echo
echo '=== 4 FORECAST FUNCTION CALL ==='
curl -sS -X POST "$BASE/api/chat" -H 'Content-Type: application/json' --data '{"question":"杭州未来三天天气怎么样？"}'
echo
echo '=== 5 IMAGE GENERATION FUNCTION CALL ==='
curl -sS -X POST "$BASE/api/chat" -H 'Content-Type: application/json' --data '{"question":"生成一张极简风格的蓝色圆形图标，白色背景"}'
echo
echo '=== 6 TEXT TO SPEECH ==='
curl -sS -o /tmp/function-smoke.wav -w 'HTTP=%{http_code} BYTES=%{size_download}\n' -X POST "$BASE/api/audio/synthesize" -H 'Content-Type: application/json' --data '{"text":"功能测试正常"}'
echo '=== 7 SPEECH RECOGNITION ==='
curl -sS -X POST "$BASE/api/audio/recognize" -F 'file=@/tmp/nihao-test.wav'
echo
echo '=== 8 FUNCTION CALL LOGS ==='
journalctl -u demo-assistant --since '10 minutes ago' --no-pager | grep -E '执行 Function Calling 工具|Function Calling 执行失败|万相图片生成成功|天气查询成功|未来天气查询成功|语音识别|语音合成' | tail -n 80 || true
'@

Write-Host 'Running live smoke tests (password 2/2)...' -ForegroundColor Cyan
& ssh -t $Server $RemoteCommand
Write-Host "`nLIVE SMOKE TEST FINISHED" -ForegroundColor Green
Read-Host 'Press Enter to close this window'
