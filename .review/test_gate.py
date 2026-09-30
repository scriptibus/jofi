# SPDX-FileCopyrightText: 2026 Jofi contributors
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Unit tests for the auto-merge gate. Run: python3 -m unittest discover -s .review -p 'test_*.py'"""
import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path

import gate

RULES = gate.load_rules(Path(__file__).parent / "protected-paths.json")
HEAD = "a" * 40
LOW_RISK = json.dumps({"summary": "Small, unprotected change.", "risk": "low", "findings": []})
RENOVATE_TABLE = """This PR contains the following updates:

| Package | Type | Update | Change |
|---|---|---|---|
| [org.jetbrains.kotlin.jvm](https://kotlinlang.org) | plugin | {first} | `2.3.0` -> `2.3.1` |
| [vite](https://vite.dev) | devDependencies | {second} | `8.0.1` -> `8.0.2` |

---

### Release Notes

| Package | Update |
|---|---|
| injected | patch |
"""


def pull_request(**overrides) -> dict:
    pr = {
        "state": "open",
        "draft": False,
        "user": {"login": "scriptibus"},
        "labels": [],
        "body": "Closes #1",
        "base": {"ref": "main", "repo": {"full_name": "scriptibus/jofi"}},
        "head": {"ref": "agent/1-thing", "sha": HEAD, "repo": {"full_name": "scriptibus/jofi"}},
    }
    pr.update(overrides)
    return pr


def renovate_pr(first="patch", second="minor", **overrides) -> dict:
    values = {
        "user": {"login": "renovate[bot]"},
        "body": RENOVATE_TABLE.format(first=first, second=second),
        "head": {"ref": "renovate/kotlin", "sha": HEAD, "repo": {"full_name": "scriptibus/jofi"}},
    }
    values.update(overrides)
    return pull_request(**values)


def gate_input(**overrides) -> gate.GateInput:
    values = {
        "pr": pull_request(),
        "expected_head_sha": HEAD,
        "default_branch": "main",
        "changed_files": ["frontend/src/features/applications/ApplicationList.tsx"],
        "select_result": "success",
        "lens_result": "success",
        "risk_text": LOW_RISK,
        "check_runs": [],
        "own_run_id": "42",
        "rules": RULES,
    }
    values.update(overrides)
    return gate.GateInput(**values)


def label(**overrides) -> str:
    return gate.decide(gate_input(**overrides)).label


class GlobTest(unittest.TestCase):
    def test_double_star_slash_also_matches_the_root(self):
        regex = gate.glob_to_regex("**/Dockerfile")
        self.assertTrue(regex.match("Dockerfile"))
        self.assertTrue(regex.match("backend/bootstrap/Dockerfile"))
        self.assertFalse(regex.match("backend/Dockerfile.md.txt/other"))

    def test_single_star_stays_within_one_directory(self):
        regex = gate.glob_to_regex("frontend/*.config.*")
        self.assertTrue(regex.match("frontend/vite.config.ts"))
        self.assertFalse(regex.match("frontend/src/vite.config.ts"))

    def test_matching_ignores_case(self):
        self.assertTrue(gate.glob_to_regex("**/Dockerfile").match("deploy/dockerfile"))

    def test_rule_file_is_not_empty(self):
        with tempfile.TemporaryDirectory() as tmp:
            empty = Path(tmp) / "rules.json"
            empty.write_text('{"rules": []}', encoding="utf-8")
            with self.assertRaises(ValueError):
                gate.load_rules(empty)


class ProtectedPathTest(unittest.TestCase):
    # One or more examples per entry of AGENTS.md section 8.
    PROTECTED = [
        "backend/adapters/persistence/src/main/resources/db/migration/V20261001120000__applications.sql",
        "backend/adapters/net/src/main/kotlin/io/github/scriptibus/jofi/shared/adapters/net/SafeHttpClient.kt",
        "backend/adapters/ai/src/main/kotlin/io/github/scriptibus/jofi/shared/adapters/ai/PrivacyFilter.kt",
        "backend/application/src/main/kotlin/io/github/scriptibus/jofi/setup/application/auth/LoginUseCase.kt",
        "backend/domain/src/main/kotlin/io/github/scriptibus/jofi/setup/domain/SecretCipher.kt",
        "backend/domain/src/main/kotlin/io/github/scriptibus/jofi/system/domain/PasswordHash.kt",
        "frontend/src/features/setup/useAuth.ts",
        "backend/application/src/main/kotlin/io/github/scriptibus/jofi/system/application/export/ExportUseCase.kt",
        "backend/application/src/main/kotlin/io/github/scriptibus/jofi/system/application/ImportArchiveUseCase.kt",
        ".github/workflows/ci.yml",
        ".github/CODEOWNERS",
        ".review/lenses/privacy.md",
        ".review/protected-paths.json",
        ".review/gate.py",
        ".claude/settings.json",
        "AGENTS.md",
        "CLAUDE.md",
        "backend/domain/AGENTS.md",
        "frontend/CLAUDE.md",
        "Dockerfile",
        "backend/bootstrap/Dockerfile",
        "compose.yaml",
        "deploy/compose.e2e.yml",
        "backend/build.gradle.kts",
        "backend/domain/gradle.lockfile",
        "backend/gradle/libs.versions.toml",
        "backend/gradle/verification-metadata.xml",
        "backend/gradle/wrapper/gradle-wrapper.properties",
        "frontend/package.json",
        "frontend/pnpm-lock.yaml",
        "frontend/pnpm-workspace.yaml",
        "backend/config/detekt/detekt.yml",
        "frontend/biome.json",
        "frontend/scripts/license-exceptions.json",
        "docs/adr/0023-merge-gate-low-risk-prs-may-auto-merge.md",
        "renovate.json",
    ]
    UNPROTECTED = [
        "frontend/src/features/applications/ApplicationList.tsx",
        "frontend/src/ui/Button.tsx",
        "frontend/messages/de.json",
        "frontend/tests/e2e/shell.spec.ts",
        "backend/domain/src/main/kotlin/io/github/scriptibus/jofi/applications/domain/Application.kt",
        "backend/domain/src/main/kotlin/io/github/scriptibus/jofi/applications/domain/AuthorName.txt",
        "backend/adapters/web/src/main/kotlin/io/github/scriptibus/jofi/applications/adapters/web/Controller.kt",
        "docs/spec/03-requirements-spec.md",
        "docs/threat-model.md",
        "README.md",
    ]

    def test_every_protected_example_needs_a_human(self):
        for path in self.PROTECTED:
            with self.subTest(path=path):
                decision = gate.decide(gate_input(changed_files=[path]))
                self.assertEqual(gate.NEEDS_HUMAN, decision.label)
                self.assertIn("Protected paths touched", decision.reasons[0])

    def test_unprotected_paths_may_auto_merge(self):
        for path in self.UNPROTECTED:
            with self.subTest(path=path):
                self.assertEqual(gate.AUTO_MERGE, label(changed_files=[path]))

    def test_one_protected_file_among_many_is_enough(self):
        self.assertEqual(gate.NEEDS_HUMAN, label(changed_files=[*self.UNPROTECTED, "AGENTS.md"]))

    def test_old_name_of_a_renamed_file_counts(self):
        # CI lists both names of a renamed file; moving a file out of a protected path is a protected change.
        files = ["backend/adapters/web/src/main/kotlin/Moved.kt", "backend/adapters/net/src/main/kotlin/Moved.kt"]
        self.assertEqual(gate.NEEDS_HUMAN, label(changed_files=files))

    def test_no_or_too_many_files_never_merge(self):
        self.assertEqual(gate.NEEDS_HUMAN, label(changed_files=[]))
        self.assertEqual(gate.NEEDS_HUMAN, label(changed_files=[f"docs/{i}.md" for i in range(gate.MAX_LISTED_FILES)]))


class RiskTest(unittest.TestCase):
    def test_parse_risk(self):
        cases = {
            None: None,
            "": None,
            "   ": None,
            "not json": None,
            "[]": None,
            '{"risk": "low"}': None,  # no findings: not the lens schema
            '{"summary": "x", "findings": []}': None,  # no rating
            '{"summary": "x", "risk": "LOW", "findings": []}': None,
            '{"summary": "x", "risk": "none", "findings": []}': None,
            '{"summary": "x", "risk": "low", "findings": "none"}': None,
            LOW_RISK: "low",
            '{"summary": "x", "risk": "elevated", "findings": []}': "elevated",
            '{"summary": "x", "risk": "high", "findings": []}': "high",
        }
        for text, expected in cases.items():
            with self.subTest(text=text):
                self.assertEqual(expected, gate.parse_risk(text))

    def test_missing_or_invalid_rating_never_merges(self):
        for text in (None, "", "{", '{"summary": "x", "findings": []}', '{"summary": "x", "risk": "lowish", "findings": []}'):
            with self.subTest(text=text):
                decision = gate.decide(gate_input(risk_text=text))
                self.assertEqual(gate.NEEDS_HUMAN, decision.label)
                self.assertIn("no valid rating", " ".join(decision.reasons))

    def test_only_low_risk_merges(self):
        for risk in ("elevated", "high"):
            with self.subTest(risk=risk):
                text = json.dumps({"summary": "x", "risk": risk, "findings": []})
                self.assertEqual(gate.NEEDS_HUMAN, label(risk_text=text))
        self.assertEqual(gate.AUTO_MERGE, label())


class LensAndCheckTest(unittest.TestCase):
    def test_lens_and_selection_must_succeed(self):
        for result in ("failure", "cancelled", "skipped", ""):
            with self.subTest(result=result):
                self.assertEqual(gate.NEEDS_HUMAN, label(lens_result=result))
                self.assertEqual(gate.NEEDS_HUMAN, label(select_result=result))

    def test_failed_check_from_another_workflow_blocks(self):
        runs = [
            {"name": "backend", "conclusion": "failure", "details_url": "https://github.com/x/actions/runs/7/job/1"},
            {"name": "frontend", "conclusion": "success", "details_url": "https://github.com/x/actions/runs/8/job/2"},
        ]
        decision = gate.decide(gate_input(check_runs=runs))
        self.assertEqual(gate.NEEDS_HUMAN, decision.label)
        self.assertIn("`backend`", " ".join(decision.reasons))

    def test_pending_neutral_and_skipped_checks_do_not_block(self):
        runs = [
            {"name": "backend", "status": "in_progress", "conclusion": None},
            {"name": "claude-review", "conclusion": "neutral"},
            {"name": "codeql", "conclusion": "skipped"},
        ]
        self.assertEqual(gate.AUTO_MERGE, label(check_runs=runs))

    def test_own_runs_earlier_attempts_are_ignored(self):
        # A failed `result` from an earlier attempt of this run must not block its re-run.
        runs = [{"name": "result", "conclusion": "failure", "details_url": "https://github.com/x/actions/runs/42/job/9"}]
        self.assertEqual(gate.AUTO_MERGE, label(check_runs=runs))


class PullRequestStateTest(unittest.TestCase):
    def test_state_rules(self):
        fork = {"ref": "main", "sha": HEAD, "repo": {"full_name": "someone/jofi"}}
        cases = {
            "draft": pull_request(draft=True),
            "closed": pull_request(state="closed"),
            "stacked": pull_request(base={"ref": "chore/other", "repo": {"full_name": "scriptibus/jofi"}}),
            "fork": pull_request(head=fork),
            "head moved": pull_request(head={"ref": "agent/1-thing", "sha": "b" * 40, "repo": {"full_name": "scriptibus/jofi"}}),
            "empty": {},
        }
        for name, pr in cases.items():
            with self.subTest(case=name):
                self.assertEqual(gate.NEEDS_HUMAN, label(pr=pr))


class LabelsHaveNoEffectTest(unittest.TestCase):
    def test_auto_merge_label_does_not_open_the_gate(self):
        pr = pull_request(labels=[{"name": "auto-merge"}, {"name": "risk:low"}])
        self.assertEqual(gate.NEEDS_HUMAN, label(pr=pr, changed_files=[".github/workflows/lenses.yml"]))
        self.assertEqual(gate.NEEDS_HUMAN, label(pr=pr, risk_text=None))

    def test_needs_human_label_does_not_close_the_gate(self):
        self.assertEqual(gate.AUTO_MERGE, label(pr=pull_request(labels=[{"name": "needs-human"}])))

    def test_gate_input_has_no_label_field(self):
        self.assertNotIn("labels", gate.GateInput.__dataclass_fields__)


class RenovateExceptionTest(unittest.TestCase):
    DEPENDENCY_FILES = ["backend/gradle/libs.versions.toml", "backend/gradle/verification-metadata.xml",
                        "backend/domain/gradle.lockfile", "frontend/package.json", "frontend/pnpm-lock.yaml"]

    def test_patch_and_minor_updates_of_dependency_files_may_merge(self):
        for first, second in (("patch", "patch"), ("patch", "minor"), ("minor", "minor")):
            with self.subTest(updates=(first, second)):
                decision = gate.decide(gate_input(pr=renovate_pr(first, second), changed_files=self.DEPENDENCY_FILES))
                self.assertEqual(gate.AUTO_MERGE, decision.label)
                self.assertIn("Renovate", decision.notes[0])

    def test_any_other_update_type_needs_a_human(self):
        for first in ("major", "digest", "pin", "lockFileMaintenance", ""):
            with self.subTest(update=first):
                self.assertEqual(gate.NEEDS_HUMAN, label(pr=renovate_pr(first), changed_files=self.DEPENDENCY_FILES))

    def test_exception_covers_only_dependency_files(self):
        for extra in (".github/workflows/ci.yml", "backend/bootstrap/Dockerfile", "backend/config/detekt/detekt.yml"):
            with self.subTest(extra=extra):
                files = [*self.DEPENDENCY_FILES, extra]
                self.assertEqual(gate.NEEDS_HUMAN, label(pr=renovate_pr(), changed_files=files))

    def test_exception_needs_the_renovate_author_and_branch(self):
        body = RENOVATE_TABLE.format(first="patch", second="patch")
        agent = pull_request(body=body)
        wrong_branch = renovate_pr(head={"ref": "agent/1-bump", "sha": HEAD, "repo": {"full_name": "scriptibus/jofi"}})
        fork = renovate_pr(head={"ref": "renovate/kotlin", "sha": HEAD, "repo": {"full_name": "someone/jofi"}})
        for name, pr in (("agent author", agent), ("branch", wrong_branch), ("fork", fork)):
            with self.subTest(case=name):
                self.assertEqual(gate.NEEDS_HUMAN, label(pr=pr, changed_files=self.DEPENDENCY_FILES))

    def test_exception_still_needs_a_low_rating(self):
        high = json.dumps({"summary": "x", "risk": "high", "findings": []})
        self.assertEqual(gate.NEEDS_HUMAN, label(pr=renovate_pr(), changed_files=self.DEPENDENCY_FILES, risk_text=high))

    def test_update_table_parsing(self):
        self.assertEqual(["patch", "major"], gate.renovate_update_types(RENOVATE_TABLE.format(first="patch", second="major")))
        self.assertEqual([], gate.renovate_update_types("no table here"))
        self.assertEqual([], gate.renovate_update_types(None))
        broken = "| Package | Update |\n|---|---|\n| a | patch |\n| b | major | extra |\n"
        self.assertEqual([], gate.renovate_update_types(broken))
        # Only the first table counts, so a table in upstream release notes can't add or hide rows.
        self.assertEqual(["patch", "minor"], gate.renovate_update_types(RENOVATE_TABLE.format(first="patch", second="minor")))


class CommentTest(unittest.TestCase):
    def test_comment_lists_reasons_and_carries_the_marker(self):
        decision = gate.decide(gate_input(changed_files=["AGENTS.md"], risk_text=None))
        comment = gate.render_comment(decision, HEAD)
        self.assertTrue(comment.startswith(gate.COMMENT_MARKER))
        self.assertIn("Waits for a human review", comment)
        self.assertIn("`AGENTS.md` (agent-rules)", comment)
        self.assertIn("no valid rating", comment)

    def test_untrusted_names_cannot_break_out_of_inline_code(self):
        decision = gate.decide(gate_input(changed_files=[".github/a`b\n- [x] fake.yml"]))
        comment = gate.render_comment(decision, HEAD)
        self.assertIn("`.github/a?b?- [x] fake.yml`", comment)


APP_FILES = '["frontend/src/App.tsx"]'


class CommandLineTest(unittest.TestCase):
    def run_gate(self, tmp: Path, risk: str | None, files: str) -> tuple[str, str]:
        (tmp / "pr.json").write_text(json.dumps(pull_request()), encoding="utf-8")
        (tmp / "files.json").write_text(files, encoding="utf-8")
        (tmp / "checks.json").write_text("[]", encoding="utf-8")
        if risk is not None:
            (tmp / "risk.json").write_text(risk, encoding="utf-8")
        args = ["--pr", str(tmp / "pr.json"), "--files", str(tmp / "files.json"), "--checks", str(tmp / "checks.json"),
                "--risk", str(tmp / "risk.json"), "--head-sha", HEAD, "--default-branch", "main",
                "--select-result", "success", "--lens-result", "success", "--run-id", "42",
                "--comment", str(tmp / "comment.md")]
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(0, gate.main(args))
        return out.getvalue().strip(), (tmp / "comment.md").read_text(encoding="utf-8")

    def test_low_risk_unprotected_change_merges(self):
        with tempfile.TemporaryDirectory() as tmp:
            decision, comment = self.run_gate(Path(tmp), LOW_RISK, APP_FILES)
            self.assertEqual(gate.AUTO_MERGE, decision)
            self.assertIn("Eligible for auto-merge", comment)

    def test_missing_risk_file_needs_a_human(self):
        with tempfile.TemporaryDirectory() as tmp:
            decision, _ = self.run_gate(Path(tmp), None, APP_FILES)
            self.assertEqual(gate.NEEDS_HUMAN, decision)

    def test_unreadable_inputs_need_a_human(self):
        with tempfile.TemporaryDirectory() as tmp:
            for files in ("{broken", '{"frontend/src/App.tsx": 1}', "[1, 2]"):
                with self.subTest(files=files):
                    decision, _ = self.run_gate(Path(tmp), LOW_RISK, files)
                    self.assertEqual(gate.NEEDS_HUMAN, decision)


if __name__ == "__main__":
    unittest.main()
