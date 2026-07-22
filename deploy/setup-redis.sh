#!/usr/bin/env bash
set -Eeuo pipefail

if [[ "${EUID}" -ne 0 ]]; then
  echo "ERROR: Run this script as root."
  exit 1
fi

if ! command -v redis-server >/dev/null 2>&1; then
  echo "Installing Redis..."
  if command -v dnf >/dev/null 2>&1; then dnf install -y redis
  elif command -v yum >/dev/null 2>&1; then yum install -y redis
  elif command -v apt-get >/dev/null 2>&1; then
    apt-get update
    DEBIAN_FRONTEND=noninteractive apt-get install -y redis-server
  else
    echo "ERROR: No supported package manager was found."
    exit 1
  fi
fi

if systemctl list-unit-files redis.service >/dev/null 2>&1; then
  REDIS_SERVICE=redis
elif systemctl list-unit-files redis-server.service >/dev/null 2>&1; then
  REDIS_SERVICE=redis-server
else
  echo "ERROR: Redis systemd service was not found."
  exit 1
fi

systemctl enable --now "${REDIS_SERVICE}"
redis-cli ping | grep -q PONG

ENV_FILE=/etc/demo-assistant/demo-assistant.env
install -d -o demo-assistant -g demo-assistant -m 0700 /var/lib/demo-assistant/knowledge
touch "${ENV_FILE}"
grep -q '^REDIS_URL=' "${ENV_FILE}" || echo 'REDIS_URL=redis://127.0.0.1:6379/0' >> "${ENV_FILE}"
grep -q '^CONVERSATION_REDIS_ENABLED=' "${ENV_FILE}" || echo 'CONVERSATION_REDIS_ENABLED=true' >> "${ENV_FILE}"
grep -q '^CONVERSATION_TTL_MINUTES=' "${ENV_FILE}" || echo 'CONVERSATION_TTL_MINUTES=1440' >> "${ENV_FILE}"
grep -q '^KNOWLEDGE_BASE_PATH=' "${ENV_FILE}" || echo 'KNOWLEDGE_BASE_PATH=/var/lib/demo-assistant/knowledge' >> "${ENV_FILE}"
grep -q '^REMINDERS_ENABLED=' "${ENV_FILE}" || echo 'REMINDERS_ENABLED=true' >> "${ENV_FILE}"
grep -q '^REMINDERS_POLL_MS=' "${ENV_FILE}" || echo 'REMINDERS_POLL_MS=10000' >> "${ENV_FILE}"
chown root:demo-assistant "${ENV_FILE}"
chmod 0640 "${ENV_FILE}"

echo "Redis PING: $(redis-cli ping)"
echo "Redis bind: $(redis-cli --raw CONFIG GET bind | tail -n 1)"
echo "Redis protected-mode: $(redis-cli --raw CONFIG GET protected-mode | tail -n 1)"
echo "Conversation TTL: 1440 minutes (sliding expiration)"
