#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Jofi contributors
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Flyway migration gate for a pull request (AGENTS.md §1, ADR-0009, ADR-0030). Fails when the PR adds
# (or renames/copies in) a migration and
#   - an older open PR (lower number) also adds one: only one open PR at a time may add migrations,
#     the oldest holds the lock; or
#   - a new migration's version is not greater than the newest migration on the base branch.
#
# Usage: migration-lock.sh <pr-number>
# Environment: GH_REPO (owner/name), BASE_REF (base branch), GH_TOKEN. Uses only the GitHub API, so the
# workflow can run the base branch's copy of this script against any PR.
set -euo pipefail

readonly MIGRATIONS_DIR="backend/adapters/persistence/src/main/resources/db/migration/"
readonly VERSION_PATTERN='^V([0-9]{14})__'

# Migration files the PR adds, renames or copies into the migrations directory (all pages).
pr_migrations() {
  gh api --paginate "repos/${GH_REPO}/pulls/$1/files" \
    --jq ".[] | select((.status == \"added\" or .status == \"renamed\" or .status == \"copied\")
                       and (.filename | startswith(\"${MIGRATIONS_DIR}\"))) | .filename"
}

# Migration file names on the base branch (empty when the directory does not exist yet).
base_migrations() {
  local truncated
  truncated="$(gh api "repos/${GH_REPO}/git/trees/${BASE_REF}?recursive=1" --jq '.truncated')"
  [[ "$truncated" == "false" ]] || { echo "::error::Base tree listing is truncated." >&2; exit 2; }
  gh api "repos/${GH_REPO}/git/trees/${BASE_REF}?recursive=1" \
    --jq ".tree[] | select(.type == \"blob\" and (.path | startswith(\"${MIGRATIONS_DIR}\"))) | .path"
}

version_of() {
  local name="${1##*/}"
  [[ "$name" =~ $VERSION_PATTERN ]] && echo "${BASH_REMATCH[1]}"
}

pr="${1:-}"
[[ "$pr" =~ ^[0-9]+$ ]] || { echo "Not a PR number: $pr" >&2; exit 2; }
: "${GH_REPO:?GH_REPO is not set}" "${BASE_REF:?BASE_REF is not set}"

# Assignments (not inline substitutions) so a failing gh call aborts the check instead of passing it.
own="$(pr_migrations "$pr")"
if [[ -z "$own" ]]; then
  echo "PR #$pr adds no Flyway migration; nothing to check."
  exit 0
fi
echo "PR #$pr adds:"
echo "$own"
failed=0

base="$(base_migrations)"
newest_base=0
for path in $base; do
  version="$(version_of "$path" || true)"
  if [[ -n "$version" ]] && (( 10#$version > 10#$newest_base )); then newest_base="$version"; fi
done
for path in $own; do
  version="$(version_of "$path" || true)"
  if [[ -z "$version" ]]; then
    echo "::error::${path##*/} is not named V<yyyyMMddHHmmss>__<description>.sql."
    failed=1
  elif (( 10#$version <= 10#$newest_base )); then
    echo "::error::${path##*/} is not newer than the newest migration on ${BASE_REF} (V${newest_base}):" \
      "rename your migration to a newer timestamp."
    failed=1
  fi
done

open_prs="$(gh api --paginate "repos/${GH_REPO}/pulls?state=open&per_page=100" --jq '.[].number')"
holders=()
for other in $open_prs; do
  (( other < pr )) || continue
  other_migrations="$(pr_migrations "$other")"
  if [[ -n "$other_migrations" ]]; then
    holders+=("#$other")
  fi
done
if (( ${#holders[@]} > 0 )); then
  echo "::error::Older open PR(s) ${holders[*]} already add Flyway migrations. Only one open PR may add" \
    "migrations at a time: wait until they are merged or closed, rebase, and re-run this check."
  failed=1
fi

if (( failed )); then
  exit 1
fi
echo "No older open PR adds migrations and all versions are newer than ${BASE_REF}; PR #$pr holds the lock."
