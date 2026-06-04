#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
REMOTE_HOST="${DEPLOY_HOST:-tencent}"
REMOTE_APP_DIR="${REMOTE_APP_DIR:-/opt/spam-db-api/app}"
REMOTE_DEPLOY_DIR="${REMOTE_APP_DIR}/deploy"
ARCHIVE_PATH="${TMPDIR:-/tmp}/spam-db-api-deploy.tgz"

echo "[deploy] packing project"
tar --exclude='./target' \
    --exclude='./apps/android/.gradle' \
    --exclude='./apps/android/build' \
    --exclude='./apps/android/app/build' \
    --exclude='./data' \
    -C "${ROOT_DIR}" \
    -czf "${ARCHIVE_PATH}" \
    .cargo Cargo.toml Cargo.lock deploy services/spam-db-api

echo "[deploy] ensuring remote directories"
ssh "${REMOTE_HOST}" "mkdir -p '${REMOTE_APP_DIR}' '${REMOTE_DEPLOY_DIR}'"

echo "[deploy] uploading archive"
scp "${ARCHIVE_PATH}" "${REMOTE_HOST}:/tmp/spam-db-api-deploy.tgz"

echo "[deploy] extracting archive on remote host"
ssh "${REMOTE_HOST}" "tar -xzf /tmp/spam-db-api-deploy.tgz -C '${REMOTE_APP_DIR}'"

echo "[deploy] verifying remote env file"
ssh "${REMOTE_HOST}" "test -f '${REMOTE_DEPLOY_DIR}/.env'"

echo "[deploy] rebuilding container"
ssh "${REMOTE_HOST}" "cd '${REMOTE_DEPLOY_DIR}' && docker-compose build --no-cache spam-db-api && docker-compose up -d --force-recreate spam-db-api"

echo "[deploy] waiting for health"
ssh "${REMOTE_HOST}" "curl -fsS http://127.0.0.1:8080/health"

echo "[deploy] checking admin console signature"
ssh "${REMOTE_HOST}" "bash -lc '
set -euo pipefail
set -a
. \"${REMOTE_DEPLOY_DIR}/.env\"
set +a
token=\$(printf \"%s\" \"\${ADMIN_PASSWORD}\" | sha256sum | awk \"{print \\\$1}\")
curl -fsS -H \"Cookie: spam_admin_session=\${token}\" http://127.0.0.1:8080/admin | grep -q \"data-list\"
'"

echo "[deploy] done"
