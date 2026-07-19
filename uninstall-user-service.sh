#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NATIVE_FILE="${AIS_BINARY:-$SCRIPT_DIR/bin/ais}"
if [[ "$NATIVE_FILE" != /* ]]; then
  NATIVE_FILE="$SCRIPT_DIR/$NATIVE_FILE"
fi
if [[ -e "$NATIVE_FILE" ]]; then
  NATIVE_FILE="$(cd "$(dirname "$NATIVE_FILE")" && pwd)/$(basename "$NATIVE_FILE")"
fi
NATIVE_DIR="$(dirname "$NATIVE_FILE")"
if [[ "$(basename "$NATIVE_DIR")" == "bin" ]]; then
  BASE_DIR="$(cd "$(dirname "$NATIVE_DIR")" && pwd)"
else
  BASE_DIR="$(cd "$NATIVE_DIR" && pwd)"
fi
SERVICE_NAME="ais.service"
UNIT_FILE="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user/$SERVICE_NAME"
ENV_FILE="$BASE_DIR/config/application.env"

usage() {
  cat <<'USAGE'
用法：
  ./uninstall-user-service.sh       停止、注销并删除用户级服务
  ./uninstall-user-service.sh -h    显示帮助

说明：
  卸载只删除 systemd 用户服务注册，不删除 Native Image 基础目录下的 config/、data/ 或 logs/。
  如果安装时设置了 AIS_BINARY，卸载时请使用相同设置。
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

systemctl --user disable --now "$SERVICE_NAME" >/dev/null 2>&1 || true
rm -f "$UNIT_FILE"
systemctl --user daemon-reload
systemctl --user reset-failed "$SERVICE_NAME" >/dev/null 2>&1 || true

echo "已卸载用户级服务：$SERVICE_NAME"
if [[ -f "$ENV_FILE" ]]; then
  echo "已保留应用配置：$ENV_FILE"
fi
echo "应用配置、日志、数据库和上传文件均未删除：$BASE_DIR/{config,data,logs}"
