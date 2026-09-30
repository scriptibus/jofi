#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Jofi contributors
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Stop hook: before the agent finishes, runs the tests of every module it changed plus the
# architecture tests. Frontend tests first (re)generate the git-ignored API client when it is
# missing or out of date (lib/api-client.sh). Exit 2 keeps the agent working and shows it the failure.
# Results are cached per diff, so a turn without new changes costs nothing.
set -uo pipefail

root="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel)}"
cd "$root" || exit 0

base="$(git merge-base HEAD origin/main 2>/dev/null || git rev-parse HEAD)"
changed="$( { git diff --name-only "$base"; git ls-files --others --exclude-standard; } | sort -u)"
[[ -z "$changed" ]] && exit 0

cache_dir="$root/.claude/.cache"
mkdir -p "$cache_dir"
stamp="$( { git diff "$base"; git ls-files --others --exclude-standard | xargs -r cat 2>/dev/null; } | sha256sum | cut -d' ' -f1)"
[[ -f "$cache_dir/affected-tests.ok" && "$(cat "$cache_dir/affected-tests.ok")" == "$stamp" ]] && exit 0

failures=""

if [[ -x backend/gradlew ]] && grep -q '^backend/' <<<"$changed"; then
  tasks=(":architecture-tests:test")
  while read -r path; do
    dir="$(dirname "${path#backend/}")"
    while [[ "$dir" != "." && "$dir" != "/" ]]; do
      if [[ -f "backend/$dir/build.gradle.kts" ]]; then
        tasks+=(":${dir//\//:}:test")
        break
      fi
      dir="$(dirname "$dir")"
    done
  done < <(grep '^backend/' <<<"$changed")
  mapfile -t tasks < <(printf '%s\n' "${tasks[@]}" | sort -u)
  if ! out="$(cd backend && ./gradlew -q "${tasks[@]}" 2>&1)"; then
    failures+=$'\n'"Backend tests failed (${tasks[*]}):"$'\n'"$(tail -n 80 <<<"$out")"
  fi
fi

if [[ -d frontend/node_modules ]] && grep -q '^frontend/' <<<"$changed"; then
  # shellcheck source=lib/api-client.sh
  source "$root/.claude/hooks/lib/api-client.sh"
  if api_client_stale frontend && ! out="$(cd frontend && pnpm api 2>&1 && api_client_mark_fresh .)"; then
    failures+=$'\n'"Generating the API client (pnpm api) failed:"$'\n'"$(tail -n 80 <<<"$out")"
  elif ! out="$(cd frontend && pnpm exec vitest related --run --passWithNoTests $(grep '^frontend/src/' <<<"$changed" | sed 's#^frontend/##') 2>&1)"; then
    failures+=$'\n'"Frontend tests failed:"$'\n'"$(tail -n 80 <<<"$out")"
  fi
fi

if [[ -n "$failures" ]]; then
  echo "Tests for the modules you changed are failing. Fix them before finishing.$failures" >&2
  exit 2
fi
echo "$stamp" >"$cache_dir/affected-tests.ok"
exit 0
