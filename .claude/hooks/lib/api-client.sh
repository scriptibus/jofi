# shellcheck shell=bash
# SPDX-FileCopyrightText: 2026 Jofi contributors
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Sourced by affected-tests.sh. The orval client in frontend/src/api/generated/ is git-ignored
# (ADR-0033), so a fresh checkout has none and Vitest cannot resolve its imports.
# Tests: .claude/hooks/lib/api-client.test.sh

# api_client_stale <frontend-dir>: succeeds when `pnpm api` has to run, i.e. a generated file is
# missing or older than the spec or the orval config. Compares timestamps only, so an up-to-date
# client costs no process start.
api_client_stale() {
  local frontend="$1" generated input
  for generated in "$frontend/src/api/generated/jofi.ts" "$frontend/src/api/generated/jofi.zod.ts"; do
    [[ -f "$generated" ]] || return 0
    for input in "$frontend/../api/openapi.json" "$frontend/orval.config.ts"; do
      [[ "$input" -nt "$generated" ]] && return 0
    done
  done
  return 1
}

# api_client_mark_fresh <frontend-dir>: orval leaves a file untouched when its output did not
# change, so after a spec edit without effect the client would stay "stale" and regenerate on
# every run. Call after a successful `pnpm api`.
api_client_mark_fresh() {
  touch "$1/src/api/generated/jofi.ts" "$1/src/api/generated/jofi.zod.ts"
}
