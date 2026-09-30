#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Jofi contributors
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Auto-merge gate (proposal §4.4, ADR 0023).

Two steps, both fail closed (any missing or unreadable input means "needs a human"):

- `decide` runs in the lenses workflow on the PR and writes a small decision artifact.
- `verify` runs in the merge-gate workflow (workflow_run, always main's definition and main's copy of this
  file). It treats the artifact as untrusted data and re-checks the PR state and protected paths itself.

Labels are never an input, so a label set by an author or agent can't change the decision.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

AUTO_MERGE = "auto-merge"
NEEDS_HUMAN = "needs-human"
COMMENT_MARKER = "<!-- jofi-merge-gate -->"

RISK_LEVELS = ("low", "elevated", "high")
DEPENDENCY_CATEGORY = "dependencies"
RENOVATE_LOGIN = "renovate[bot]"
RENOVATE_BRANCH_PREFIX = "renovate/"
RENOVATE_ALLOWED_UPDATES = frozenset({"patch", "minor"})
FAILED_CONCLUSIONS = frozenset({"failure", "timed_out", "cancelled", "action_required", "startup_failure"})
# The REST API lists at most 3000 files of a PR. A list that long may be cut off, so it can't prove anything.
MAX_LISTED_FILES = 3000
MAX_FILES_IN_COMMENT = 20
SHA_PATTERN = re.compile(r"[0-9a-f]{40}\Z")
ARTIFACT_KEYS = frozenset({"pr", "head_sha", "decision", "reasons"})
MAX_ARTIFACT_BYTES = 64 * 1024
MAX_ARTIFACT_REASONS = 30
MAX_REASON_LENGTH = 2000


@dataclass(frozen=True)
class Rule:
    pattern: str
    category: str
    regex: re.Pattern[str]


@dataclass(frozen=True)
class GateInput:
    pr: dict
    expected_head_sha: str
    default_branch: str
    changed_files: list[str]
    select_result: str
    lens_result: str
    risk_text: str | None
    check_runs: list[dict]
    own_run_id: str
    rules: list[Rule]


@dataclass
class Decision:
    reasons: list[str] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)

    @property
    def label(self) -> str:
        return NEEDS_HUMAN if self.reasons else AUTO_MERGE

    def add(self, reason: str) -> None:
        if reason not in self.reasons:
            self.reasons.append(reason)


def glob_to_regex(pattern: str) -> re.Pattern[str]:
    """Translate a path glob: `*` and `?` stay within one directory, `**` crosses them, `**/` may match nothing."""
    parts, i = [], 0
    while i < len(pattern):
        if pattern.startswith("**/", i):
            parts.append("(?:.*/)?")
            i += 3
        elif pattern.startswith("**", i):
            parts.append(".*")
            i += 2
        elif pattern[i] == "*":
            parts.append("[^/]*")
            i += 1
        elif pattern[i] == "?":
            parts.append("[^/]")
            i += 1
        else:
            parts.append(re.escape(pattern[i]))
            i += 1
    # Ignoring case errs towards protecting too much, never too little.
    return re.compile("".join(parts) + r"\Z", re.IGNORECASE | re.DOTALL)


def load_rules(path: Path) -> list[Rule]:
    data = json.loads(path.read_text(encoding="utf-8"))
    rules = [Rule(r["pattern"], r["category"], glob_to_regex(r["pattern"])) for r in data["rules"]]
    if not rules:
        raise ValueError(f"{path} lists no protected paths")
    return rules


def matching_rules(file: str, rules: list[Rule]) -> list[Rule]:
    return [rule for rule in rules if rule.regex.match(file)]


def parse_risk(text: str | None) -> str | None:
    """Return the risk-classifier rating, or None if the result is missing or doesn't follow the schema."""
    if not text or not text.strip():
        return None
    try:
        result = json.loads(text)
    except json.JSONDecodeError:
        return None
    if not isinstance(result, dict) or not isinstance(result.get("findings"), list):
        return None
    risk = result.get("risk")
    return risk if risk in RISK_LEVELS else None


def renovate_update_types(body: str | None) -> list[str]:
    """Read the `Update` column of the first update table in a Renovate PR body.

    Only the first table counts: later ones are release notes copied from upstream projects.
    Returns an empty list (no exception) if there is no such table or any row can't be read.
    """
    lines = [line.strip() for line in (body or "").splitlines()]
    for index, line in enumerate(lines):
        header = [cell.strip().lower() for cell in line.strip("|").split("|")]
        if not (line.startswith("|") and "package" in header and "update" in header):
            continue
        column = header.index("update")
        updates = []
        for row in lines[index + 2:]:
            if not row.startswith("|"):
                break
            cells = row.strip("|").split("|")
            if len(cells) != len(header):
                return []
            updates.append(cells[column].strip().strip("`").strip().lower())
        return updates
    return []


def is_renovate_patch_or_minor(pr: dict) -> bool:
    updates = renovate_update_types(pr.get("body"))
    return (
        (pr.get("user") or {}).get("login") == RENOVATE_LOGIN
        and str((pr.get("head") or {}).get("ref", "")).startswith(RENOVATE_BRANCH_PREFIX)
        and not is_fork(pr)
        and bool(updates)
        and all(update in RENOVATE_ALLOWED_UPDATES for update in updates)
    )


def is_fork(pr: dict) -> bool:
    head_repo = ((pr.get("head") or {}).get("repo") or {}).get("full_name")
    base_repo = ((pr.get("base") or {}).get("repo") or {}).get("full_name")
    return not head_repo or head_repo != base_repo


def check_pr_state(pr: dict, expected_head_sha: str, default_branch: str, decision: Decision) -> None:
    if pr.get("state") != "open" or pr.get("draft") is not False:
        decision.add("The PR is not an open, ready-for-review PR.")
    if (pr.get("base") or {}).get("ref") != default_branch:
        decision.add(f"The PR does not target `{default_branch}`.")
    if is_fork(pr):
        decision.add("The PR comes from a fork.")
    if not expected_head_sha or (pr.get("head") or {}).get("sha") != expected_head_sha:
        decision.add("The PR head moved while the gate ran; the next run decides again.")


def check_lenses(gate: GateInput, decision: Decision) -> None:
    if gate.select_result != "success":
        decision.add(f"Lens selection did not succeed (`{safe(gate.select_result)}`).")
    if gate.lens_result != "success":
        decision.add(
            f"Not all triggered lenses passed (`{safe(gate.lens_result)}`): "
            "a lens failed, found a blocking issue or did not run."
        )
    risk = parse_risk(gate.risk_text)
    if risk is None:
        decision.add("The risk classifier returned no valid rating.")
    elif risk != "low":
        decision.add(f"The risk classifier rated the PR `{risk}`, not `low`.")


def check_other_checks(gate: GateInput, decision: Decision) -> None:
    # This run's own jobs (including earlier attempts) are judged through select/lens results above.
    own = f"/actions/runs/{gate.own_run_id}/"
    failed = sorted(
        {
            str(run.get("name"))
            for run in gate.check_runs
            if run.get("conclusion") in FAILED_CONCLUSIONS and own not in str(run.get("details_url", ""))
        }
    )
    if failed:
        decision.add("Checks failed: " + ", ".join(f"`{safe(name)}`" for name in failed) + ".")


def check_protected_paths(pr: dict, changed_files: list[str], rules: list[Rule], decision: Decision) -> None:
    if not changed_files:
        decision.add("The PR changes no files, or the file list could not be read.")
        return
    if len(changed_files) >= MAX_LISTED_FILES:
        decision.add("The PR is too large for the file list to be complete.")
        return
    hits = {file: matching_rules(file, rules) for file in changed_files}
    hits = {file: matched for file, matched in hits.items() if matched}
    if not hits:
        return
    only_dependencies = all(rule.category == DEPENDENCY_CATEGORY for matched in hits.values() for rule in matched)
    if only_dependencies and is_renovate_patch_or_minor(pr):
        note = "Dependency files changed by a Renovate patch/minor update (allowed exception)."
        if note not in decision.notes:
            decision.notes.append(note)
        return
    listed = sorted(hits)[:MAX_FILES_IN_COMMENT]
    lines = [f"`{safe(file)}` ({', '.join(sorted({rule.category for rule in hits[file]}))})" for file in listed]
    more = len(hits) - len(listed)
    decision.add("Protected paths touched (AGENTS.md §8): " + "; ".join(lines) + (f"; and {more} more." if more else "."))


def decide(gate: GateInput) -> Decision:
    decision = Decision()
    check_pr_state(gate.pr, gate.expected_head_sha, gate.default_branch, decision)
    check_lenses(gate, decision)
    check_other_checks(gate, decision)
    check_protected_paths(gate.pr, gate.changed_files, gate.rules, decision)
    return decision


def decision_artifact(decision: Decision, pr_number: int, head_sha: str) -> dict:
    return {"pr": pr_number, "head_sha": head_sha, "decision": decision.label, "reasons": decision.reasons}


def parse_artifact(text: str | None, pr_number: int, head_sha: str) -> tuple[str, list[str]] | None:
    """Validate the decision artifact strictly. It comes from a workflow the PR may have changed, so it is data only."""
    if not text or len(text.encode("utf-8")) > MAX_ARTIFACT_BYTES:
        return None
    try:
        data = json.loads(text)
    except json.JSONDecodeError:
        return None
    if not isinstance(data, dict) or set(data) != ARTIFACT_KEYS:
        return None
    decision, reasons = data["decision"], data["reasons"]
    valid = (
        type(data["pr"]) is int
        and data["pr"] == pr_number
        and data["head_sha"] == head_sha
        and isinstance(head_sha, str)
        and SHA_PATTERN.match(head_sha) is not None
        and decision in (AUTO_MERGE, NEEDS_HUMAN)
        and isinstance(reasons, list)
        and len(reasons) <= MAX_ARTIFACT_REASONS
        and all(isinstance(r, str) and 0 < len(r) <= MAX_REASON_LENGTH for r in reasons)
        and (decision == NEEDS_HUMAN) == bool(reasons)
    )
    return (decision, [untrusted_text(r) for r in reasons]) if valid else None


def verify(artifact_text: str | None, pr: dict, changed_files: list[str], run_head_sha: str,
           run_base_ref: str, default_branch: str, rules: list[Rule]) -> Decision:
    """Final decision in the privileged workflow: auto-merge only if the artifact says so and our own checks agree.

    `run_base_ref` is the PR's base as the workflow_run payload recorded it for the lenses run.
    """
    decision = Decision()
    if run_base_ref != default_branch:
        decision.add(f"The lenses run was for a PR targeting `{safe(run_base_ref)}`, not `{default_branch}`.")
    pr_number = pr.get("number")
    parsed = parse_artifact(artifact_text, pr_number, run_head_sha) if type(pr_number) is int else None
    if parsed is None:
        decision.add("The lenses workflow left no valid gate decision for this commit.")
    else:
        for reason in parsed[1]:
            decision.add(reason)
    check_pr_state(pr, run_head_sha, default_branch, decision)
    check_protected_paths(pr, changed_files, rules, decision)
    return decision


def safe(text: str) -> str:
    """File and check names come from the PR: keep them from breaking out of inline code in the comment."""
    return re.sub(r"[`\x00-\x1f\x7f]", "?", text)


def untrusted_text(text: str) -> str:
    """Reasons read back from the artifact: one line, no HTML, no @-mentions."""
    text = re.sub(r"[\x00-\x1f\x7f]", " ", text)
    return text.replace("<", "&lt;").replace("@", "@​")


def render_comment(decision: Decision, head_sha: str) -> str:
    lines = [COMMENT_MARKER, "## Merge gate", ""]
    if decision.label == AUTO_MERGE:
        lines.append(f"Eligible for auto-merge at `{safe(head_sha)[:12]}`: it merges once all required checks are green.")
    else:
        lines.append(f"Waits for a human review (`{safe(head_sha)[:12]}`):")
        lines.append("")
        lines.extend(f"- {reason}" for reason in decision.reasons)
    if decision.notes:
        lines.append("")
        lines.extend(f"- Note: {note}" for note in decision.notes)
    lines += ["", "_Computed by CI (`.review/gate.py`, ADR 0023). Labels set by hand have no effect._"]
    return "\n".join(lines) + "\n"


def read_optional(path: Path | None) -> str | None:
    if path is None or not path.is_file() or path.stat().st_size > MAX_ARTIFACT_BYTES:
        return None
    try:
        return path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError):
        return None


def read_json(path: Path, default: object) -> object:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return default


def read_pr(path: Path) -> dict:
    pr = read_json(path, {})
    return pr if isinstance(pr, dict) else {}


def read_files(path: Path) -> list[str]:
    files = read_json(path, [])
    return [f for f in files if isinstance(f, str)] if isinstance(files, list) else []


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("decide", "verify"):
        command = commands.add_parser(name)
        command.add_argument("--pr", type=Path, required=True, help="PR JSON from the REST API")
        command.add_argument("--files", type=Path, required=True, help="JSON list of changed paths (old and new names)")
        command.add_argument("--protected", type=Path, default=Path(__file__).parent / "protected-paths.json")
        command.add_argument("--head-sha", required=True, help="the commit this decision is about")
        command.add_argument("--default-branch", required=True)
        command.add_argument("--comment", type=Path, required=True, help="where to write the PR comment")
    decide_command = commands.choices["decide"]
    decide_command.add_argument("--checks", type=Path, required=True, help="JSON list of check runs on the head commit")
    decide_command.add_argument("--risk", type=Path, help="structured output of the risk-classifier lens")
    decide_command.add_argument("--select-result", required=True)
    decide_command.add_argument("--lens-result", required=True)
    decide_command.add_argument("--run-id", required=True)
    decide_command.add_argument("--artifact", type=Path, required=True, help="where to write the decision artifact")
    verify_command = commands.choices["verify"]
    verify_command.add_argument("--artifact", type=Path, required=True, help="decision artifact to verify")
    verify_command.add_argument("--run-base-ref", required=True, help="PR base from the workflow_run payload")
    return parser.parse_args(argv)


def run_decide(args: argparse.Namespace) -> Decision:
    checks = read_json(args.checks, [])
    gate = GateInput(
        pr=read_pr(args.pr),
        expected_head_sha=args.head_sha,
        default_branch=args.default_branch,
        changed_files=read_files(args.files),
        select_result=args.select_result,
        lens_result=args.lens_result,
        risk_text=read_optional(args.risk),
        check_runs=[c for c in checks if isinstance(c, dict)] if isinstance(checks, list) else [],
        own_run_id=args.run_id,
        rules=load_rules(args.protected),
    )
    decision = decide(gate)
    artifact = decision_artifact(decision, gate.pr.get("number"), args.head_sha)
    args.artifact.write_text(json.dumps(artifact), encoding="utf-8")
    return decision


def run_verify(args: argparse.Namespace) -> Decision:
    return verify(read_optional(args.artifact), read_pr(args.pr), read_files(args.files), args.head_sha,
                  args.run_base_ref, args.default_branch, load_rules(args.protected))


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    decision = run_decide(args) if args.command == "decide" else run_verify(args)
    args.comment.write_text(render_comment(decision, args.head_sha), encoding="utf-8")
    print(decision.label)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
