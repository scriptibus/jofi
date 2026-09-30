#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Jofi contributors
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Smoke test for compose.yaml (issue #15, run by CI): starts the stack, waits for the app to be
# healthy, then checks the published API, localhost-only port bindings and non-root, read-only
# containers. Tears everything down (including volumes) on exit.
#
# Usage (repository root, with a .env, e.g. `cp .env.example .env`):
#   scripts/compose-smoke-test.sh            # docker compose
#   COMPOSE="podman-compose" CONTAINER=podman scripts/compose-smoke-test.sh
# Set SKIP_BUILD=1 to reuse an already built localhost/jofi:local image.
set -euo pipefail

read -r -a compose <<<"${COMPOSE:-docker compose}"
container="${CONTAINER:-docker}"
project="jofi-smoke"
port="${JOFI_PORT:-8080}"
base_url="http://127.0.0.1:${port}"
timeout_seconds="${SMOKE_TIMEOUT_SECONDS:-240}"

compose() { "${compose[@]}" --project-name "${project}" "$@"; }
fail() { echo "FAIL: $*" >&2; exit 1; }
pass() { echo "ok: $*"; }

cleanup() {
  local status=$?
  if [[ ${status} -ne 0 ]]; then
    echo "--- compose ps / logs (failure) ---" >&2
    compose ps >&2 || true
    compose logs --no-color >&2 || true
  fi
  compose down --volumes --remove-orphans >/dev/null 2>&1 || true
  exit "${status}"
}
trap cleanup EXIT

if [[ "${SKIP_BUILD:-0}" != "1" ]]; then
  compose build
fi
compose up -d

# Container lookup by compose labels: works for Docker Compose and podman-compose alike.
container_id() {
  "${container}" ps --quiet \
    --filter "label=com.docker.compose.project=${project}" \
    --filter "label=com.docker.compose.service=$1" | head -n 1
}

echo "Waiting up to ${timeout_seconds}s for the app to report healthy..."
deadline=$((SECONDS + timeout_seconds))
until app_id="$(container_id app)" && [[ -n "${app_id}" ]] \
  && [[ "$("${container}" inspect --format '{{.State.Health.Status}}' "${app_id}")" == "healthy" ]]; do
  ((SECONDS < deadline)) || fail "app did not become healthy within ${timeout_seconds}s"
  sleep 3
done
pass "app container is healthy"

status="$(curl --silent --output /dev/null --write-out '%{http_code}' "${base_url}/api/auth/session")"
[[ "${status}" == "200" ]] || fail "GET /api/auth/session returned ${status}, expected 200"
pass "GET ${base_url}/api/auth/session returns 200"

# Every other API call needs a login session (ADR-0035).
status="$(curl --silent --output /dev/null --write-out '%{http_code}' "${base_url}/api/system/info")"
[[ "${status}" == "401" ]] || fail "GET /api/system/info without a session returned ${status}, expected 401"
pass "GET ${base_url}/api/system/info without a session returns 401"

status="$(curl --silent --output /dev/null --write-out '%{http_code}' "${base_url}/")"
[[ "${status}" == "200" ]] || fail "GET / (frontend) returned ${status}, expected 200"
pass "GET ${base_url}/ serves the frontend"

for service in app worker db; do
  id="$(container_id "${service}")"
  [[ -n "${id}" ]] || fail "no running container for service ${service}"

  # Every published port must be bound to 127.0.0.1; only the app publishes one.
  host_ips="$("${container}" inspect --format '{{json .NetworkSettings.Ports}}' "${id}" \
    | jq -r '[(. // {})[] | (. // [])[] | .HostIp] | unique | join(",")')"
  if [[ "${service}" == "app" ]]; then
    [[ "${host_ips}" == "127.0.0.1" ]] || fail "app publishes on '${host_ips}', expected only 127.0.0.1"
  else
    [[ -z "${host_ips}" ]] || fail "${service} publishes ports on '${host_ips}', expected none"
  fi

  uid="$(compose exec -T "${service}" id -u | tr -d '[:space:]')"
  [[ "${uid}" =~ ^[0-9]+$ && "${uid}" != "0" ]] || fail "${service} runs as uid '${uid}', expected non-root"

  read_only="$("${container}" inspect --format '{{.HostConfig.ReadonlyRootfs}}' "${id}")"
  [[ "${read_only}" == "true" ]] || fail "${service} root filesystem is not read-only"

  pass "${service}: ports [${host_ips:-none}], uid ${uid}, read-only root filesystem"
done

# Not reachable through a non-loopback address of this host.
external_ip="$(hostname -I 2>/dev/null | tr ' ' '\n' | grep -E '^[0-9]+(\.[0-9]+){3}$' | grep -v '^127\.' | head -n 1 || true)"
if [[ -n "${external_ip}" ]]; then
  if curl --silent --max-time 3 --output /dev/null "http://${external_ip}:${port}/api/system/info"; then
    fail "app is reachable on ${external_ip}:${port}; it must listen on localhost only"
  fi
  pass "app is not reachable on ${external_ip}:${port}"
fi

echo "Compose smoke test passed."
