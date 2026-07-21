$ErrorActionPreference = 'Stop'
$Server = 'root@101.200.53.44'
$RemoteCommand = @'
set -u
BASE=http://127.0.0.1:8080
echo eyJxdWVzdGlvbiI6Iuivt+WPquWbnuWkje+8muaZrumAmuWvueivneato+W4uCJ9 | base64 -d > /tmp/chat-normal.json
echo eyJxdWVzdGlvbiI6IuWMl+S6rOeOsOWcqOWkqeawlOaAjuS5iOagt++8nyJ9 | base64 -d > /tmp/chat-weather.json
echo eyJxdWVzdGlvbiI6IuadreW3nuacquadpeS4ieWkqeWkqeawlOaAjuS5iOagt++8nyJ9 | base64 -d > /tmp/chat-forecast.json
echo eyJxdWVzdGlvbiI6IueUn+aIkOS4gOW8oOaegeeugOmjjuagvOeahOiTneiJsuWchuW9ouWbvuagh++8jOeZveiJsuiDjOaZryJ9 | base64 -d > /tmp/chat-image.json
echo eyJ0ZXh0Ijoi5Yqf6IO95rWL6K+V5q2j5bi4In0= | base64 -d > /tmp/tts.json
echo '=== 1 NORMAL CHAT ==='
curl -sS -X POST $BASE/api/chat -H Content-Type:application/json --data-binary @/tmp/chat-normal.json
echo
echo '=== 2 CURRENT WEATHER FUNCTION CALL ==='
curl -sS -X POST $BASE/api/chat -H Content-Type:application/json --data-binary @/tmp/chat-weather.json
echo
echo '=== 3 FORECAST FUNCTION CALL ==='
curl -sS -X POST $BASE/api/chat -H Content-Type:application/json --data-binary @/tmp/chat-forecast.json
echo
echo '=== 4 IMAGE GENERATION FUNCTION CALL ==='
curl -sS -X POST $BASE/api/chat -H Content-Type:application/json --data-binary @/tmp/chat-image.json
echo
echo '=== 5 TEXT TO SPEECH ==='
curl -sS -o /tmp/function-smoke.wav -w 'HTTP=%{http_code}_BYTES=%{size_download}\n' -X POST $BASE/api/audio/synthesize -H Content-Type:application/json --data-binary @/tmp/tts.json
echo '=== 6 FUNCTION CALL LOGS ==='
journalctl -u demo-assistant --since '15 minutes ago' --no-pager | grep -E '执行 Function Calling 工具|Function Calling 执行失败|万相图片生成成功|天气查询成功|未来天气查询成功|语音识别|语音合成' | tail -n 100 || true
'@
& ssh -t $Server $RemoteCommand
Write-Host "`nLIVE SMOKE RETRY FINISHED" -ForegroundColor Green
Read-Host 'Press Enter to close this window'
