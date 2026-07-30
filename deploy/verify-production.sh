#!/usr/bin/env bash
set -Eeuo pipefail

APP_NAME="${APP_NAME:-wechat-agent-platform}"
BASE="${BASE:-http://127.0.0.1:8080}"
ENV_FILE="${ENV_FILE:-/etc/wechat-agent-platform/wechat-agent-platform.env}"
JAR="${JAR:-/opt/wechat-agent-platform/wechat-agent-platform.jar}"
EXPECTED_SHA256="${EXPECTED_SHA256:-}"
WORK_DIR="${WORK_DIR:-/tmp/wechat-agent-production-checks}"

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
  [[ -n "${!name:-}" ]] || {
    printf '%s=MISSING\n' "${name}" >&2
    exit 1
  }
}

systemctl is-active --quiet "${APP_NAME}.service"
systemctl is-enabled --quiet "${APP_NAME}.service"
pass systemd

if systemctl is-active --quiet demo-assistant.service; then
  printf 'legacy_service=STILL_ACTIVE\n' >&2
  exit 1
fi
pass legacy_service_stopped

if [[ -n "${EXPECTED_SHA256}" ]]; then
  actual_sha256="$(sha256sum "${JAR}" | awk '{print $1}')"
  [[ "${actual_sha256}" == "${EXPECTED_SHA256}" ]]
  pass artifact_sha256
fi

for key_name in DASHSCOPE_API_KEY QWEATHER_API_HOST QWEATHER_API_KEY ADMIN_API_TOKEN \
  TRAVEL_MAP_API_URL TRAVEL_MAP_API_KEY TRAVEL_ATTRACTION_API_URL \
  TRAVEL_ATTRACTION_API_KEY FLYAI_API_KEY; do
  require_env "${key_name}"
done
pass environment_keys

curl --fail --silent --show-error "${BASE}/actuator/health" > "${WORK_DIR}/health.json"
grep -q '"status"[[:space:]]*:[[:space:]]*"UP"' "${WORK_DIR}/health.json"
pass health

redis-cli ping | grep -q '^PONG$'
pass redis

curl --fail --silent --show-error --get "${BASE}/api/weather" \
  --data-urlencode "city=北京" > "${WORK_DIR}/weather.json"
grep -q '"weather"' "${WORK_DIR}/weather.json"
pass qweather

curl --fail --silent --show-error \
  -H "X-Admin-Token: ${ADMIN_API_TOKEN}" \
  "${BASE}/api/admin/status" > "${WORK_DIR}/admin.json"
grep -q '"redis"[[:space:]]*:[[:space:]]*"pong"' "${WORK_DIR}/admin.json"
pass admin

curl --fail --silent --show-error "${BASE}/api/agent/tasks/types" > "${WORK_DIR}/agent-types.json"
grep -q '"generic"' "${WORK_DIR}/agent-types.json"
pass agent_types

printf '{"question":"只回复 PRODUCTION_SMOKE_OK"}' > "${WORK_DIR}/chat-request.json"
curl --fail --silent --show-error --max-time 120 \
  -X POST "${BASE}/api/chat" \
  -H "Content-Type: application/json" \
  --data-binary "@${WORK_DIR}/chat-request.json" > "${WORK_DIR}/chat.json"
grep -q '"answer"' "${WORK_DIR}/chat.json"
pass dashscope_chat

printf 'ALL_PRODUCTION_CHECKS=PASS\n'
