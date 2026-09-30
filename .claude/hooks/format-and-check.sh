#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Jofi contributors
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# PostToolUse hook (Edit|Write): formats and lints the file that was just edited.
# Exit 2 sends the tool output back to the agent as feedback; exit 0 stays silent.
set -uo pipefail

root="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel)}"
file="$(jq -r '.tool_input.file_path // empty')"
[[ -z "$file" || ! -f "$file" ]] && exit 0
rel="$(realpath --relative-to="$root" "$file")"

fail() {
  echo "$1" >&2
  echo "$2" | tail -n 60 >&2
  exit 2
}

case "$rel" in
  backend/*.kt | backend/*.kts)
    [[ -x "$root/backend/gradlew" ]] || exit 0
    # Gradle project path of the edited file, e.g. backend/adapters/web/src/... -> :adapters:web
    dir="$(dirname "${rel#backend/}")"
    project=""
    while [[ "$dir" != "." && "$dir" != "/" ]]; do
      if [[ -f "$root/backend/$dir/build.gradle.kts" ]]; then
        project=":${dir//\//:}"
        break
      fi
      dir="$(dirname "$dir")"
    done
    [[ -z "$project" ]] && exit 0
    out="$(cd "$root/backend" && ./gradlew -q "$project:spotlessApply" "$project:detekt" 2>&1)" ||
      fail "Formatting/detekt failed for $project after editing $rel:" "$out"
    ;;
  frontend/*.ts | frontend/*.tsx | frontend/*.js | frontend/*.jsx | frontend/*.css | frontend/*.json)
    [[ -d "$root/frontend/node_modules" ]] || exit 0
    out="$(cd "$root/frontend" && pnpm exec biome check --write "${rel#frontend/}" 2>&1)" ||
      fail "Biome found problems in $rel:" "$out"
    ;;
esac
exit 0
