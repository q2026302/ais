#!/usr/bin/env bash

set -euo pipefail

# Deployment layout: keep the executable and its shared libraries in bin/.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NATIVE_FILE="${AIS_BINARY:-$SCRIPT_DIR/bin/ais}"
if [[ "$NATIVE_FILE" != /* ]]; then
  NATIVE_FILE="$SCRIPT_DIR/$NATIVE_FILE"
fi
if [[ -e "$NATIVE_FILE" ]]; then
  NATIVE_FILE="$(cd "$(dirname "$NATIVE_FILE")" && pwd)/$(basename "$NATIVE_FILE")"
fi
SERVICE_NAME="ais.service"
UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
UNIT_FILE="$UNIT_DIR/$SERVICE_NAME"

usage() {
  cat <<'USAGE'
用法：
  ./install-user-service.sh       注册并立即启动当前用户的 AIS 服务
  ./install-user-service.sh -h    显示帮助

说明：
  默认假定本脚本目录下 bin/ 中存在 GraalVM Native Image 可执行文件 ais。
  也可以通过 AIS_BINARY 指定其他本地文件路径。
  配置、日志、数据库和上传文件默认全部放在该目录下的 config/、logs/、data/。
USAGE
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

if [[ $# -ne 0 ]]; then
  echo "未知参数：$1" >&2
  usage >&2
  exit 2
fi

if ! command -v systemctl >/dev/null 2>&1; then
  echo "未找到 systemctl；当前系统可能不支持 systemd。" >&2
  exit 1
fi

if [[ ! -f "$NATIVE_FILE" || ! -x "$NATIVE_FILE" ]]; then
  echo "未找到可执行的 Native Image：$NATIVE_FILE" >&2
  echo "请将编译产物复制为本脚本目录下 bin/ais，或设置 AIS_BINARY。" >&2
  exit 1
fi

NATIVE_DIR="$(dirname "$NATIVE_FILE")"
if [[ "$(basename "$NATIVE_DIR")" == "bin" ]]; then
  BASE_DIR="$(cd "$(dirname "$NATIVE_DIR")" && pwd)"
else
  BASE_DIR="$(cd "$NATIVE_DIR" && pwd)"
fi
CONFIG_DIR="$BASE_DIR/config"
DATA_DIR="$BASE_DIR/data"
LOG_DIR="$BASE_DIR/logs"
UPLOAD_DIR="$DATA_DIR/uploads"
ENV_FILE="$CONFIG_DIR/application.env"

mkdir -p "$UNIT_DIR" "$CONFIG_DIR" "$DATA_DIR" "$LOG_DIR" "$UPLOAD_DIR" "$UPLOAD_DIR/attachments"

if [[ ! -f "$ENV_FILE" ]]; then
  cat >"$ENV_FILE" <<EOF_ENV
# AIS 用户级服务环境变量
# 修改后执行：systemctl --user restart $SERVICE_NAME
APP_BASE_DIR=$BASE_DIR
APP_CONFIG_DIR=$CONFIG_DIR
APP_DATA_DIR=$DATA_DIR
APP_LOG_DIR=$LOG_DIR
APP_UPLOAD_DIR=$UPLOAD_DIR
APP_SQLITE_PATH=$DATA_DIR/ais.db
APP_LOG_FILE=$LOG_DIR/application.log
APP_CONTEXT_PATH=/ais
SERVER_PORT=11111
SPRING_CONFIG_ADDITIONAL_LOCATION=optional:file:$CONFIG_DIR/
# APP_SECURITY_ENABLED=true
# APP_TOKEN_SECRET=请替换为随机的长字符串
# APP_INITIAL_ADMIN_USERNAME=admin
# APP_INITIAL_ADMIN_PASSWORD_MD5=请填写 32 位 MD5 摘要
EOF_ENV
  chmod 600 "$ENV_FILE"
  echo "已创建配置文件：$ENV_FILE"
else
  echo "保留已有配置文件：$ENV_FILE"
fi

cat >"$UNIT_FILE" <<EOF_UNIT
[Unit]
Description=AIS service
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=$BASE_DIR
EnvironmentFile=$ENV_FILE
ExecStart=$NATIVE_FILE -R:MaxHeapSize=128m -R:GC=serial
Restart=on-failure
RestartSec=5s
TimeoutStopSec=20s
KillSignal=SIGTERM
NoNewPrivileges=true
PrivateTmp=true
UMask=0027

[Install]
WantedBy=default.target
EOF_UNIT

chmod 644 "$UNIT_FILE"
systemctl --user daemon-reload
systemctl --user enable --now "$SERVICE_NAME"

echo "已注册并启动用户级服务：$SERVICE_NAME"
echo "基础目录：$BASE_DIR"
echo "配置文件：$ENV_FILE"
echo "服务状态：systemctl --user status $SERVICE_NAME"
echo "查看日志：journalctl --user -u $SERVICE_NAME -f"
echo "访问地址：http://127.0.0.1:11111/ais/"
