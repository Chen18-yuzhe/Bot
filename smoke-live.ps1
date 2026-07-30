$ErrorActionPreference = 'Stop'
$Server = 'root@101.200.53.44'
$Audio = 'D:\demo\nihao-test.wav'

if (-not (Test-Path -LiteralPath $Audio)) {
    throw "Audio fixture not found: $Audio"
}

Write-Host 'Uploading audio fixture (password 1/2)...' -ForegroundColor Cyan
& scp -- $Audio "${Server}:/tmp/nihao-test.wav"
if ($LASTEXITCODE -ne 0) { throw 'Audio fixture upload failed' }

$RemoteCommand = @'
set -eu
BASE=http://127.0.0.1:8080
echo eyJxdWVzdGlvbiI6Iuivt+WPquWbnuWkje+8muaZrumAmuWvueivneato+W4uCJ9 | base64 -d > /tmp/chat-normal.json
echo eyJxdWVzdGlvbiI6IuWMl+S6rOeOsOWcqOWkqeawlOaAjuS5iOagt++8nyJ9 | base64 -d > /tmp/chat-weather.json
echo eyJxdWVzdGlvbiI6IuadreW3nuacquadpeS4ieWkqeWkqeawlOaAjuS5iOagt++8nyJ9 | base64 -d > /tmp/chat-forecast.json
echo eyJxdWVzdGlvbiI6IueUn+aIkOS4gOW8oOaegeeugOmjjuagvOeahOiTneiJsuWchuW9ouWbvuagh++8jOeZveiJsuiDjOaZryJ9 | base64 -d > /tmp/chat-image.json
echo eyJ0ZXh0Ijoi5Yqf6IO95rWL6K+V5q2j5bi4In0= | base64 -d > /tmp/tts.json
echo eyJxdWVzdGlvbiI6Iuivt+iBlOe9keaQnOe0ouS7iuWkqeeahOS4u+imgeS6uuW3peaZuuiDveaWsOmXu++8jOW5tuS/neeVmeadpea6kCJ9 | base64 -d > /tmp/chat-web.json
echo eyJxdWVzdGlvbiI6Iuivt+S7juS4quS6uuefpeivhuW6k+afpeivoiBGdW5jdGlvbiBDYWxsaW5nIOW8gOWPkeinhOiMgyJ9 | base64 -d > /tmp/chat-knowledge.json
echo eyJxdWVzdGlvbiI6Iuafpeeci+aPkOmGkuWIl+ihqCJ9 | base64 -d > /tmp/chat-reminders.json
echo '=== 1 SERVICE ==='
systemctl is-active wechat-agent-platform
echo '=== 2 NORMAL CHAT ==='
curl -sS -X POST $BASE/api/chat -H Content-Type:application/json --data-binary @/tmp/chat-normal.json
echo
echo '=== 3 CURRENT WEATHER FUNCTION CALL ==='
curl -sS -X POST $BASE/api/chat -H Content-Type:application/json --data-binary @/tmp/chat-weather.json
echo
echo '=== 4 FORECAST FUNCTION CALL ==='
curl -sS -X POST $BASE/api/chat -H Content-Type:application/json --data-binary @/tmp/chat-forecast.json
echo
echo '=== 5 IMAGE GENERATION FUNCTION CALL ==='
curl -sS -X POST $BASE/api/chat -H Content-Type:application/json --data-binary @/tmp/chat-image.json
echo
echo '=== 6 TEXT TO SPEECH ==='
curl -sS -o /tmp/function-smoke.wav -w 'HTTP=%{http_code}_BYTES=%{size_download}\n' -X POST $BASE/api/audio/synthesize -H Content-Type:application/json --data-binary @/tmp/tts.json
echo '=== 7 SPEECH RECOGNITION ==='
curl -sS -X POST $BASE/api/audio/recognize -F file=@/tmp/nihao-test.wav
echo
echo '=== 8 WEB SEARCH FUNCTION CALL ==='
curl -sS -X POST $BASE/api/chat -H Content-Type:application/json --data-binary @/tmp/chat-web.json
echo
echo '=== 9 KNOWLEDGE SEARCH FUNCTION CALL ==='
curl -sS -X POST $BASE/api/chat -H Content-Type:application/json --data-binary @/tmp/chat-knowledge.json
echo
echo '=== 10 REMINDER LIST FUNCTION CALL ==='
curl -sS -X POST $BASE/api/chat -H Content-Type:application/json --data-binary @/tmp/chat-reminders.json
echo
echo '=== 11 ADMIN STATUS ==='
curl -sS -w '\nHTTP=%{http_code}\n' $BASE/api/admin/status
echo '=== 12 FUNCTION CALL LOGS ==='
journalctl -u wechat-agent-platform --since '15 minutes ago' --no-pager | grep -E '执行 Function Calling 工具|Function Calling 执行失败|图片编辑|图片生成|天气查询|未来天气查询|联网搜索|知识库|提醒|文档处理|语音识别|语音合成' | tail -n 160 || true
'@

Write-Host 'Running live smoke tests (password 2/2)...' -ForegroundColor Cyan
& ssh -t $Server $RemoteCommand
Write-Host "`nLIVE SMOKE TEST FINISHED" -ForegroundColor Green
Read-Host 'Press Enter to close this window'
