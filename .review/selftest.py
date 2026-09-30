#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Jofi contributors
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Lens self-test on known-bad and known-good fixture diffs (proposal §4.7, ADR 0021).

A fixture is `.review/fixtures/<lens>/<case>/` with `change.diff` (only adds new files), `context.md`
(PR description and linked issue) and `expected.json`. See `.review/README.md` for the format.

Subcommands:
  matrix [--lens NAME]                      GitHub Actions matrix of fixtures as JSON
  validate                                  check the shape of every fixture (runs in CI on every PR)
  prepare FIXTURE --out DIR --context-out FILE
                                            build a synthetic PR repository for one fixture (fresh git init)
  evaluate FIXTURE --expected FILE --result FILE
                                            compare a lens result with the fixture's expectation
"""
import argparse
import importlib.util
import json
import pathlib
import re
import subprocess
import sys

REVIEW_DIR = pathlib.Path(__file__).resolve().parent
REPO_ROOT = REVIEW_DIR.parent
FIXTURES = ".review/fixtures"
SEVERITIES = ["low", "medium", "high"]
RISKS = ["low", "elevated", "high"]
KINDS = ["bad", "good"]
MIN_BAD, MIN_GOOD = 2, 1
BASE_REF = "refs/remotes/origin/main"
HEAD_BRANCH = "selftest"
# Commits in the synthetic repo must never trigger local signing (1Password) or hooks.
GIT_CONFIG = ["-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null",
              "-c", "user.name=Jofi lens self-test", "-c", "user.email=selftest@invalid"]


def _load_select_lenses():
    spec = importlib.util.spec_from_file_location("select_lenses", REVIEW_DIR / "select-lenses.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def lens_meta(lens_dir: pathlib.Path) -> dict[str, dict]:
    """Front matter of every lens, keyed by lens name."""
    parse = _load_select_lenses().front_matter
    metas = (parse(path.read_text(encoding="utf-8")) for path in sorted(lens_dir.glob("*.md")))
    return {meta["name"]: meta for meta in metas}


def git(repo: pathlib.Path, *args: str, stdin: str | None = None) -> str:
    completed = subprocess.run(["git", *GIT_CONFIG, *args], cwd=repo, input=stdin, text=True,
                               capture_output=True, check=False)
    if completed.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)} failed: {completed.stderr.strip()}")
    return completed.stdout


# --- matching -------------------------------------------------------------------------------------------------

def same_file(reported: str, expected: str) -> bool:
    """True if the lens's file path names the expected file (lenses sometimes shorten or prefix paths)."""
    reported, expected = _strip(reported), _strip(expected)
    if not reported or not expected:
        return False
    return reported == expected or expected.endswith("/" + reported) or reported.endswith("/" + expected)


def _strip(path: str) -> str:
    path = (path or "").strip().split(":")[0]
    while path.startswith("./"):
        path = path[2:]
    return path


def rank(severity: str | None) -> int:
    return SEVERITIES.index(severity) if severity in SEVERITIES else -1


def evaluate(result: object, expected: dict, blocking: list[str]) -> list[str]:
    """Reasons why a lens result does not meet the fixture's expectation; empty means the lens passed."""
    if not isinstance(result, dict) or not isinstance(result.get("findings"), list):
        return ["the lens produced no result (no structured output with a findings list)"]
    findings = [f for f in result["findings"] if isinstance(f, dict)]
    failures = []
    if expected["kind"] == "bad":
        for wanted in expected.get("findings", []):
            if not any(_matches(found, wanted) for found in findings):
                where = f" on {wanted['file']}" if wanted.get("file") else ""
                about = f" about {' / '.join(wanted['about'])}" if wanted.get("about") else ""
                failures.append(f"missed: expected a {wanted['severity']} (or worse) finding{where}{about}")
    else:
        for found in findings:
            if found.get("severity") in blocking:
                failures.append(f"false alarm: blocking {found.get('severity')} finding on "
                                f"{found.get('file')}: {found.get('title')}")
            elif "max_severity" in expected and rank(found.get("severity")) > rank(expected["max_severity"]):
                failures.append(f"false alarm: {found.get('severity')} finding above {expected['max_severity']} "
                                f"on {found.get('file')}: {found.get('title')}")
    if "risk" in expected and result.get("risk") not in expected["risk"]:
        failures.append(f"risk rated {result.get('risk')!r}, expected one of {expected['risk']}")
    return failures


def _matches(found: dict, wanted: dict) -> bool:
    if rank(found.get("severity")) < rank(wanted["severity"]):
        return False
    if "file" in wanted and not same_file(str(found.get("file", "")), wanted["file"]):
        return False
    text = " ".join(str(found.get(key, "")) for key in ("title", "explanation", "suggestion")).lower()
    return not wanted.get("about") or any(word.lower() in text for word in wanted["about"])


# --- fixture discovery and validation ------------------------------------------------------------------------

def fixture_cases(root: pathlib.Path) -> list[pathlib.Path]:
    return sorted(path.parent for path in (root / FIXTURES).glob("*/*/expected.json"))


def matrix(root: pathlib.Path, lens: str = "") -> list[dict]:
    cases = [{"lens": case.parent.name, "case": case.name} for case in fixture_cases(root)]
    return [case for case in cases if not lens or case["lens"] == lens]


def validate_expected(expected: object, lens: dict) -> list[str]:
    if not isinstance(expected, dict):
        return ["expected.json must be an object"]
    errors = []
    kind = expected.get("kind")
    if kind not in KINDS:
        errors.append(f"kind must be one of {KINDS}")
    if not isinstance(expected.get("why"), str) or not expected["why"].strip():
        errors.append("why must say in one sentence what the fixture tests")
    findings = expected.get("findings", [])
    if not isinstance(findings, list) or any(not isinstance(f, dict) for f in findings):
        return [*errors, "findings must be a list of objects"]
    for wanted in findings:
        if wanted.get("severity") not in SEVERITIES:
            errors.append(f"finding severity must be one of {SEVERITIES}")
        if set(wanted) - {"severity", "file", "about"}:
            errors.append("a finding may only have severity, file and about")
        about = wanted.get("about", ["-"])
        if not isinstance(about, list) or not about or not all(isinstance(w, str) and w.strip() for w in about):
            errors.append("about must be a non-empty list of words, one of which the finding must mention")
    risk = expected.get("risk")
    if risk is not None and (not isinstance(risk, list) or not risk or set(risk) - set(RISKS)):
        errors.append(f"risk must be a non-empty list of {RISKS}")
    if "max_severity" in expected and expected["max_severity"] not in SEVERITIES:
        errors.append(f"max_severity must be one of {SEVERITIES}")
    if kind == "bad" and not findings and risk is None:
        errors.append("a bad fixture needs expected findings or a risk expectation")
    if kind == "bad" and findings and not any(f.get("severity") in lens.get("blocking", []) for f in findings) \
            and lens.get("blocking"):
        errors.append(f"a bad fixture must expect at least one blocking severity {lens['blocking']}")
    if kind == "good" and findings:
        errors.append("a good fixture must not expect findings")
    return errors


def validate_diff(diff: str) -> list[str]:
    """Fixture diffs only add new files, so they keep applying while main moves on."""
    blocks = [block for block in diff.split("diff --git ")[1:]]
    if not blocks:
        return ["change.diff contains no file"]
    errors = []
    for block in blocks:
        header = block.splitlines()[0]
        if "\nnew file mode " not in block or "\n--- /dev/null\n" not in block:
            errors.append(f"change.diff may only add new files: {header}")
        if f" b/{FIXTURES}/" in header:
            errors.append(f"change.diff must not touch the fixtures: {header}")
    return errors


def validate(root: pathlib.Path) -> list[str]:
    metas = lens_meta(root / ".review" / "lenses")
    errors = []
    counts = {name: {"bad": 0, "good": 0} for name in metas}
    fixture_root = root / FIXTURES
    for lens_dir in sorted(p for p in fixture_root.iterdir() if p.is_dir()) if fixture_root.is_dir() else []:
        if lens_dir.name not in metas:
            errors.append(f"{lens_dir.name}: no lens with that name in .review/lenses")
            continue
        for case in sorted(p for p in lens_dir.iterdir() if p.is_dir()):
            errors += [f"{lens_dir.name}/{case.name}: {e}" for e in _validate_case(root, case, metas[lens_dir.name])]
            kind = _read_kind(case)
            if kind in KINDS:
                counts[lens_dir.name][kind] += 1
    for name, count in counts.items():
        if count["bad"] < MIN_BAD or count["good"] < MIN_GOOD:
            errors.append(f"{name}: needs at least {MIN_BAD} bad and {MIN_GOOD} good fixtures, has "
                          f"{count['bad']} bad and {count['good']} good")
    return errors


def _read_kind(case: pathlib.Path) -> str | None:
    try:
        return json.loads((case / "expected.json").read_text(encoding="utf-8")).get("kind")
    except (OSError, ValueError, AttributeError):
        return None


def _validate_case(root: pathlib.Path, case: pathlib.Path, lens: dict) -> list[str]:
    missing = [name for name in ("change.diff", "context.md", "expected.json") if not (case / name).is_file()]
    if missing:
        return [f"missing {', '.join(missing)}"]
    try:
        expected = json.loads((case / "expected.json").read_text(encoding="utf-8"))
    except ValueError as error:
        return [f"expected.json is not valid JSON: {error}"]
    errors = validate_expected(expected, lens)
    diff = (case / "change.diff").read_text(encoding="utf-8")
    errors += validate_diff(diff)
    if not errors:
        try:
            git(root, "apply", "--check", "-", stdin=diff)
        except RuntimeError as error:
            errors.append(f"change.diff does not apply: {error}")
    if not pr_title((case / "context.md").read_text(encoding="utf-8")):
        errors.append("context.md needs a line 'PR #<number>: <title>' like lenses.yml writes it")
    return errors


# --- synthetic pull request ----------------------------------------------------------------------------------

def pr_title(context: str) -> str:
    """The PR title from a context file in the format lenses.yml writes (`PR #<n>: <title>`)."""
    match = re.search(r"^PR #\d+: (.+)$", context, re.M)
    return match.group(1).strip() if match else ""


def prepare(source: pathlib.Path, fixture: str, out: pathlib.Path, context_out: pathlib.Path) -> None:
    """Build a synthetic PR repository in `out` whose `origin/main...HEAD` is exactly the fixture's change.

    `out` is a fresh `git init` holding the source's committed tree without `.review/fixtures`, so no object,
    ref or history of the source repository (and therefore no expectation) is reachable from it.
    """
    case = source / fixture
    diff = (case / "change.diff").read_text(encoding="utf-8")
    context = (case / "context.md").read_text(encoding="utf-8")
    out.mkdir(parents=True)
    _export_tree(source, out)
    git(out, "init", "-q", "-b", HEAD_BRANCH)
    git(out, "add", "-A", "-f")
    git(out, "commit", "-q", "-m", "Base branch")
    git(out, "update-ref", BASE_REF, "HEAD")
    git(out, "apply", "--index", "-", stdin=diff)
    git(out, "commit", "-q", "-m", pr_title(context))
    context_out.write_text(context, encoding="utf-8")


def _export_tree(source: pathlib.Path, out: pathlib.Path) -> None:
    archive = subprocess.run(["git", "archive", "--format=tar", "HEAD", "--", ".", f":(exclude){FIXTURES}"],
                             cwd=source, capture_output=True, check=False)
    if archive.returncode != 0:
        raise RuntimeError(f"git archive failed: {archive.stderr.decode(errors='replace').strip()}")
    # The archive is our own committed tree; GNU tar also refuses absolute and `..` member paths by default.
    extract = subprocess.run(["tar", "-x", "-C", str(out)], input=archive.stdout, capture_output=True, check=False)
    if extract.returncode != 0:
        raise RuntimeError(f"tar failed: {extract.stderr.decode(errors='replace').strip()}")


def report(fixture: str, failures: list[str], result: object) -> str:
    status = "FAIL" if failures else "pass"
    lines = [f"### Lens self-test `{fixture}`: {status}", ""]
    lines += [f"- {failure}" for failure in failures]
    if isinstance(result, dict):
        if result.get("risk"):
            lines.append(f"- risk rated: {result['risk']}")
        for found in result.get("findings", []) if isinstance(result.get("findings"), list) else []:
            if isinstance(found, dict):
                lines.append(f"- reported {found.get('severity')} `{found.get('file')}:{found.get('line', '')}` "
                             f"{found.get('title')}")
    return "\n".join(lines) + "\n"


# --- command line --------------------------------------------------------------------------------------------


# --- command line --------------------------------------------------------------------------------------------

def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("matrix").add_argument("--lens", default="")
    commands.add_parser("validate")
    prepare_cmd = commands.add_parser("prepare")
    prepare_cmd.add_argument("fixture", help="e.g. .review/fixtures/privacy/bad-cv-in-logs")
    prepare_cmd.add_argument("--out", required=True, type=pathlib.Path, help="new directory for the synthetic PR")
    prepare_cmd.add_argument("--context-out", required=True, type=pathlib.Path)
    evaluate_cmd = commands.add_parser("evaluate")
    evaluate_cmd.add_argument("fixture", help="e.g. .review/fixtures/privacy/bad-cv-in-logs")
    evaluate_cmd.add_argument("--expected", required=True, type=pathlib.Path, help="the fixture's expected.json")
    evaluate_cmd.add_argument("--result", required=True, type=pathlib.Path, help="the lens's structured output")
    args = parser.parse_args(argv)
    return COMMANDS[args.command](args)


def _cmd_matrix(args) -> int:
    print(json.dumps(matrix(REPO_ROOT, args.lens)))
    return 0


def _cmd_validate(_args) -> int:
    errors = validate(REPO_ROOT)
    for error in errors:
        print(f"::error::{error}")
    print(f"{len(fixture_cases(REPO_ROOT))} fixtures checked, {len(errors)} problem(s)")
    return 1 if errors else 0


def _cmd_prepare(args) -> int:
    prepare(REPO_ROOT, args.fixture.rstrip("/"), args.out, args.context_out)
    return 0


def _cmd_evaluate(args) -> int:
    fixture = args.fixture.rstrip("/")
    expected = json.loads(args.expected.read_text(encoding="utf-8"))
    lens = pathlib.PurePosixPath(fixture).parent.name
    blocking = lens_meta(REVIEW_DIR / "lenses")[lens].get("blocking", ["high"])
    try:
        result = json.loads(args.result.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        result = None
    failures = evaluate(result, expected, blocking)
    print(report(fixture, failures, result))
    return 1 if failures else 0


COMMANDS = {"matrix": _cmd_matrix, "validate": _cmd_validate, "prepare": _cmd_prepare, "evaluate": _cmd_evaluate}

if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
