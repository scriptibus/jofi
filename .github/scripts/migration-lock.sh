#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Jofi contributors
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Only one open PR at a time may add Flyway migrations (AGENTS.md §1, ADR-0009).
# Fails when the given PR adds a migration while an older open PR (lower number) also adds one:
# the oldest PR holds the lock, newer ones wait until it is merged or closed, then rebase.
#
# Usage: migration-lock.sh <pr-number>   (needs GH_TOKEN and GH_REPO in the environment)
set -euo pipefail

# Overridable only to exercise the script against other paths by hand.
readonly MIGRATIONS_DIR="${MIGRATIONS_DIR:-backend/adapters/persistence/src/main/resources/db/migration/}"

# Prints the migration files the given PR adds (one per line).
added_migrations() {
  gh pr view "$1" --json files \
    --jq ".files[] | select(.changeType == \"ADDED\" and (.path | startswith(\"${MIGRATIONS_DIR}\"))) | .path"
}

pr="$1"
[[ "$pr" =~ ^[0-9]+$ ]] || { echo "Not a PR number: $pr" >&2; exit 2; }

own="$(added_migrations "$pr")"
if [[ -z "$own" ]]; then
  echo "PR #$pr adds no Flyway migration; nothing to serialize."
  exit 0
fi
echo "PR #$pr adds:"
echo "$own"

# Assignments (not inline substitutions) so a failing gh call aborts the check instead of passing it.
open_prs="$(gh pr list --state open --limit 500 --json number --jq '.[].number')"
holders=()
for other in $open_prs; do
  (( other < pr )) || continue
  other_added="$(added_migrations "$other")"
  if [[ -n "$other_added" ]]; then
    holders+=("#$other")
  fi
done

if (( ${#holders[@]} > 0 )); then
  echo "::error::Older open PR(s) ${holders[*]} already add Flyway migrations. Only one open PR may add" \
    "migrations at a time: wait until they are merged or closed, rebase, and re-run this check."
  exit 1
fi
echo "No older open PR adds migrations; PR #$pr holds the migration lock."
