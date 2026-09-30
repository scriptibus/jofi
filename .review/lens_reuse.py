#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Jofi contributors
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Reuse lens results while a PR's diff is unchanged (ADR 0021, .review/README.md "Reusing lens results").

A rebase, an "update branch" merge or a title edit doesn't change what the lenses review, so it shouldn't
cost another Claude call. A *fingerprint* identifies what a lens saw: the PR's effective diff (with hunk
positions, only blob ids removed), the base branch name and main's review setup. Each lens result is also
bound to the blob id of that lens's definition.

- `fingerprint` (lenses.yml, job `select`): computes the fingerprint of this run.
- `trusted-run` (lenses.yml): checks that a record comes from main's lens-record.yml, never from a PR's run.
- `plan` (lenses.yml): marks every selected lens whose result is recorded for this fingerprint as reused.
- `record` (lens-record.yml, main's definition and main's copy of this file): recomputes the fingerprint
  itself and stores the lens results of a finished run. Everything taken from that run is untrusted data.

Every check fails closed: anything missing, malformed or different means the lens runs again.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

VERSION = 1
RECORD_WORKFLOW_PATH = ".github/workflows/lens-record.yml"
RECORD_EVENT = "workflow_run"
LENS_DIR = ".review/lenses"
# The shared review setup on the base commit. A change to any of these (including the reuse rules in this
# file) invalidates every recorded result.
SHARED_DEFINITIONS = (
    ".review/prompt.md",
    ".review/findings.schema.json",
    ".review/select-lenses.py",
    ".review/lens_reuse.py",
    ".github/workflows/lenses.yml",
)
SHA_PATTERN = re.compile(r"[0-9a-f]{40}\Z")
FINGERPRINT_PATTERN = re.compile(r"[0-9a-f]{64}\Z")
LENS_NAME_PATTERN = re.compile(r"[a-z0-9][a-z0-9-]{0,62}\Z")
BASE_REF_PATTERN = re.compile(r"[A-Za-z0-9._/-]{1,255}\Z")
INDEX_LINE = re.compile(r"index [0-9a-f]+\.\.[0-9a-f]+(?P<mode> [0-7]{6})?\Z")
MAX_RESULT_BYTES = 64 * 1024
MAX_RECORD_BYTES = 1024 * 1024
MAX_CLAIM_BYTES = 4 * 1024
# Fixed options, so that no git config, no `.gitmodules` `ignore =` setting (a PR could hide a gitlink change
# with it) and no external driver changes the diff we hash. `.gitattributes` is read from the base commit
# (`--attr-source`, see diff_args), so `select` (merge commit checked out) and `record` (main checked out)
# hash the same thing, whatever the PR puts in its own `.gitattributes`.
DIFF_OPTIONS = (
    "-c", "core.quotePath=true", "-c", "diff.noprefix=false", "diff", "--no-color", "--no-ext-diff",
    "--no-textconv", "--ignore-submodules=none", "--binary", "--find-renames", "--diff-algorithm=myers",
    "--unified=3", "--src-prefix=a/", "--dst-prefix=b/",
)


def diff_args(base_sha: str, head_sha: str) -> tuple[str, ...]:
    return (f"--attr-source={base_sha}", *DIFF_OPTIONS, f"{base_sha}...{head_sha}")


def git(repo: Path, *args: str) -> bytes:
    return subprocess.run(("git", *args), cwd=repo, check=True, capture_output=True).stdout


def git_ok(repo: Path, *args: str) -> bool:
    return subprocess.run(("git", *args), cwd=repo, capture_output=True).returncode == 0


def normalise_diff(diff: bytes) -> bytes:
    """Drop the pre-image blob ids, which change whenever main changes a file the PR also touches.

    Everything else stays, hunk headers with their line numbers included: without them the same added lines
    at two places with identical context would hash the same. So a rebase over main commits that shift lines
    in a file the PR changes means a new review; rebases over changes to other files don't.
    Only header lines are rewritten: content lines always start with ' ', '+' or '-'.
    """
    lines = []
    for line in diff.split(b"\n"):
        text = line.decode("utf-8", errors="surrogateescape")
        index = INDEX_LINE.match(text)
        if index:
            text = "index" + (index.group("mode") or "")
        lines.append(text.encode("utf-8", errors="surrogateescape"))
    return b"\n".join(lines)


def blob_id(repo: Path, commit: str, path: str) -> str:
    """Object id of `path` at `commit`, or "missing"."""
    try:
        return git(repo, "rev-parse", "--verify", "--quiet", f"{commit}:{path}").decode().strip() or "missing"
    except subprocess.CalledProcessError:
        return "missing"


def lens_definitions(repo: Path, commit: str) -> dict[str, str]:
    """Blob id of every lens definition at `commit`, by lens name."""
    listing = git(repo, "ls-tree", "-z", commit, f"{LENS_DIR}/").decode("utf-8", errors="replace")
    definitions = {}
    for entry in filter(None, listing.split("\0")):
        meta, _, path = entry.partition("\t")
        name = Path(path).name.removesuffix(".md")
        if meta.split()[1:2] == ["blob"] and path.endswith(".md") and LENS_NAME_PATTERN.match(name):
            definitions[name] = meta.split()[2]
    return definitions


def fingerprint(repo: Path, base_sha: str, head_sha: str, base_ref: str) -> dict:
    """Fingerprint of the diff `base...head` (from their merge base) plus the review setup at `base`."""
    for sha in (base_sha, head_sha):
        if not SHA_PATTERN.match(sha):
            raise ValueError(f"not a full commit id: {sha!r}")
    if not BASE_REF_PATTERN.match(base_ref):
        raise ValueError(f"unexpected base ref: {base_ref!r}")
    digest = hashlib.sha256()
    digest.update(f"jofi-lens-reuse v{VERSION}\nbase-ref {base_ref}\n".encode())
    for path in SHARED_DEFINITIONS:
        digest.update(f"{path} {blob_id(repo, base_sha, path)}\n".encode())
    digest.update(b"diff\n")
    digest.update(normalise_diff(git(repo, *diff_args(base_sha, head_sha))))
    return {
        "version": VERSION,
        "base_sha": base_sha,
        "head_sha": head_sha,
        "base_ref": base_ref,
        "fingerprint": digest.hexdigest(),
        "definitions": lens_definitions(repo, base_sha),
    }


def is_trusted_record_run(run: dict, repository: str, default_branch: str) -> bool:
    """A record counts only if main's lens-record.yml wrote it. A PR's own workflows run on other events."""
    return (
        isinstance(run, dict)
        and run.get("path") == RECORD_WORKFLOW_PATH
        and run.get("event") == RECORD_EVENT
        and run.get("head_branch") == default_branch
        and (run.get("repository") or {}).get("full_name") == repository
        and (run.get("head_repository") or {}).get("full_name") == repository
    )


def parse_lens_result(value: object) -> dict | None:
    """A lens's structured output, if it has the shape lenses.yml evaluates; None otherwise."""
    if isinstance(value, (str, bytes)):
        if len(value) > MAX_RESULT_BYTES:
            return None
        try:
            value = json.loads(value)
        except (json.JSONDecodeError, UnicodeDecodeError):
            return None
    if not isinstance(value, dict) or not isinstance(value.get("findings"), list):
        return None
    if len(json.dumps(value).encode("utf-8")) > MAX_RESULT_BYTES:
        return None
    return value


def reusable_results(record: object, current: dict, pr_number: int) -> tuple[dict[str, dict], str]:
    """Lens results in `record` that are valid for `current` (a fingerprint dict), and the run they came from."""
    if not isinstance(record, dict) or record.get("version") != VERSION:
        return {}, ""
    if type(record.get("pr")) is not int or record["pr"] != pr_number:
        return {}, ""
    if not FINGERPRINT_PATTERN.match(str(current.get("fingerprint", ""))) \
            or record.get("fingerprint") != current["fingerprint"]:
        return {}, ""
    lenses = record.get("lenses")
    if not isinstance(lenses, dict):
        return {}, ""
    results = {}
    for name, entry in lenses.items():
        definition = current.get("definitions", {}).get(name)
        if not isinstance(entry, dict) or definition is None or entry.get("definition") != definition:
            continue
        result = parse_lens_result(entry.get("result"))
        if result is not None:
            results[name] = result
    run_id = str(record.get("run_id", ""))
    return results, run_id if run_id.isdigit() else ""


def plan(selected: list[dict], current: dict, record: object, pr_number: int) -> list[dict]:
    """The lens matrix for this run: selected lenses, with the recorded result attached where it is reusable."""
    results, run_id = reusable_results(record, current, pr_number)
    matrix = []
    for lens in selected:
        entry = dict(lens)
        result = results.get(lens.get("name"))
        if result is not None:
            entry["reused_result"] = json.dumps(result, separators=(",", ":"))
            entry["reused_run"] = run_id
        matrix.append(entry)
    return matrix


def read_limited(path: Path | None, limit: int) -> str | None:
    if path is None or not path.is_file() or path.is_symlink() or path.stat().st_size > limit:
        return None
    try:
        return path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError):
        return None


def read_json(path: Path | None, limit: int) -> object:
    text = read_limited(path, limit)
    try:
        return json.loads(text) if text is not None else None
    except json.JSONDecodeError:
        return None


def claimed_base(claim: object, repo: Path, trusted_ref: str) -> str | None:
    """The base commit a lenses run says it used, if it is a commit on the trusted branch."""
    base_sha = claim.get("base_sha") if isinstance(claim, dict) else None
    if not isinstance(base_sha, str) or not SHA_PATTERN.match(base_sha):
        return None
    return base_sha if git_ok(repo, "merge-base", "--is-ancestor", base_sha, trusted_ref) else None


def collect_results(lens_dir: Path, definitions: dict[str, str]) -> dict[str, dict]:
    """Valid lens results from the downloaded `lens-<name>/lens-result.json` artifacts (untrusted)."""
    lenses = {}
    if not lens_dir.is_dir():
        return lenses
    for directory in sorted(lens_dir.iterdir()):
        name = directory.name.removeprefix("lens-")
        if not directory.name.startswith("lens-") or name not in definitions or directory.is_symlink():
            continue
        result = parse_lens_result(read_limited(directory / "lens-result.json", MAX_RESULT_BYTES))
        if result is not None:
            lenses[name] = {"definition": definitions[name], "result": result}
    return lenses


def build_record(repo: Path, claim: object, lens_dir: Path, pr_number: int, head_sha: str, base_ref: str,
                 run_id: str, trusted_ref: str = "HEAD") -> dict | None:
    """The record for a finished lenses run, or None if nothing in it can be trusted for reuse.

    Only `head_sha`, `base_ref`, `pr_number` and `run_id` come from GitHub's workflow_run payload. The base
    commit is taken from the run's claim only if it is on the trusted branch, and the fingerprint is always
    recomputed here, so a run can't file its results under the fingerprint of some other diff.
    """
    base_sha = claimed_base(claim, repo, trusted_ref)
    if base_sha is None or not SHA_PATTERN.match(head_sha) or not BASE_REF_PATTERN.match(base_ref):
        return None
    if not git_ok(repo, "cat-file", "-e", f"{head_sha}^{{commit}}"):
        return None
    current = fingerprint(repo, base_sha, head_sha, base_ref)
    lenses = collect_results(lens_dir, current["definitions"])
    if not lenses or not str(run_id).isdigit():
        return None
    return {
        "version": VERSION,
        "pr": pr_number,
        "head_sha": head_sha,
        "base_sha": base_sha,
        "fingerprint": current["fingerprint"],
        "run_id": str(run_id),
        "lenses": lenses,
    }


def artifact_name(pr_number: int, fingerprint_hex: str) -> str:
    return f"lens-reuse-{pr_number}-{fingerprint_hex}"


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)
    command = commands.add_parser("fingerprint", help="write the fingerprint JSON of base...head")
    command.add_argument("--base", required=True)
    command.add_argument("--head", required=True)
    command.add_argument("--base-ref", required=True)
    command.add_argument("--out", type=Path, required=True)
    command = commands.add_parser("trusted-run", help="exit 0 if the run JSON is main's lens-record.yml")
    command.add_argument("--run", type=Path, required=True)
    command.add_argument("--repository", required=True)
    command.add_argument("--default-branch", required=True)
    command = commands.add_parser("plan", help="print the lens matrix with reusable results attached")
    command.add_argument("--selected", required=True, help="JSON list from select-lenses.py")
    command.add_argument("--fingerprint", type=Path, required=True)
    command.add_argument("--record", type=Path, help="a record from a trusted run; absent means no reuse")
    command.add_argument("--pr", type=int, required=True)
    command = commands.add_parser("record", help="write the record of a finished lenses run")
    command.add_argument("--claim", type=Path, required=True, help="the run's fingerprint JSON (untrusted)")
    command.add_argument("--lens-dir", type=Path, required=True, help="the run's lens-* artifacts (untrusted)")
    command.add_argument("--pr", type=int, required=True)
    command.add_argument("--head-sha", required=True)
    command.add_argument("--base-ref", required=True)
    command.add_argument("--run-id", required=True)
    command.add_argument("--out", type=Path, required=True)
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    repo = Path.cwd()
    if args.command == "fingerprint":
        current = fingerprint(repo, args.base, args.head, args.base_ref)
        args.out.write_text(json.dumps(current), encoding="utf-8")
        print(current["fingerprint"])
        return 0
    if args.command == "trusted-run":
        run = read_json(args.run, MAX_RECORD_BYTES)
        return 0 if is_trusted_record_run(run, args.repository, args.default_branch) else 1
    if args.command == "plan":
        current = read_json(args.fingerprint, MAX_RECORD_BYTES)
        record = read_json(args.record, MAX_RECORD_BYTES)
        print(json.dumps(plan(json.loads(args.selected), current if isinstance(current, dict) else {}, record, args.pr)))
        return 0
    record = build_record(repo, read_json(args.claim, MAX_CLAIM_BYTES), args.lens_dir, args.pr, args.head_sha,
                          args.base_ref, args.run_id)
    if record is not None:
        args.out.write_text(json.dumps(record), encoding="utf-8")
        print(artifact_name(args.pr, record["fingerprint"]))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
