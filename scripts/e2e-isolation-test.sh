#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Jofi contributors
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Proves that nothing from the e2e stack (issue #21, ADR-0036) can reach production:
#  1. compose.yaml alone defines exactly app, worker and db, declares no profiles, and its resolved
#     configuration names no e2e service, network or setting (each marker is checked to occur in the
#     e2e sources, so the check cannot pass vacuously);
#  2. the production image holds none of the e2e files (fake AI, fixtures, seed, edge), checked in the
#     file names and in every entry of every jar (app.jar and lib/*.jar: all modules and libraries),
#     decompressed.
# The app itself has no e2e code path at all: the e2e user is created through the public first-run API.
#
# Usage (repository root, after scripts/e2e-stack.sh built localhost/jofi:e2e; JOFI_IMAGE overrides):
#   scripts/e2e-isolation-test.sh            # docker
#   COMPOSE="podman-compose" CONTAINER=podman scripts/e2e-isolation-test.sh
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repo_root}"

read -r -a compose_command <<<"${COMPOSE:-docker compose}"
container="${CONTAINER:-docker}"
# The image the e2e job builds (compose.e2e.yaml tags app and worker with it), from the same Dockerfile.
image="${JOFI_IMAGE:-localhost/jofi:e2e}"
# Strings that only e2e files contain. Each one must occur in the e2e sources (checked below).
marker_list=(
  'JOFI_FAKE_AI'
  'fake-ai'
  'tests/stack'
  'e2e-internal'
  '\[\[scenario:'
  'e2e-seed'
  'jofi-e2e-demo-password'
  'playwright/\.auth'
)
markers="$(IFS='|'; echo "${marker_list[*]}")"

fail() { echo "FAIL: $*" >&2; exit 1; }
pass() { echo "ok: $*"; }

# The markers are not vacuous: every one of them occurs in the e2e sources.
for marker in "${marker_list[@]}"; do
  grep -r -q -E -e "${marker}" frontend/tests/stack compose.e2e.yaml \
    || fail "marker '${marker}' no longer occurs in frontend/tests/stack or compose.e2e.yaml; update the list"
done
pass "every marker occurs in the e2e sources"

# compose.yaml demands a database password; any value will do for rendering the configuration.
export JOFI_DB_PASSWORD="isolation-test-placeholder"

production() { "${compose_command[@]}" --env-file /dev/null --project-name jofi -f compose.yaml "$@"; }

services="$(production config --services | sort | tr '\n' ' ')"
[[ "${services}" == "app db worker " ]] || fail "compose.yaml defines '${services}', expected only app, db, worker"
# No profiles at all: no `--profile` can switch anything on in a production deployment.
if grep -n -E '^[[:space:]]*profiles:' compose.yaml; then
  fail "compose.yaml declares profiles (lines above); e2e services belong in compose.e2e.yaml"
fi
pass "compose.yaml defines only app, worker and db, and no profiles"

if production config | grep -E -n "${markers}"; then
  fail "the production compose configuration mentions e2e parts (lines above)"
fi
pass "the production compose configuration has no e2e service, network or setting"

# The same markers do recognise the rendered e2e overlay.
"${compose_command[@]}" --env-file /dev/null --project-name jofi-e2e -f compose.yaml -f compose.e2e.yaml \
  --profile e2e config | grep -q -E "${markers}" || fail "the markers do not match the rendered e2e overlay"
pass "the rendered e2e overlay is recognised by the same markers"

scratch="$(mktemp -d)"
cid=""
cleanup() {
  [[ -z "${cid}" ]] || "${container}" rm "${cid}" >/dev/null 2>&1 || true
  rm -rf "${scratch}"
}
trap cleanup EXIT

cid="$("${container}" create "${image}")"
"${container}" cp "${cid}:/opt/jofi" "${scratch}/jofi"

if ! python3 - "${scratch}/jofi" "${markers}" <<'PY'
import pathlib, re, sys, zipfile

root, pattern = pathlib.Path(sys.argv[1]), re.compile(sys.argv[2].encode())
hits = []
for path in root.rglob("*"):
    if pattern.search(str(path.relative_to(root)).encode()):
        hits.append(str(path))
# The extracted layout keeps only bootstrap in app.jar; every other module and library is in lib/.
jars = sorted(root.rglob("*.jar"))
if not any(jar.name == "app.jar" for jar in jars) or len(jars) < 2:
    print(f"expected app.jar and lib/*.jar under {root}, found {len(jars)} jars")
    sys.exit(1)
for jar in jars:
    with zipfile.ZipFile(jar) as archive:
        for entry in archive.infolist():
            if pattern.search(entry.filename.encode()) or pattern.search(archive.read(entry)):
                hits.append(f"{jar.relative_to(root)}!/{entry.filename}")
if hits:
    print("\n".join(hits))
    sys.exit(1)
PY
then
  fail "the production image contains e2e files (listed above)"
fi
pass "the production image (${image}) contains no e2e files"

echo "e2e isolation test passed."
