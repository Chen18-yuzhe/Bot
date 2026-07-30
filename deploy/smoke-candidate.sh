#!/usr/bin/env bash
set -Eeuo pipefail

BASE="${BASE:-http://127.0.0.1:18080}"
ENV_FILE="${ENV_FILE:-/etc/demo-assistant/demo-assistant.env}"
WORK_DIR="${WORK_DIR:-/tmp/wechat-agent-smoke-checks}"

mkdir -p "${WORK_DIR}"
set -a
# shellcheck disable=SC1090
. "${ENV_FILE}"
set +a

pass() {
  printf '%s=PASS\n' "$1"
}

require_env() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    printf '%s=MISSING\n' "${name}" >&2
    exit 1
  fi
}

json_assert() {
  local file="$1" expression="$2"
  python3 - "${file}" "${expression}" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    data = json.load(handle)
scope = {"data": data, "len": len, "bool": bool, "isinstance": isinstance, "str": str}
if not eval(sys.argv[2], {"__builtins__": {}}, scope):
    raise SystemExit(f"JSON assertion failed for {sys.argv[1]}")
PY
}

post_chat() {
  local name="$1" question="$2"
  local payload="${WORK_DIR}/${name}-request.json"
  local response="${WORK_DIR}/${name}-response.json"
  curl --fail --silent --show-error -X DELETE \
    -H "X-Admin-Token: ${ADMIN_API_TOKEN}" \
    --get "${BASE}/api/admin/ops/conversations" \
    --data-urlencode "sessionId=assistant" > /dev/null
  python3 - "${question}" > "${payload}" <<'PY'
import json
import sys
print(json.dumps({"question": sys.argv[1]}, ensure_ascii=False))
PY
  curl --fail --silent --show-error --max-time 120 \
    -X POST "${BASE}/api/chat" \
    -H "Content-Type: application/json" \
    --data-binary "@${payload}" > "${response}"
  json_assert "${response}" 'isinstance(data.get("answer"), str) and len(data["answer"].strip()) > 0'
  pass "${name}"
}

for key_name in DASHSCOPE_API_KEY QWEATHER_API_HOST QWEATHER_API_KEY ADMIN_API_TOKEN \
  TRAVEL_MAP_API_URL TRAVEL_MAP_API_KEY TRAVEL_ATTRACTION_API_URL \
  TRAVEL_ATTRACTION_API_KEY FLYAI_API_KEY; do
  require_env "${key_name}"
done

curl --fail --silent --show-error "${BASE}/actuator/health" > "${WORK_DIR}/health.json"
json_assert "${WORK_DIR}/health.json" 'data.get("status") == "UP"'
pass health

redis-cli ping | grep -q '^PONG$'
pass redis

curl --fail --silent --show-error --get "${BASE}/api/weather" \
  --data-urlencode "city=北京" > "${WORK_DIR}/weather.json"
json_assert "${WORK_DIR}/weather.json" 'bool(data.get("city")) and bool(data.get("weather"))'
pass qweather_key

curl --fail --silent --show-error \
  -H "X-Admin-Token: ${ADMIN_API_TOKEN}" \
  "${BASE}/api/admin/status" > "${WORK_DIR}/admin.json"
json_assert "${WORK_DIR}/admin.json" 'len(data.get("tools", [])) >= 20 and data.get("redis") == "pong"'
pass admin_token

curl --fail --silent --show-error --get \
  "${TRAVEL_MAP_API_URL%/}/v3/geocode/geo" \
  --data-urlencode "address=杭州西湖" \
  --data-urlencode "key=${TRAVEL_MAP_API_KEY}" > "${WORK_DIR}/amap-map.json"
json_assert "${WORK_DIR}/amap-map.json" 'data.get("status") == "1" and len(data.get("geocodes", [])) > 0'
pass travel_map_key

curl --fail --silent --show-error --get \
  "${TRAVEL_ATTRACTION_API_URL%/}/v3/place/text" \
  --data-urlencode "keywords=西湖" \
  --data-urlencode "city=杭州" \
  --data-urlencode "key=${TRAVEL_ATTRACTION_API_KEY}" > "${WORK_DIR}/amap-attraction.json"
json_assert "${WORK_DIR}/amap-attraction.json" 'data.get("status") == "1" and len(data.get("pois", [])) > 0'
pass travel_attraction_key

timeout 60 flyai keyword-search --query "杭州西湖门票" > "${WORK_DIR}/flyai.json"
json_assert "${WORK_DIR}/flyai.json" 'bool(data)'
pass flyai_key

post_chat dashscope_chat "只回复 MODEL_SMOKE_OK"
post_chat weather_function "请查询北京当前天气，必须使用天气工具。"
post_chat forecast_function "请查询杭州未来三天天气，必须使用天气工具。"
post_chat web_search_function "请联网搜索今天的阿里云主要新闻并说明来源。"
post_chat image_generation_function "请生成一张极简蓝色圆形图标。"
post_chat reminder_function "请查看我当前的提醒列表。"
post_chat calculator_function "请使用科学计算器计算 sqrt(144)+2^5。"
post_chat time_function "请使用时间工具查询北京时间。"

install -d /tmp/wechat-agent-smoke-data/knowledge/accounts/default
printf 'SMOKE_RAG_TOKEN_20260730=知识库外部验证成功\n' > \
  /tmp/wechat-agent-smoke-data/knowledge/accounts/default/smoke-rag.txt
post_chat rag_function "请从知识库查找 SMOKE_RAG_TOKEN_20260730 的值。"
python3 - "${WORK_DIR}/rag_function-response.json" <<'PY'
import json
import sys
with open(sys.argv[1], encoding="utf-8") as handle:
    answer = json.load(handle)["answer"]
if "知识库外部验证成功" not in answer:
    raise SystemExit("RAG answer did not contain the expected private fact")
PY
pass rag_content

curl --fail --silent --show-error --max-time 120 \
  -X POST "${BASE}/api/audio/synthesize" \
  -H "Content-Type: application/json" \
  --data-binary '{"text":"功能验证成功"}' > "${WORK_DIR}/tts.wav"
[[ "$(wc -c < "${WORK_DIR}/tts.wav")" -gt 1000 ]]
pass tts

curl --fail --silent --show-error --max-time 120 \
  -X POST "${BASE}/api/audio/recognize" \
  -F "file=@/tmp/nihao-test.wav" > "${WORK_DIR}/asr.json"
json_assert "${WORK_DIR}/asr.json" 'isinstance(data.get("text"), str) and len(data["text"].strip()) > 0'
pass asr

curl --fail --silent --show-error "${BASE}/api/agent/tasks/types" > "${WORK_DIR}/agent-types.json"
json_assert "${WORK_DIR}/agent-types.json" 'len(data) >= 5'
pass agent_types

python3 > "${WORK_DIR}/agent-request.json" <<'PY'
import json
print(json.dumps({
    "sessionId": "release-smoke",
    "taskType": "generic",
    "goal": "不要调用工具，只回复 AGENT_SMOKE_OK",
    "delaySeconds": 0
}, ensure_ascii=False))
PY
curl --fail --silent --show-error -X POST "${BASE}/api/agent/tasks" \
  -H "Content-Type: application/json" \
  --data-binary "@${WORK_DIR}/agent-request.json" > "${WORK_DIR}/agent-created.json"
AGENT_ID="$(python3 - "${WORK_DIR}/agent-created.json" <<'PY'
import json
import sys
with open(sys.argv[1], encoding="utf-8") as handle:
    print(json.load(handle)["id"])
PY
)"
for _ in $(seq 1 90); do
  curl --fail --silent --show-error "${BASE}/api/agent/tasks/${AGENT_ID}" > "${WORK_DIR}/agent-state.json"
  AGENT_STATUS="$(python3 - "${WORK_DIR}/agent-state.json" <<'PY'
import json
import sys
with open(sys.argv[1], encoding="utf-8") as handle:
    print(json.load(handle)["status"])
PY
)"
  case "${AGENT_STATUS}" in
    COMPLETED) break ;;
    FAILED|CANCELLED) printf 'agent_status=%s\n' "${AGENT_STATUS}" >&2; exit 1 ;;
  esac
  sleep 1
done
[[ "${AGENT_STATUS}" == "COMPLETED" ]]
json_assert "${WORK_DIR}/agent-state.json" 'bool(data.get("finalAnswer"))'
pass durable_agent

curl --fail --silent --show-error \
  -H "X-Admin-Token: ${ADMIN_API_TOKEN}" \
  "${BASE}/api/admin/status" > "${WORK_DIR}/final-admin.json"
json_assert "${WORK_DIR}/final-admin.json" 'len(data.get("toolMetrics", {})) > 0'
pass tool_metrics

printf 'ALL_CANDIDATE_SMOKE_TESTS=PASS\n'
