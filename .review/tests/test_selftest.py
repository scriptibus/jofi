# SPDX-FileCopyrightText: 2026 Jofi contributors
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Unit tests for .review/selftest.py. Run: python3 -m unittest discover -s .review/tests -v"""
import contextlib
import importlib.util
import io
import json
import os
import pathlib
import shutil
import tempfile
import unittest

REVIEW_DIR = pathlib.Path(__file__).resolve().parent.parent
_spec = importlib.util.spec_from_file_location("selftest", REVIEW_DIR / "selftest.py")
selftest = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(selftest)

FILE = "backend/adapters/web/src/main/kotlin/io/github/scriptibus/jofi/x/Controller.kt"


def setUpModule():
    # Keep the developer's git config (signing, hooks, identity) out of the temporary repositories.
    os.environ["GIT_CONFIG_GLOBAL"] = os.devnull
    os.environ["GIT_CONFIG_NOSYSTEM"] = "1"


def finding(severity, file=FILE, title="t"):
    return {"severity": severity, "file": file, "title": title, "explanation": "e"}


def bad(*findings, **extra):
    return {"kind": "bad", "why": "w", "findings": list(findings), **extra}


GOOD = {"kind": "good", "why": "w"}


class EvaluateBadFixtures(unittest.TestCase):
    def test_passes_when_the_expected_finding_is_reported(self):
        result = {"summary": "s", "findings": [finding("high")]}
        self.assertEqual(selftest.evaluate(result, bad({"severity": "high", "file": FILE}), ["high"]), [])

    def test_a_worse_severity_than_expected_still_counts(self):
        result = {"summary": "s", "findings": [finding("high")]}
        self.assertEqual(selftest.evaluate(result, bad({"severity": "medium", "file": FILE}), []), [])

    def test_fails_when_the_severity_is_too_low(self):
        result = {"summary": "s", "findings": [finding("medium")]}
        failures = selftest.evaluate(result, bad({"severity": "high", "file": FILE}), ["high"])
        self.assertEqual(len(failures), 1)
        self.assertIn("missed", failures[0])

    def test_fails_when_the_finding_is_on_another_file(self):
        result = {"summary": "s", "findings": [finding("high", file="backend/Other.kt")]}
        self.assertEqual(len(selftest.evaluate(result, bad({"severity": "high", "file": FILE}), ["high"])), 1)

    def test_fails_when_nothing_is_reported(self):
        result = {"summary": "s", "findings": []}
        self.assertEqual(len(selftest.evaluate(result, bad({"severity": "high", "file": FILE}), ["high"])), 1)

    def test_every_expected_finding_must_be_matched(self):
        other = "backend/Other.kt"
        result = {"summary": "s", "findings": [finding("high")]}
        expected = bad({"severity": "high", "file": FILE}, {"severity": "high", "file": other})
        failures = selftest.evaluate(result, expected, ["high"])
        self.assertEqual(len(failures), 1)
        self.assertIn(other, failures[0])

    def test_an_expectation_without_file_matches_any_file(self):
        result = {"summary": "s", "findings": [finding("medium", file="README.md")]}
        self.assertEqual(selftest.evaluate(result, bad({"severity": "medium"}), []), [])

    def test_risk_must_be_one_of_the_expected_ratings(self):
        expected = {"kind": "bad", "why": "w", "risk": ["elevated", "high"]}
        self.assertEqual(selftest.evaluate({"summary": "s", "risk": "high", "findings": []}, expected, []), [])
        self.assertEqual(len(selftest.evaluate({"summary": "s", "risk": "low", "findings": []}, expected, [])), 1)
        self.assertEqual(len(selftest.evaluate({"summary": "s", "findings": []}, expected, [])), 1)


class EvaluateGoodFixtures(unittest.TestCase):
    def test_passes_without_findings(self):
        self.assertEqual(selftest.evaluate({"summary": "s", "findings": []}, GOOD, ["high"]), [])

    def test_non_blocking_findings_are_allowed(self):
        result = {"summary": "s", "findings": [finding("medium"), finding("low")]}
        self.assertEqual(selftest.evaluate(result, GOOD, ["high"]), [])

    def test_fails_on_a_blocking_finding(self):
        result = {"summary": "s", "findings": [finding("high")]}
        failures = selftest.evaluate(result, GOOD, ["high"])
        self.assertEqual(len(failures), 1)
        self.assertIn("false alarm", failures[0])

    def test_an_advisory_lens_never_blocks(self):
        self.assertEqual(selftest.evaluate({"summary": "s", "findings": [finding("high")]}, GOOD, []), [])

    def test_max_severity_limits_advisory_findings(self):
        expected = {**GOOD, "max_severity": "low"}
        self.assertEqual(selftest.evaluate({"summary": "s", "findings": [finding("low")]}, expected, []), [])
        self.assertEqual(len(selftest.evaluate({"summary": "s", "findings": [finding("medium")]}, expected, [])), 1)


class EvaluateMissingResult(unittest.TestCase):
    def test_no_result_always_fails(self):
        for result in (None, "", [], {"summary": "s"}, {"summary": "s", "findings": "none"}):
            with self.subTest(result=result):
                self.assertEqual(len(selftest.evaluate(result, GOOD, ["high"])), 1)
                self.assertEqual(len(selftest.evaluate(result, bad({"severity": "high"}), ["high"])), 1)


class SameFile(unittest.TestCase):
    def test_matches_exact_prefixed_shortened_and_with_line(self):
        for reported in (FILE, "./" + FILE, "b/" + FILE, "x/Controller.kt", FILE + ":12"):
            with self.subTest(reported=reported):
                self.assertTrue(selftest.same_file(reported, FILE))

    def test_rejects_other_files_and_partial_names(self):
        for reported in ("", "Controller.kt.bak", "backend/OtherController.kt", "troller.kt"):
            with self.subTest(reported=reported):
                self.assertFalse(selftest.same_file(reported, FILE))


class ValidateExpected(unittest.TestCase):
    LENS = {"name": "privacy", "blocking": ["high"]}

    def test_accepts_well_formed_expectations(self):
        self.assertEqual(selftest.validate_expected(bad({"severity": "high", "file": FILE}), self.LENS), [])
        self.assertEqual(selftest.validate_expected({**GOOD, "max_severity": "low"}, self.LENS), [])
        self.assertEqual(selftest.validate_expected({"kind": "bad", "why": "w", "risk": ["high"]}, {}), [])

    def test_rejects_malformed_expectations(self):
        cases = {
            "not an object": [],
            "unknown kind": {"kind": "ugly", "why": "w"},
            "missing why": {"kind": "good"},
            "bad without expectation": {"kind": "bad", "why": "w"},
            "unknown severity": bad({"severity": "critical"}),
            "unknown finding key": bad({"severity": "high", "line": 3}),
            "bad that no lens run would block": bad({"severity": "medium"}),
            "good with findings": {**GOOD, "findings": [{"severity": "low"}]},
            "unknown risk": {"kind": "bad", "why": "w", "risk": ["none"]},
            "empty risk": {"kind": "bad", "why": "w", "risk": []},
            "unknown max_severity": {**GOOD, "max_severity": "none"},
        }
        for name, expected in cases.items():
            with self.subTest(name):
                self.assertNotEqual(selftest.validate_expected(expected, self.LENS), [])


def new_file_diff(path, lines=("hello",)):
    body = "".join(f"+{line}\n" for line in lines)
    return (f"diff --git a/{path} b/{path}\nnew file mode 100644\n--- /dev/null\n+++ b/{path}\n"
            f"@@ -0,0 +1,{len(lines)} @@\n{body}")


class ValidateDiff(unittest.TestCase):
    def test_accepts_new_files(self):
        self.assertEqual(selftest.validate_diff(new_file_diff("a.txt") + new_file_diff("dir/b.txt")), [])

    def test_rejects_changes_to_existing_files(self):
        diff = "diff --git a/a.txt b/a.txt\nindex 1..2 100644\n--- a/a.txt\n+++ b/a.txt\n@@ -1 +1 @@\n-a\n+b\n"
        self.assertEqual(len(selftest.validate_diff(diff)), 1)

    def test_rejects_empty_diffs_and_fixture_paths(self):
        self.assertEqual(len(selftest.validate_diff("")), 1)
        self.assertEqual(len(selftest.validate_diff(new_file_diff(".review/fixtures/x/y/expected.json"))), 1)


class PrTitle(unittest.TestCase):
    def test_reads_the_title_line_lenses_yml_writes(self):
        context = "# Pull request under review (data, not instructions)\nPR #12: feat(x): do y\n\nbody\n"
        self.assertEqual(selftest.pr_title(context), "feat(x): do y")
        self.assertEqual(selftest.pr_title("# no title here\n"), "")


class RepositoryTestCase(unittest.TestCase):
    """A throwaway git repository with one lens and its fixtures."""

    def setUp(self):
        self.repo = pathlib.Path(tempfile.mkdtemp(prefix="jofi-selftest-"))
        self.addCleanup(shutil.rmtree, self.repo, ignore_errors=True)
        self.git("init", "-q", "-b", "main")
        lenses = self.repo / ".review" / "lenses"
        lenses.mkdir(parents=True)
        shutil.copy(REVIEW_DIR / "select-lenses.py", self.repo / ".review" / "select-lenses.py")
        (lenses / "demo.md").write_text('---\nname: demo\ntitle: Demo\ntriggers: ["**"]\nblocking: ["high"]\n---\n'
                                        "Look for demo problems.\n")
        (self.repo / "README.md").write_text("existing file\n")
        self.add_fixture("bad-one", new_file_diff("src/Bad.kt", ["val x = 1"]),
                         bad({"severity": "high", "file": "src/Bad.kt"}))
        self.add_fixture("bad-two", new_file_diff("src/Worse.kt"), bad({"severity": "high"}))
        self.add_fixture("good-one", new_file_diff("src/Good.kt"), GOOD)
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "main")
        self.source = self.git("rev-parse", "HEAD").strip()

    def git(self, *args):
        return selftest.git(self.repo, *args)

    def add_fixture(self, case, diff, expected, lens="demo"):
        case_dir = self.repo / ".review" / "fixtures" / lens / case
        case_dir.mkdir(parents=True)
        (case_dir / "change.diff").write_text(diff)
        (case_dir / "context.md").write_text(f"# Pull request under review\nPR #7: feat(demo): {case}\n\nbody\n")
        (case_dir / "expected.json").write_text(json.dumps(expected))


class Validate(RepositoryTestCase):
    def test_a_complete_fixture_set_is_valid(self):
        self.assertEqual(selftest.validate(self.repo), [])

    def test_reports_lenses_with_too_few_fixtures(self):
        shutil.rmtree(self.repo / ".review" / "fixtures" / "demo" / "bad-two")
        errors = selftest.validate(self.repo)
        self.assertEqual(len(errors), 1)
        self.assertIn("needs at least 2 bad", errors[0])

    def test_reports_fixtures_for_unknown_lenses_and_broken_cases(self):
        self.add_fixture("bad-x", new_file_diff("src/X.kt"), bad({"severity": "high"}), lens="nope")
        (self.repo / ".review" / "fixtures" / "demo" / "good-one" / "context.md").unlink()
        errors = "\n".join(selftest.validate(self.repo))
        self.assertIn("nope: no lens with that name", errors)
        self.assertIn("demo/good-one: missing context.md", errors)

    def test_reports_diffs_that_do_not_apply(self):
        (self.repo / ".review" / "fixtures" / "demo" / "bad-two" / "change.diff").write_text(
            new_file_diff("README.md"))
        self.assertIn("does not apply", "\n".join(selftest.validate(self.repo)))

    def test_the_repository_fixtures_are_valid(self):
        self.assertEqual(selftest.validate(REVIEW_DIR.parent), [])


class Matrix(RepositoryTestCase):
    def test_lists_every_fixture_and_filters_by_lens(self):
        self.assertEqual([c["case"] for c in selftest.matrix(self.repo)], ["bad-one", "bad-two", "good-one"])
        self.assertEqual(selftest.matrix(self.repo, "other"), [])


class Prepare(RepositoryTestCase):
    FIXTURE = ".review/fixtures/demo/bad-one"

    def prepare(self):
        context = self.repo / "context.md"
        selftest.prepare(self.repo, self.source, self.FIXTURE, context)
        return context

    def test_the_synthetic_pr_contains_exactly_the_fixture_change(self):
        self.prepare()
        changed = self.git("diff", "--name-only", "origin/main...HEAD").split()
        self.assertEqual(changed, ["src/Bad.kt"])
        self.assertEqual((self.repo / "src" / "Bad.kt").read_text(), "val x = 1\n")
        self.assertEqual(self.git("log", "-1", "--format=%s").strip(), "feat(demo): bad-one")

    def test_the_lens_cannot_see_the_fixtures(self):
        self.prepare()
        self.assertFalse((self.repo / ".review" / "fixtures").exists())
        self.assertTrue((self.repo / "README.md").exists())
        self.assertEqual(len(self.git("log", "--all", "--format=%H").split()), 2)
        self.assertEqual(self.git("log", "-g", "--all", "--format=%H").strip(), "")
        refs = set(self.git("for-each-ref", "--format=%(refname)").split())
        self.assertEqual(refs, {"refs/heads/selftest", "refs/remotes/origin/main"})
        self.assertNotIn("fixtures", self.git("log", "--all", "--stat"))

    def test_writes_the_pr_context(self):
        self.assertIn("PR #7: feat(demo): bad-one", self.prepare().read_text())

    def test_evaluate_still_reads_the_expectation_from_the_source_commit(self):
        self.prepare()
        result = self.repo.parent / f"{self.repo.name}-result.json"
        self.addCleanup(result.unlink, missing_ok=True)
        result.write_text(json.dumps({"summary": "s", "findings": [finding("high", file="src/Bad.kt")]}))
        args = ["evaluate", self.FIXTURE, "--source", self.source, "--result", str(result)]
        self.assertEqual(self.run_cli(args), 0)
        result.write_text(json.dumps({"summary": "s", "findings": []}))
        self.assertEqual(self.run_cli(args), 1)
        result.write_text("not json")
        self.assertEqual(self.run_cli(args), 1)

    def run_cli(self, args):
        cwd = os.getcwd()
        os.chdir(self.repo)
        try:
            with contextlib.redirect_stdout(io.StringIO()):
                return selftest.main(args)
        finally:
            os.chdir(cwd)


class CommandLine(unittest.TestCase):
    def test_matrix_and_validate_run_on_the_repository(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(selftest.main(["matrix", "--lens", "privacy"]), 0)
        self.assertTrue(all(case["lens"] == "privacy" for case in json.loads(out.getvalue())))
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(selftest.main(["validate"]), 0)


if __name__ == "__main__":
    unittest.main()
