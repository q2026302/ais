#!/bin/bash
# ============================================================
# deploy.sh — 在目标服务器上直接拉取 GitHub Actions artifact 部署
# ============================================================
# 用法: 在目标服务器上运行
#   ./deploy.sh <run-id>
#
# 需要: GITHUB_TOKEN 环境变量（GitHub App installation token 或 PAT）
#       有 actions:read 权限
#
# 示例:
#   GITHUB_TOKEN=ghs_xxx ./deploy.sh 29904037211
# ============================================================

set -euo pipefail

RUN_ID="${1:?Usage: $0 <github-run-id>}"
BIN_DIR="${AIS_BIN_DIR:-/home/quanz/ais/bin}"
SERVICE="${AIS_SERVICE:-ai-image-studio}"
REPO="q2026302/ais"
TOKEN="${GITHUB_TOKEN:?GITHUB_TOKEN is required}"

echo "=== Deploy build #${RUN_ID} ==="

# 1. 获取 Native Image artifact ID
echo "[1/5] Fetching artifact info..."
ARTIFACT_JSON=$(curl -sL -H "Authorization: Bearer ${TOKEN}" \
  "https://api.github.com/repos/${REPO}/actions/runs/${RUN_ID}/artifacts")
ARTIFACT_ID=$(echo "${ARTIFACT_JSON}" | python3 -c "
import sys,json
data=json.load(sys.stdin)
for a in data.get('artifacts',[]):
    if a['name'] == 'ais-native':
        print(a['id'])
        break
")
if [ -z "${ARTIFACT_ID}" ]; then
  echo "ERROR: No ais-native artifact found in run ${RUN_ID}" >&2
  exit 1
fi
echo "  Artifact ID: ${ARTIFACT_ID}"

# 2. 下载 artifact（处理 GitHub → Azure Blob 重定向）
echo "[2/5] Downloading native image (81MB)..."
TMPDIR=$(mktemp -d /tmp/ais-deploy-XXXXXX)
python3 << PYEOF
import json, urllib.request, os

token = """${TOKEN}"""
artifact_id = """${ARTIFACT_ID}"""
repo = """${REPO}"""
tmpdir = """${TMPDIR}"""

class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

opener = urllib.request.build_opener(NoRedirectHandler)
req = urllib.request.Request(
    f"https://api.github.com/repos/{repo}/actions/artifacts/{artifact_id}/zip",
    headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json"}
)
resp = opener.open(req)
if resp.status == 302:
    signed_url = resp.headers.get('location')
    zip_path = os.path.join(tmpdir, 'artifact.zip')
    with urllib.request.urlopen(signed_url) as dl:
        with open(zip_path, 'wb') as f:
            f.write(dl.read())
    import zipfile
    with zipfile.ZipFile(zip_path, 'r') as zf:
        zf.extractall(tmpdir)
    print(f"Downloaded and extracted to {tmpdir}")
else:
    print(f"Unexpected status: {resp.status}")
PYEOF

# 3. 备份旧二进制
echo "[3/5] Backing up old binary..."
if [ -f "${BIN_DIR}/ais" ]; then
  cp "${BIN_DIR}/ais" "${BIN_DIR}/ais.build-${RUN_ID}.bak"
  echo "  Backup: ${BIN_DIR}/ais.build-${RUN_ID}.bak"
fi

# 4. 替换二进制
echo "[4/5] Replacing binary..."
cp "${TMPDIR}/ais" "${BIN_DIR}/ais.new"
mv "${BIN_DIR}/ais.new" "${BIN_DIR}/ais"
chmod +x "${BIN_DIR}/ais"
ls -lh "${BIN_DIR}/ais"

# 5. 重启服务
echo "[5/5] Restarting service..."
systemctl --user reset-failed "${SERVICE}" 2>/dev/null || true
systemctl --user restart "${SERVICE}"
sleep 3
if systemctl --user is-active --quiet "${SERVICE}"; then
  echo "✅ Service ${SERVICE} is active"
  # 验证版本 API
  curl -sk "http://127.0.0.1:11111/ais/api/version" 2>/dev/null | python3 -m json.tool || true
else
  echo "❌ Service ${SERVICE} failed to start"
  systemctl --user status "${SERVICE}" 2>&1 | head -20
  exit 1
fi

# 清理
rm -rf "${TMPDIR}"
echo "=== Deploy complete ==="
