#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Jofi contributors
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Tests for migration-lock.sh against a stubbed `gh` that serves canned API responses (needs jq).
# Run: .github/scripts/migration-lock.test.sh
set -euo pipefail

readonly SCRIPT="$(cd "$(dirname "$0")" && pwd)/migration-lock.sh"
readonly DIR="backend/adapters/persistence/src/main/resources/db/migration"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/bin"

# Stub: `gh api [--paginate] <endpoint> --jq <filter>` answers from $FIXTURES/<endpoint with / ? & = as _>.
# A missing fixture behaves like an API error.
cat >"$work/bin/gh" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == "api" ]] || { echo "stub gh: only 'api' is supported" >&2; exit 1; }
shift
endpoint="" filter="."
while (( $# )); do
  case "$1" in
    --paginate) shift ;;
    --jq) filter="$2"; shift 2 ;;
    *) endpoint="$1"; shift ;;
  esac
done
file="$FIXTURES/$(tr '/?&=' '____' <<<"$endpoint").json"
[[ -f "$file" ]] || { echo "gh: HTTP 404 ($endpoint)" >&2; exit 1; }
jq -r "$filter" "$file"
STUB
chmod +x "$work/bin/gh"

passed=0
failures=0

# fixture <endpoint> <json>
fixture() { printf '%s' "$2" >"$FIXTURES/$(tr '/?&=' '____' <<<"$1").json"; }

files_json() { # <status> <path>...
  local status="$1" out="" path
  shift
  for path in "$@"; do out+="{\"filename\":\"$path\",\"status\":\"$status\"},"; done
  echo "[${out%,}]"
}

tree_json() { # <path>...
  local out="" path
  for path in "$@"; do out+="{\"path\":\"$path\",\"type\":\"blob\"},"; done
  echo "{\"truncated\":false,\"tree\":[${out%,}]}"
}

new_case() {
  FIXTURES="$work/$1"
  export FIXTURES
  mkdir -p "$FIXTURES"
  fixture "repos/o/r/git/trees/main?recursive=1" "$(tree_json "$DIR/V20260101000000__base.sql" "README.md")"
  fixture "repos/o/r/pulls?state=open&per_page=100" '[{"number":7},{"number":9},{"number":12}]'
  fixture "repos/o/r/pulls/7/files" "$(files_json modified README.md)"
  fixture "repos/o/r/pulls/9/files" "$(files_json added docs/x.md)"
  fixture "repos/o/r/pulls/12/files" "$(files_json added "$DIR/V20260201000000__new.sql")"
}

# expect <name> <exit code> <output substring>
expect() {
  local output code=0
  output="$(PATH="$work/bin:$PATH" GH_REPO=o/r BASE_REF=main "$SCRIPT" 12 2>&1)" || code=$?
  if [[ "$code" == "$2" && "$output" == *"$3"* ]]; then
    passed=$((passed + 1))
  else
    failures=$((failures + 1))
    echo "FAIL: $1 (exit $code, expected $2 with '$3')"
    echo "$output" | sed 's/^/    /'
  fi
}

new_case no-migration
fixture "repos/o/r/pulls/12/files" "$(files_json modified "$DIR/V20260101000000__base.sql")"
expect "a PR without new migrations passes" 0 "adds no Flyway migration"

new_case lock-free
expect "the only PR adding a migration holds the lock" 0 "holds the lock"

new_case older-holder
fixture "repos/o/r/pulls/9/files" "$(files_json added "$DIR/V20260115000000__other.sql")"
expect "an older open PR with a migration blocks" 1 "Older open PR(s) #9"

new_case newer-pr-ignored
fixture "repos/o/r/pulls?state=open&per_page=100" '[{"number":12},{"number":20}]'
fixture "repos/o/r/pulls/20/files" "$(files_json added "$DIR/V20260301000000__later.sql")"
expect "a newer open PR does not block the older one" 0 "holds the lock"

new_case renamed
fixture "repos/o/r/pulls/12/files" "$(files_json renamed "$DIR/V20260201000000__moved.sql")"
fixture "repos/o/r/pulls/9/files" "$(files_json copied "$DIR/V20260115000000__copy.sql")"
expect "renamed and copied migrations count" 1 "Older open PR(s) #9"

new_case stale-version
fixture "repos/o/r/pulls/12/files" "$(files_json added "$DIR/V20251231000000__old.sql")"
expect "a migration older than the base branch fails" 1 "rename your migration to a newer timestamp"

new_case equal-version
fixture "repos/o/r/pulls/12/files" "$(files_json added "$DIR/V20260101000000__same.sql")"
expect "a migration equal to the newest base version fails" 1 "rename your migration to a newer timestamp"

new_case bad-name
fixture "repos/o/r/pulls/12/files" "$(files_json added "$DIR/V1__short.sql")"
expect "a migration without a timestamp version fails" 1 "is not named V<yyyyMMddHHmmss>"

new_case empty-base
fixture "repos/o/r/git/trees/main?recursive=1" "$(tree_json README.md)"
expect "the first migration ever passes" 0 "holds the lock"

new_case api-error
rm "$FIXTURES/repos_o_r_pulls_9_files.json"
expect "an API error fails the check instead of passing it" 1 "HTTP 404"

new_case truncated-tree
fixture "repos/o/r/git/trees/main?recursive=1" '{"truncated":true,"tree":[]}'
expect "a truncated base listing fails" 2 "truncated"

echo "migration-lock: $passed passed, $failures failed"
(( failures == 0 ))
