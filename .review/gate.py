#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Jofi contributors
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Auto-merge gate (proposal §4.4, ADR 0023).

Decides from data CI collected itself whether a PR may auto-merge. Labels are never an input, so a
label set by an author or agent can't change the decision. Every missing or unreadable input counts
against merging: the gate fails closed.

CI runs the copy of this file from the PR's base branch, so a PR can't weaken the gate that judges it.
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


def check_pr_state(gate: GateInput, decision: Decision) -> None:
    pr = gate.pr
    if pr.get("state") != "open" or pr.get("draft") is not False:
        decision.reasons.append("The PR is not an open, ready-for-review PR.")
    if (pr.get("base") or {}).get("ref") != gate.default_branch:
        decision.reasons.append(f"The PR does not target `{gate.default_branch}`.")
    if is_fork(pr):
        decision.reasons.append("The PR comes from a fork.")
    if not gate.expected_head_sha or (pr.get("head") or {}).get("sha") != gate.expected_head_sha:
        decision.reasons.append("The PR head moved while the gate ran; the next run decides again.")


def check_lenses(gate: GateInput, decision: Decision) -> None:
    if gate.select_result != "success":
        decision.reasons.append(f"Lens selection did not succeed (`{gate.select_result}`).")
    if gate.lens_result != "success":
        decision.reasons.append(
            f"Not all triggered lenses passed (`{gate.lens_result}`): a lens failed, found a blocking issue or did not run."
        )
    risk = parse_risk(gate.risk_text)
    if risk is None:
        decision.reasons.append("The risk classifier returned no valid rating.")
    elif risk != "low":
        decision.reasons.append(f"The risk classifier rated the PR `{risk}`, not `low`.")


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
        decision.reasons.append("Checks failed: " + ", ".join(f"`{safe(name)}`" for name in failed) + ".")


def check_protected_paths(gate: GateInput, decision: Decision) -> None:
    if not gate.changed_files:
        decision.reasons.append("The PR changes no files, or the file list could not be read.")
        return
    if len(gate.changed_files) >= MAX_LISTED_FILES:
        decision.reasons.append("The PR is too large for the file list to be complete.")
        return
    hits = {file: matching_rules(file, gate.rules) for file in gate.changed_files}
    hits = {file: rules for file, rules in hits.items() if rules}
    if not hits:
        return
    only_dependencies = all(rule.category == DEPENDENCY_CATEGORY for rules in hits.values() for rule in rules)
    if only_dependencies and is_renovate_patch_or_minor(gate.pr):
        decision.notes.append("Dependency files changed by a Renovate patch/minor update (allowed exception).")
        return
    listed = sorted(hits)[:MAX_FILES_IN_COMMENT]
    lines = [f"`{safe(file)}` ({', '.join(sorted({rule.category for rule in hits[file]}))})" for file in listed]
    more = len(hits) - len(listed)
    decision.reasons.append(
        "Protected paths touched (AGENTS.md §8): " + "; ".join(lines) + (f"; and {more} more." if more else ".")
    )


def decide(gate: GateInput) -> Decision:
    decision = Decision()
    check_pr_state(gate, decision)
    check_lenses(gate, decision)
    check_other_checks(gate, decision)
    check_protected_paths(gate, decision)
    return decision


def safe(text: str) -> str:
    """File and check names come from the PR: keep them from breaking out of inline code in the comment."""
    return re.sub(r"[`\x00-\x1f\x7f]", "?", text)


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
    if path is None or not path.is_file():
        return None
    return path.read_text(encoding="utf-8")


def read_json(path: Path, default: object) -> object:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return default


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--pr", type=Path, required=True, help="PR JSON from the REST API")
    parser.add_argument("--files", type=Path, required=True, help="JSON list of changed paths (old and new names)")
    parser.add_argument("--checks", type=Path, required=True, help="JSON list of check runs on the head commit")
    parser.add_argument("--risk", type=Path, help="structured output of the risk-classifier lens")
    parser.add_argument("--protected", type=Path, default=Path(__file__).parent / "protected-paths.json")
    parser.add_argument("--head-sha", required=True)
    parser.add_argument("--default-branch", required=True)
    parser.add_argument("--select-result", required=True)
    parser.add_argument("--lens-result", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--comment", type=Path, required=True, help="where to write the PR comment")
    return parser.parse_args(argv)


def as_list(value: object) -> list:
    return value if isinstance(value, list) else []


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    pr = read_json(args.pr, {})
    gate = GateInput(
        pr=pr if isinstance(pr, dict) else {},
        expected_head_sha=args.head_sha,
        default_branch=args.default_branch,
        changed_files=[f for f in as_list(read_json(args.files, [])) if isinstance(f, str)],
        select_result=args.select_result,
        lens_result=args.lens_result,
        risk_text=read_optional(args.risk),
        check_runs=[c for c in as_list(read_json(args.checks, [])) if isinstance(c, dict)],
        own_run_id=args.run_id,
        rules=load_rules(args.protected),
    )
    decision = decide(gate)
    args.comment.write_text(render_comment(decision, args.head_sha), encoding="utf-8")
    print(decision.label)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
