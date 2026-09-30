#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Jofi contributors
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# The full-stack e2e environment (issue #21, ADR-0036): compose.yaml + compose.e2e.yaml with the `e2e`
# profile, i.e. app, worker and db on an internal network without internet, the fake AI provider,
# WireMock, the seed, and `edge` publishing the app on 127.0.0.1:${JOFI_E2E_PORT:-8180}.
#
# Usage (from anywhere; `pnpm e2e` in frontend/ runs `test`):
#   scripts/e2e-stack.sh test [playwright args]   build, start, seed, check, run Playwright, tear down
#   scripts/e2e-stack.sh up                       build, start, seed and check; leaves the stack running
#   scripts/e2e-stack.sh seed | check | logs | down
#
# Env: SKIP_BUILD=1 reuses localhost/jofi:local; E2E_KEEP_STACK=1 keeps the stack after `test`;
# E2E_REUSE_STACK=1 skips build and start when the stack already answers (and then leaves it running); COMPOSE / CONTAINER as in
# scripts/compose-smoke-test.sh (e.g. COMPOSE=podman-compose CONTAINER=podman).
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repo_root}"

read -r -a compose_command <<<"${COMPOSE:-docker compose}"
project="jofi-e2e"
export JOFI_E2E_PORT="${JOFI_E2E_PORT:-8180}"
base_url="http://127.0.0.1:${JOFI_E2E_PORT}"
timeout_seconds="${E2E_TIMEOUT_SECONDS:-300}"
seed_provider="00000000-0000-4000-8000-0000000e2e01"
log_file="${repo_root}/frontend/test-results/e2e-stack.log"
reused_stack=0

# The e2e stack never uses the values of a local .env: a throwaway database password, and loopback
# binding so first run needs no setup token (#16).
export JOFI_DB_PASSWORD="jofi-e2e-throwaway-database-password"
export JOFI_DB_USERNAME="jofi"
export JOFI_BIND_ADDRESS="127.0.0.1"

compose() {
  "${compose_command[@]}" --project-name "${project}" -f compose.yaml -f compose.e2e.yaml --profile e2e "$@"
}
fail() { echo "FAIL: $*" >&2; exit 1; }
pass() { echo "ok: $*"; }

app_is_up() {
  curl --silent --max-time 3 "${base_url}/actuator/health" 2>/dev/null | grep -q '"status":"UP"'
}

up() {
  if [[ "${E2E_REUSE_STACK:-0}" == "1" ]] && app_is_up; then
    pass "reusing the running e2e stack at ${base_url}"
    # Started elsewhere (`pnpm e2e:up`): `test` leaves it running.
    reused_stack=1
  else
    [[ "${SKIP_BUILD:-0}" == "1" ]] || compose build app
    # The seed is not started here; it runs below, once Flyway has migrated.
    compose up -d app worker db fake-ai wiremock edge
    echo "Waiting up to ${timeout_seconds}s for ${base_url}/actuator/health..."
    local deadline=$((SECONDS + timeout_seconds))
    until app_is_up; do
      ((SECONDS < deadline)) || fail "the e2e stack did not become healthy within ${timeout_seconds}s"
      sleep 2
    done
    pass "app is healthy behind edge at ${base_url}"
  fi
  seed
  check
}

seed() {
  compose run --rm seed
  pass "seed applied"
}

sql() {
  compose exec -T db psql --no-psqlrc --username "${JOFI_DB_USERNAME}" --dbname jofi --tuples-only --no-align \
    --command "$1" | tr -d '[:space:]'
}

# Assertions on the running stack: the seed is complete and idempotent, the fake AI is reachable from
# the app, and nothing on the internal network reaches the internet.
check() {
  seed >/dev/null
  local assignments changelog
  assignments="$(sql "SELECT count(*) FROM ai_model_assignment WHERE provider_id = '${seed_provider}'")"
  [[ "${assignments}" == "9" ]] || fail "expected 9 seeded model assignments, found ${assignments}"
  changelog="$(sql "SELECT count(*) FROM changelog_entry WHERE actor_name = 'e2e-seed'")"
  [[ "${changelog}" == "1" ]] || fail "seeding twice must log the provider once, found ${changelog} entries"
  pass "seed is complete and idempotent (9 model assignments, 1 changelog entry)"

  compose exec -T app bash -c 'exec 3<>/dev/tcp/fake-ai/8080' || fail "app cannot reach fake-ai:8080"
  pass "app reaches the fake AI provider"

  # A public address, no DNS needed: the internal network has no route to it.
  if compose exec -T app timeout 5 bash -c 'exec 3<>/dev/tcp/1.1.1.1/443' 2>/dev/null; then
    fail "app reaches the internet; the e2e network must be internal"
  fi
  if compose exec -T fake-ai node -e \
    "require('node:net').connect(443, '1.1.1.1').on('connect', () => process.exit(0)).on('error', () => process.exit(1)); setTimeout(() => process.exit(1), 5000)" \
    2>/dev/null; then
    fail "fake-ai reaches the internet; the e2e network must be internal"
  fi
  pass "app and fake-ai have no internet access"
}

logs() {
  mkdir -p "$(dirname "${log_file}")"
  compose logs --no-color >"${log_file}" 2>&1 || true
  echo "Stack logs: ${log_file}"
}

down() {
  compose down --volumes --remove-orphans
}

run_tests() {
  finish() {
    local status=$?
    if [[ ${status} -ne 0 ]]; then
      compose ps >&2 || true
      logs
    fi
    if [[ "${E2E_KEEP_STACK:-0}" != "1" && "${reused_stack}" != "1" ]]; then
      down >/dev/null 2>&1 || true
    fi
    exit "${status}"
  }
  trap finish EXIT
  up
  (cd frontend && JOFI_E2E_BASE_URL="${base_url}" pnpm exec playwright test "$@")
}

command="${1:-}"
shift || true
case "${command}" in
  test) run_tests "$@" ;;
  up) up ;;
  seed) seed ;;
  check) check ;;
  logs) logs ;;
  down) down ;;
  *) fail "usage: $0 test [playwright args] | up | seed | check | logs | down" ;;
esac
