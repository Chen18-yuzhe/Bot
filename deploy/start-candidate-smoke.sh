#!/usr/bin/env bash
set -Eeuo pipefail

JAR="${JAR:-/tmp/wechat-agent-platform-1.0.0-boot.jar}"
ENV_FILE="${ENV_FILE:-/etc/demo-assistant/demo-assistant.env}"
PID_FILE="${PID_FILE:-/tmp/wechat-agent-smoke.pid}"
LOG_FILE="${LOG_FILE:-/tmp/wechat-agent-smoke.log}"
DATA_DIR="${DATA_DIR:-/tmp/wechat-agent-smoke-data}"
PORT="${PORT:-18080}"

if [[ -s "${PID_FILE}" ]]; then
  existing_pid="$(cat "${PID_FILE}")"
  if [[ -r "/proc/${existing_pid}/cmdline" ]] &&
      tr '\0' ' ' < "/proc/${existing_pid}/cmdline" |
        grep -Fq -- "--server.port=${PORT}"; then
    kill "${existing_pid}"
    for _ in $(seq 1 30); do
      [[ ! -d "/proc/${existing_pid}" ]] && break
      sleep 1
    done
  fi
fi

if [[ "${STOP_ONLY:-false}" == true ]]; then
  rm -f "${PID_FILE}"
  printf 'candidate_stopped=PASS\n'
  exit 0
fi

install -d "${DATA_DIR}/knowledge"
set -a
# shellcheck disable=SC1090
. "${ENV_FILE}"
set +a

nohup java -jar "${JAR}" \
  --server.address=127.0.0.1 \
  --server.port="${PORT}" \
  --assistant.ilink-enabled=false \
  --assistant.reminders.enabled=false \
  --assistant.audit.enabled=false \
  --assistant.conversation.storage-file="${DATA_DIR}/conversations.json" \
  --assistant.conversation.sqlite-file="${DATA_DIR}/conversations.db" \
  --assistant.knowledge-base.path="${DATA_DIR}/knowledge" \
  --assistant.agent.storage-file="${DATA_DIR}/agent-tasks.json" \
  --assistant.conversation.redis-key-prefix=wechat-agent-smoke:conversation: \
  --assistant.agent.redis-key-prefix=wechat-agent-smoke:agent: \
  --assistant.reminders.redis-key-prefix=wechat-agent-smoke:reminder: \
  --assistant.rate-limit.per-minute=100 \
  > "${LOG_FILE}" 2>&1 &
printf '%s\n' "$!" > "${PID_FILE}"

for _ in $(seq 1 90); do
  if curl --fail --silent --show-error --max-time 2 \
    "http://127.0.0.1:${PORT}/actuator/health" 2>/dev/null |
      grep -q '"status"[[:space:]]*:[[:space:]]*"UP"'; then
    printf 'candidate_health=PASS\n'
    exit 0
  fi
  sleep 1
done

printf 'candidate_health=FAIL\n' >&2
tail -n 100 "${LOG_FILE}" >&2
exit 1
