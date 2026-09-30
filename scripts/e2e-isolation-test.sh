#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Jofi contributors
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Proves that nothing from the e2e stack (issue #21, ADR-0036) can reach production:
#  1. compose.yaml alone, with or without `--profile e2e`, defines exactly app, worker and db, and its
#     resolved configuration names no e2e service, network or setting;
#  2. the production image holds none of the e2e files (fake AI, fixtures, seed, edge), checked in the
#     file names and in every entry of the application jar, decompressed.
# The app itself has no e2e code path at all: the e2e user is created through the public first-run API.
#
# Usage (repository root, after the image is built, e.g. by scripts/e2e-stack.sh or the smoke test):
#   scripts/e2e-isolation-test.sh            # docker
#   COMPOSE="podman-compose" CONTAINER=podman scripts/e2e-isolation-test.sh
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repo_root}"

read -r -a compose_command <<<"${COMPOSE:-docker compose}"
container="${CONTAINER:-docker}"
image="${JOFI_IMAGE:-localhost/jofi:local}"
# Strings that only e2e files contain.
markers='JOFI_FAKE_AI|fake-ai|tests/stack|e2e-internal|\[\[scenario:|e2e-seed'

fail() { echo "FAIL: $*" >&2; exit 1; }
pass() { echo "ok: $*"; }

# compose.yaml demands a database password; any value will do for rendering the configuration.
export JOFI_DB_PASSWORD="isolation-test-placeholder"

production() { "${compose_command[@]}" --project-name jofi -f compose.yaml "$@"; }

for profile_args in "" "--profile e2e"; do
  read -r -a extra <<<"${profile_args}"
  services="$(production "${extra[@]}" config --services | sort | tr '\n' ' ')"
  [[ "${services}" == "app db worker " ]] \
    || fail "compose.yaml ${profile_args:-(no profile)} defines '${services}', expected only app, db, worker"
done
pass "compose.yaml defines only app, worker and db, with or without --profile e2e"

if production config | grep -E -n "${markers}"; then
  fail "the production compose configuration mentions e2e parts (lines above)"
fi
pass "the production compose configuration has no e2e service, network or setting"

# The markers are not vacuous: the e2e overlay does contain them.
"${compose_command[@]}" --project-name jofi-e2e -f compose.yaml -f compose.e2e.yaml --profile e2e config \
  | grep -q -E "${markers}" || fail "the markers no longer match the e2e overlay; update them"
pass "the e2e overlay is recognised by the same markers"

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
jar = root / "app.jar"
with zipfile.ZipFile(jar) as archive:
    for entry in archive.infolist():
        if pattern.search(entry.filename.encode()) or pattern.search(archive.read(entry)):
            hits.append(f"app.jar!/{entry.filename}")
if hits:
    print("\n".join(hits))
    sys.exit(1)
PY
then
  fail "the production image contains e2e files (listed above)"
fi
pass "the production image (${image}) contains no e2e files"

echo "e2e isolation test passed."
