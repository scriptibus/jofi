#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Jofi contributors
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Tests for api_client_stale and api_client_mark_fresh (api-client.sh) on a throwaway frontend layout with fixed mtimes.
# Run: .claude/hooks/lib/api-client.test.sh
set -euo pipefail

# shellcheck source=api-client.sh
source "$(cd "$(dirname "$0")" && pwd)/api-client.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

passed=0
failures=0

# setup: spec and config at T0, both generated files at T1 (up to date).
setup() {
  rm -rf "$work/repo"
  mkdir -p "$work/repo/api" "$work/repo/frontend/src/api/generated"
  touch -d '2026-01-01 00:00' "$work/repo/api/openapi.json" "$work/repo/frontend/orval.config.ts"
  touch -d '2026-01-02 00:00' "$work/repo/frontend/src/api/generated/jofi.ts" \
    "$work/repo/frontend/src/api/generated/jofi.zod.ts"
}

# expect <stale|fresh> <name>
expect() {
  local actual=fresh
  api_client_stale "$work/repo/frontend" && actual=stale
  if [[ "$actual" == "$1" ]]; then
    passed=$((passed + 1))
  else
    failures=$((failures + 1))
    echo "FAIL: $2: expected $1, got $actual" >&2
  fi
}

setup
expect fresh "generated files newer than spec and config"

setup
rm -rf "$work/repo/frontend/src/api/generated"
expect stale "generated directory missing (fresh checkout)"

setup
rm "$work/repo/frontend/src/api/generated/jofi.zod.ts"
expect stale "one generated file missing"

setup
touch -d '2026-01-03 00:00' "$work/repo/api/openapi.json"
expect stale "spec newer than the generated client"

setup
touch -d '2026-01-03 00:00' "$work/repo/frontend/orval.config.ts"
expect stale "orval config newer than the generated client"

setup
touch -d '2026-01-03 00:00' "$work/repo/frontend/src/api/generated/jofi.ts"
expect fresh "only one generated file touched later"

setup
touch -d '2026-01-03 00:00' "$work/repo/api/openapi.json"
api_client_mark_fresh "$work/repo/frontend"
expect fresh "spec newer, but orval kept identical output and the hook marked it fresh"

echo "api-client.test.sh: $passed passed, $failures failed"
((failures == 0))
