#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$ROOT_DIR/ais-api"
OUTPUT_DIR="$BACKEND_DIR/build/native/nativeCompile"

usage() {
  cat <<'USAGE'
用法：
  ./package.sh              构建 GraalVM Native Image 本地应用
  ./package.sh --jar        构建 Spring Boot 可执行 Jar（兼容 JVM 部署）
  ./package.sh --help       显示帮助

Native Image 构建要求：
  - GraalVM JDK，且 native-image 命令在 PATH 中
  - Node.js 与 Yarn（用于构建 Vue 前端）
USAGE
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

if ! command -v yarn >/dev/null 2>&1; then
  echo "未找到 yarn，请先安装 Node.js 和 Yarn。" >&2
  exit 1
fi

if [[ ! -x "$ROOT_DIR/ais-web/node_modules/.bin/vite" ]]; then
  echo "正在安装前端依赖..."
  yarn --cwd "$ROOT_DIR/ais-web" install --frozen-lockfile
fi

if [[ "${1:-}" == "--jar" ]]; then
  shift
  echo "正在构建前后端可执行 Jar..."
  (
    cd "$BACKEND_DIR"
    ./gradlew bootJar "$@"
  )

  JAR_FILE="$(find "$BACKEND_DIR/build/libs" -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' -print -quit)"
  if [[ -z "$JAR_FILE" ]]; then
    echo "未找到 Jar 打包产物。" >&2
    exit 1
  fi

  echo "打包完成：$JAR_FILE"
  echo "启动命令：java -jar \"$JAR_FILE\""
  exit 0
fi

if ! command -v native-image >/dev/null 2>&1; then
  echo "未找到 native-image，请使用 GraalVM JDK 并将 native-image 加入 PATH。" >&2
  exit 1
fi

if [[ "${1:-}" == "--" ]]; then
  shift
fi

echo "正在构建 GraalVM Native Image 本地应用..."
(
  cd "$BACKEND_DIR"
  ./gradlew nativeCompile "$@"
)

NATIVE_FILE="$OUTPUT_DIR/ais"
if [[ ! -x "$NATIVE_FILE" ]]; then
  echo "未找到 Native Image 打包产物：$NATIVE_FILE" >&2
  exit 1
fi

chmod +x "$NATIVE_FILE"
echo "打包完成：$NATIVE_FILE"
echo "启动命令：\"$NATIVE_FILE\""
