#!/usr/bin/env bash
set -Eeuo pipefail

APP_NAME="wechat-agent-platform"
APP_USER="wechat-agent-platform"
APP_DIR="/opt/${APP_NAME}"
DATA_DIR="/var/lib/${APP_NAME}"
ENV_DIR="/etc/${APP_NAME}"
ENV_FILE="${ENV_DIR}/${APP_NAME}.env"
SERVICE_FILE="/etc/systemd/system/${APP_NAME}.service"
SOURCE_JAR="/tmp/wechat-agent-platform-1.0.0-boot.jar"
LEGACY_ENV_FILE="/etc/demo-assistant/demo-assistant.env"
LEGACY_DATA_DIR="/var/lib/demo-assistant"

if [[ "${EUID}" -ne 0 ]]; then
  echo "ERROR: Run this installer as root."
  exit 1
fi

if [[ ! -f "${SOURCE_JAR}" ]]; then
  echo "ERROR: ${SOURCE_JAR} was not uploaded."
  exit 1
fi

JAVA_BIN=""

find_java_bin() {
  local candidate path_major
  if command -v java >/dev/null 2>&1; then
    candidate="$(command -v java)"
    path_major="$(java_major_version "${candidate}" || true)"
    if [[ "${path_major}" =~ ^[0-9]+$ ]] && (( path_major >= 21 )); then
      printf '%s\n' "${candidate}"
      return 0
    fi
  fi
  for candidate in /usr/lib/jvm/*/bin/java /usr/java/*/bin/java /opt/*/bin/java; do
    if [[ -x "${candidate}" ]]; then
      printf '%s\n' "${candidate}"
      return 0
    fi
  done
  if command -v rpm >/dev/null 2>&1; then
    local package_name rpm_java
    while read -r package_name; do
      [[ -n "${package_name}" ]] || continue
      rpm_java="$(rpm -ql "${package_name}" 2>/dev/null | awk '/\/bin\/java$/ {print; exit}')"
      if [[ -n "${rpm_java}" && -x "${rpm_java}" ]]; then
        printf '%s\n' "${rpm_java}"
        return 0
      fi
    done < <(rpm -qa | grep -Ei '(^|[-_.])(java|jdk|jre|dragonwell|corretto).*21|21.*(java|jdk|jre|dragonwell|corretto)' || true)
  fi
  return 1
}

java_major_version() {
  local java_bin="${1:-${JAVA_BIN}}" version_line
  [[ -n "${java_bin}" && -x "${java_bin}" ]] || return 1
  version_line="$(${java_bin} -version 2>&1 | head -n 1 || true)"
  if [[ "${version_line}" =~ version[[:space:]]+\"([0-9]+) ]] || [[ "${version_line}" =~ openjdk[[:space:]]+([0-9]+) ]]; then
    printf '%s\n' "${BASH_REMATCH[1]}"
  fi
}

install_java_from_package_manager() {
  if command -v dnf >/dev/null 2>&1; then
    dnf install -y java-21-openjdk-headless || dnf install -y java-21-openjdk || return 1
  elif command -v yum >/dev/null 2>&1; then
    yum install -y java-21-openjdk-headless || yum install -y java-21-openjdk || return 1
  elif command -v apt-get >/dev/null 2>&1; then
    apt-get update
    DEBIAN_FRONTEND=noninteractive apt-get install -y openjdk-21-jre-headless || return 1
  else
    return 1
  fi
}

install_java_from_adoptium() {
  local architecture archive_url temp_archive
  case "$(uname -m)" in
    x86_64|amd64) architecture="x64" ;;
    aarch64|arm64) architecture="aarch64" ;;
    *) echo "ERROR: Unsupported CPU architecture: $(uname -m)"; return 1 ;;
  esac

  if ! command -v curl >/dev/null 2>&1; then
    if command -v dnf >/dev/null 2>&1; then dnf install -y curl tar gzip
    elif command -v yum >/dev/null 2>&1; then yum install -y curl tar gzip
    elif command -v apt-get >/dev/null 2>&1; then apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y curl tar gzip
    fi
  fi

  archive_url="https://api.adoptium.net/v3/binary/latest/21/ga/linux/${architecture}/jre/hotspot/normal/eclipse"
  temp_archive="$(mktemp --suffix=.tar.gz)"
  echo "Java 21 is unavailable from the OS repository. Downloading Eclipse Temurin Java 21..."
  curl --fail --location --retry 3 --connect-timeout 15 --output "${temp_archive}" "${archive_url}"
  rm -rf /opt/java-21
  mkdir -p /opt/java-21
  tar -xzf "${temp_archive}" --strip-components=1 -C /opt/java-21
  rm -f "${temp_archive}"
  ln -sfn /opt/java-21/bin/java /usr/local/bin/java
}

JAVA_BIN="$(find_java_bin || true)"
current_java_major="$(java_major_version "${JAVA_BIN}" || true)"

if [[ ! "${current_java_major}" =~ ^[0-9]+$ ]] || (( current_java_major < 21 )); then
  echo "A usable Java 21 runtime was not found. Installing it now..."
  install_java_from_package_manager || install_java_from_adoptium
fi

JAVA_BIN="$(find_java_bin || true)"
current_java_major="$(java_major_version "${JAVA_BIN}" || true)"
if [[ ! "${current_java_major}" =~ ^[0-9]+$ ]] || (( current_java_major < 21 )); then
  echo "ERROR: Java 21 installation failed."
  exit 1
fi
JAVA_BIN="$(readlink -f "${JAVA_BIN}")"
echo "Java is ready: $(${JAVA_BIN} -version 2>&1 | head -n 1)"

install_redis() {
  if command -v redis-server >/dev/null 2>&1; then return 0; fi
  echo "Redis is not installed. Installing Redis..."
  if command -v dnf >/dev/null 2>&1; then dnf install -y redis
  elif command -v yum >/dev/null 2>&1; then yum install -y redis
  elif command -v apt-get >/dev/null 2>&1; then
    apt-get update
    DEBIAN_FRONTEND=noninteractive apt-get install -y redis-server
  else
    echo "ERROR: No supported package manager was found for Redis."
    return 1
  fi
}

install_redis
if systemctl list-unit-files redis.service >/dev/null 2>&1; then
  systemctl enable --now redis
elif systemctl list-unit-files redis-server.service >/dev/null 2>&1; then
  systemctl enable --now redis-server
else
  echo "ERROR: Redis systemd service was not found after installation."
  exit 1
fi
redis-cli ping | grep -q PONG || { echo "ERROR: Redis did not respond to PING."; exit 1; }
echo "Redis is ready on 127.0.0.1:6379."

if ! id "${APP_USER}" >/dev/null 2>&1; then
  useradd --system --home-dir "${DATA_DIR}" --create-home --shell /sbin/nologin "${APP_USER}"
fi

install -d -o root -g root -m 0755 "${APP_DIR}"
install -d -o "${APP_USER}" -g "${APP_USER}" -m 0700 "${DATA_DIR}" "${DATA_DIR}/.bot-ilink" "${DATA_DIR}/knowledge" "${DATA_DIR}/logs"
install -d -o root -g "${APP_USER}" -m 0750 "${ENV_DIR}"
install -o root -g root -m 0644 "${SOURCE_JAR}" "${APP_DIR}/${APP_NAME}.jar"

if [[ ! -s "${ENV_FILE}" && -s "${LEGACY_ENV_FILE}" ]]; then
  echo "Migrating the existing demo-assistant environment without printing secrets."
  install -o root -g "${APP_USER}" -m 0640 "${LEGACY_ENV_FILE}" "${ENV_FILE}"
  sed -i "s#/var/lib/demo-assistant#${DATA_DIR}#g" "${ENV_FILE}"
  if [[ -d "${LEGACY_DATA_DIR}" ]]; then
    cp -a "${LEGACY_DATA_DIR}/." "${DATA_DIR}/"
    chown -R "${APP_USER}:${APP_USER}" "${DATA_DIR}"
  fi
fi

if [[ ! -s "${ENV_FILE}" ]]; then
  echo
  echo "Enter the application settings. API Key input is hidden and is not saved in shell history."
  read -r -s -p "DASHSCOPE_API_KEY: " DASHSCOPE_API_KEY
  echo
  read -r -p "DASHSCOPE_BASE_URL [https://dashscope.aliyuncs.com/api/v1]: " DASHSCOPE_BASE_URL
  DASHSCOPE_BASE_URL="${DASHSCOPE_BASE_URL:-https://dashscope.aliyuncs.com/api/v1}"
  read -r -p "QWEATHER_API_HOST (your dedicated API Host): " QWEATHER_API_HOST
  read -r -s -p "QWEATHER_API_KEY: " QWEATHER_API_KEY
  echo
  read -r -s -p "ADMIN_API_TOKEN: " ADMIN_API_TOKEN
  echo

  if [[ -z "${DASHSCOPE_API_KEY}" || -z "${DASHSCOPE_BASE_URL}" || -z "${QWEATHER_API_HOST}" || -z "${QWEATHER_API_KEY}" || -z "${ADMIN_API_TOKEN}" ]]; then
    echo "ERROR: All five application settings are required."
    exit 1
  fi

  QWEATHER_API_HOST="${QWEATHER_API_HOST%/}"
  if [[ "${QWEATHER_API_HOST}" != http://* && "${QWEATHER_API_HOST}" != https://* ]]; then
    QWEATHER_API_HOST="https://${QWEATHER_API_HOST}"
  fi

  umask 0077
  {
    printf 'DASHSCOPE_API_KEY=%s\n' "${DASHSCOPE_API_KEY}"
    printf 'DASHSCOPE_BASE_URL=%s\n' "${DASHSCOPE_BASE_URL}"
    printf 'DASHSCOPE_MODEL=qwen3.7-plus\n'
    printf 'DASHSCOPE_IMAGE_MODEL=wan2.7-image-pro\n'
    printf 'DASHSCOPE_IMAGE_SIZE=2K\n'
    printf 'DASHSCOPE_IMAGE_EDIT_MODEL=wan2.7-image-pro\n'
    printf 'DASHSCOPE_IMAGE_EDIT_SIZE=2K\n'
    printf 'QWEATHER_API_HOST=%s\n' "${QWEATHER_API_HOST}"
    printf 'QWEATHER_API_KEY=%s\n' "${QWEATHER_API_KEY}"
    printf 'ADMIN_API_TOKEN=%s\n' "${ADMIN_API_TOKEN}"
    printf 'ILINK_CREDENTIALS_FILE=%s\n' "${DATA_DIR}/.bot-ilink/credentials.json"
    printf 'BILI_COOKIE=\n'
    printf 'BILI_ALLOWED_SESSION_IDS=\n'
    printf 'BILI_LOGIN_TIMEOUT_SECONDS=180\n'
    printf 'REDIS_URL=redis://127.0.0.1:6379/0\n'
    printf 'CONVERSATION_REDIS_ENABLED=true\n'
    printf 'CONVERSATION_TTL_MINUTES=1440\n'
    printf 'SQLITE_ENABLED=true\n'
    printf 'SQLITE_DATABASE_PATH=%s\n' "${DATA_DIR}/conversations.db"
    printf 'KNOWLEDGE_BASE_PATH=%s\n' "${DATA_DIR}/knowledge"
    printf 'RAG_ENABLED=true\n'
    printf 'RAG_RETRIEVAL_LIMIT=4\n'
    printf 'AGENT_STORAGE_FILE=%s\n' "${DATA_DIR}/agent-tasks.json"
    printf 'AGENT_DEFAULT_MAX_STEPS=12\n'
    printf 'AGENT_DEFAULT_TIMEOUT_SECONDS=600\n'
    printf 'REMINDERS_ENABLED=true\n'
    printf 'REMINDERS_POLL_MS=10000\n'
    printf 'RATE_LIMIT_ENABLED=true\n'
    printf 'RATE_LIMIT_PER_MINUTE=10\n'
    printf 'AUDIT_ENABLED=false\n'
  } > "${ENV_FILE}"
fi

# Existing installations keep their secrets and receive only safe defaults for
# newly introduced settings.
ensure_env_default() {
  local key="$1" value="$2"
  if ! grep -q "^${key}=" "${ENV_FILE}"; then
    printf '%s=%s\n' "${key}" "${value}" >> "${ENV_FILE}"
  fi
}
ensure_env_default "BILI_COOKIE" ""
ensure_env_default "BILI_ALLOWED_SESSION_IDS" ""
ensure_env_default "BILI_LOGIN_TIMEOUT_SECONDS" "180"

chown root:"${APP_USER}" "${ENV_FILE}"
chmod 0640 "${ENV_FILE}"
unset DASHSCOPE_API_KEY DASHSCOPE_BASE_URL QWEATHER_API_HOST QWEATHER_API_KEY ADMIN_API_TOKEN 2>/dev/null || true

cat > "${SERVICE_FILE}" <<EOF
[Unit]
Description=WeChat Agent Platform
Wants=network-online.target
After=network-online.target

[Service]
Type=simple
User=${APP_USER}
Group=${APP_USER}
WorkingDirectory=${DATA_DIR}
EnvironmentFile=${ENV_FILE}
ExecStart=${JAVA_BIN} -jar ${APP_DIR}/${APP_NAME}.jar --assistant.ilink-enabled=true --server.address=127.0.0.1 --server.port=8080
Restart=on-failure
RestartSec=30
TimeoutStopSec=30
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=${DATA_DIR}

[Install]
WantedBy=multi-user.target
EOF

chmod 0644 "${SERVICE_FILE}"
systemctl daemon-reload
systemctl enable "${APP_NAME}" >/dev/null

legacy_was_active=false
if systemctl is-active --quiet demo-assistant.service; then
  legacy_was_active=true
  echo "Stopping the legacy demo-assistant service for the port migration."
  systemctl stop demo-assistant.service
fi

systemctl restart "${APP_NAME}"

service_ready=false
for _ in $(seq 1 60); do
  if curl --fail --silent --show-error --max-time 2 \
    "http://127.0.0.1:8080/actuator/health" 2>/dev/null |
      grep -q '"status"[[:space:]]*:[[:space:]]*"UP"'; then
    service_ready=true
    break
  fi
  sleep 1
done

if [[ "${service_ready}" != true ]]; then
  echo "ERROR: ${APP_NAME} did not become healthy. Restoring the legacy service." >&2
  systemctl stop "${APP_NAME}" || true
  if [[ "${legacy_was_active}" == true ]]; then
    systemctl restart demo-assistant.service
  fi
  journalctl -u "${APP_NAME}" -n 100 --no-pager >&2 || true
  exit 1
fi

if [[ "${legacy_was_active}" == true ]]; then
  systemctl disable demo-assistant.service >/dev/null
fi

echo
echo "Deployment completed. The service is running in the background and enabled at boot."
echo "On first login, scan the QR code with WeChat."
echo
systemctl --no-pager --full status "${APP_NAME}" || true
if [[ "${FOLLOW_LOGS:-true}" == true ]]; then
  echo
  echo "Live logs follow. After login succeeds, press Ctrl+C to leave the logs."
  journalctl -u "${APP_NAME}" -n 50 -f
fi
