# SPDX-FileCopyrightText: 2026 Jofi contributors
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Unit tests for .review/lens_reuse.py. Run: python3 -m unittest discover -s .review/tests -v"""
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REVIEW_DIR = Path(__file__).resolve().parent.parent
_spec = importlib.util.spec_from_file_location("lens_reuse", REVIEW_DIR / "lens_reuse.py")
lens_reuse = importlib.util.module_from_spec(_spec)
sys.modules["lens_reuse"] = lens_reuse
_spec.loader.exec_module(lens_reuse)

REPO = "scriptibus/jofi"
PASSED = {"summary": "Nothing found.", "findings": []}
LOW_RISK = {"summary": "Small change.", "risk": "low", "findings": []}
GIT_ENV = {
    "GIT_AUTHOR_NAME": "Test", "GIT_AUTHOR_EMAIL": "test@example.org",
    "GIT_COMMITTER_NAME": "Test", "GIT_COMMITTER_EMAIL": "test@example.org",
    "GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_NOSYSTEM": "1",
}


class Repo:
    """A throwaway git repository with main's review setup and a long file PRs can change."""

    def __init__(self, path: Path):
        self.path = path
        path.mkdir()
        self.git("init", "-q", "-b", "main")
        self.write(".review/prompt.md", "prompt\n")
        self.write(".review/findings.schema.json", "{}\n")
        self.write(".review/select-lenses.py", "print('[]')\n")
        self.write(".github/workflows/lenses.yml", "name: lenses\n")
        self.write(".review/lenses/privacy.md", "privacy lens\n")
        self.write(".review/lenses/risk-classifier.md", "risk lens\n")
        self.write("app.txt", "".join(f"line {n}\n" for n in range(1, 101)))
        self.commit("base")

    def git(self, *args: str) -> str:
        env = {**os.environ, **GIT_ENV}
        return subprocess.run(("git", *args), cwd=self.path, env=env, check=True,
                              capture_output=True, text=True).stdout.strip()

    def write(self, path: str, text: str) -> None:
        file = self.path / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(text, encoding="utf-8")

    def replace(self, path: str, old: str, new: str) -> None:
        file = self.path / path
        file.write_text(file.read_text(encoding="utf-8").replace(old, new, 1), encoding="utf-8")

    def commit(self, message: str) -> str:
        self.git("add", "-A")
        self.git("commit", "-q", "-m", message)
        return self.git("rev-parse", "HEAD")

    def fingerprint(self, base: str, head: str, base_ref: str = "main") -> dict:
        return lens_reuse.fingerprint(self.path, base, head, base_ref)


class RepoTestCase(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.repo = Repo(Path(self._tmp.name) / "repo")
        self.base = self.repo.git("rev-parse", "main")
        self.repo.git("switch", "-q", "-c", "pr")
        self.repo.replace("app.txt", "line 60\n", "line 60 changed by the PR\n")
        self.repo.write("feature.txt", "new file\n")
        self.head = self.repo.commit("pr change")

    def tearDown(self):
        self._tmp.cleanup()

    def advance_main(self, path: str = "app.txt", old: str = "line 1\n", new: str = "line 0\nline 1\n") -> str:
        """A later commit on main; by default it shifts every line number of the PR's hunk."""
        self.repo.git("switch", "-q", "main")
        self.repo.replace(path, old, new)
        sha = self.repo.commit("main moves on")
        self.repo.git("switch", "-q", "pr")
        return sha


class FingerprintTest(RepoTestCase):
    def test_a_rebase_onto_a_newer_main_keeps_the_fingerprint(self):
        before = self.repo.fingerprint(self.base, self.head)
        new_base = self.advance_main()
        self.repo.git("rebase", "-q", "main")
        rebased = self.repo.git("rev-parse", "HEAD")
        self.assertNotEqual(rebased, self.head)
        self.assertEqual(before["fingerprint"], self.repo.fingerprint(new_base, rebased)["fingerprint"])

    def test_an_update_branch_merge_keeps_the_fingerprint(self):
        before = self.repo.fingerprint(self.base, self.head)
        new_base = self.advance_main()
        self.repo.git("merge", "-q", "--no-edit", "main")
        merged = self.repo.git("rev-parse", "HEAD")
        self.assertEqual(before["fingerprint"], self.repo.fingerprint(new_base, merged)["fingerprint"])

    def test_a_stale_base_commit_gives_the_same_fingerprint(self):
        # The diff starts at the merge base, so any main commit after the fork point describes the same change.
        new_base = self.advance_main()
        self.assertEqual(self.repo.fingerprint(self.base, self.head)["fingerprint"],
                         self.repo.fingerprint(new_base, self.head)["fingerprint"])

    def test_any_change_to_the_diff_changes_the_fingerprint(self):
        before = self.repo.fingerprint(self.base, self.head)["fingerprint"]
        for path, old, new in [("feature.txt", "new file\n", "new  file\n"),  # whitespace only
                               ("feature.txt", "new file\n", "new file\nmore\n"),
                               ("app.txt", "line 90\n", "line 90!\n")]:
            with self.subTest(path=path, new=new):
                self.repo.git("reset", "-q", "--hard", self.head)
                self.repo.replace(path, old, new)
                head = self.repo.commit("another change")
                self.assertNotEqual(before, self.repo.fingerprint(self.base, head)["fingerprint"])

    def test_moving_a_change_to_other_surroundings_changes_the_fingerprint(self):
        self.repo.git("switch", "-q", "main")
        self.repo.git("switch", "-q", "-c", "elsewhere")
        self.repo.replace("app.txt", "line 30\n", "line 30\nline 60 changed by the PR\n")
        self.repo.replace("app.txt", "line 60\n", "")
        self.repo.write("feature.txt", "new file\n")
        elsewhere = self.repo.commit("same words, other place")
        self.assertNotEqual(self.repo.fingerprint(self.base, self.head)["fingerprint"],
                            self.repo.fingerprint(self.base, elsewhere)["fingerprint"])

    def test_main_changing_the_lines_around_the_change_changes_the_fingerprint(self):
        new_base = self.advance_main(old="line 57\n", new="line 57 edited on main\n")  # in the context
        self.repo.git("rebase", "-q", "main")
        self.assertNotEqual(self.repo.fingerprint(self.base, self.head)["fingerprint"],
                            self.repo.fingerprint(new_base, self.repo.git("rev-parse", "HEAD"))["fingerprint"])

    def test_retargeting_changes_the_fingerprint(self):
        self.assertNotEqual(self.repo.fingerprint(self.base, self.head, "main")["fingerprint"],
                            self.repo.fingerprint(self.base, self.head, "release/1")["fingerprint"])

    def test_a_change_to_the_shared_review_setup_on_main_changes_the_fingerprint(self):
        for path in lens_reuse.SHARED_DEFINITIONS:
            with self.subTest(path=path):
                before = self.repo.fingerprint(self.repo.git("rev-parse", "main"), self.head)["fingerprint"]
                new_base = self.advance_main(path, "\n", " changed\n")
                self.assertNotEqual(before, self.repo.fingerprint(new_base, self.head)["fingerprint"])

    def test_a_lens_definition_change_only_changes_that_lens(self):
        before = self.repo.fingerprint(self.base, self.head)
        new_base = self.advance_main(".review/lenses/privacy.md", "privacy lens\n", "sharper privacy lens\n")
        after = self.repo.fingerprint(new_base, self.head)
        self.assertEqual(before["fingerprint"], after["fingerprint"])
        self.assertNotEqual(before["definitions"]["privacy"], after["definitions"]["privacy"])
        self.assertEqual(before["definitions"]["risk-classifier"], after["definitions"]["risk-classifier"])

    def test_git_config_of_the_runner_does_not_change_the_fingerprint(self):
        before = self.repo.fingerprint(self.base, self.head)["fingerprint"]
        for key, value in [("diff.noprefix", "true"), ("diff.algorithm", "histogram"), ("diff.renames", "false")]:
            self.repo.git("config", key, value)
        self.assertEqual(before, self.repo.fingerprint(self.base, self.head)["fingerprint"])

    def test_rejects_anything_but_full_commit_ids(self):
        with self.assertRaises(ValueError):
            self.repo.fingerprint("main", self.head)
        with self.assertRaises(ValueError):
            self.repo.fingerprint(self.base, self.head, "main\nx")


class NormaliseDiffTest(unittest.TestCase):
    def test_drops_line_numbers_and_blob_ids_but_keeps_content_and_modes(self):
        diff = (b"diff --git a/x b/x\nindex 1234567..89abcde 100644\n--- a/x\n+++ b/x\n"
                b"@@ -10,3 +12,4 @@ fun main()\n ctx\n-old\n+new\n+@@ -1 +1 @@\n index 1..2\n")
        self.assertEqual(
            b"diff --git a/x b/x\nindex 100644\n--- a/x\n+++ b/x\n"
            b"@@ @@ fun main()\n ctx\n-old\n+new\n+@@ -1 +1 @@\n index 1..2\n",
            lens_reuse.normalise_diff(diff))


def trusted_run(**overrides) -> dict:
    run = {"path": ".github/workflows/merge-gate.yml", "event": "workflow_run", "head_branch": "main",
           "repository": {"full_name": REPO}, "head_repository": {"full_name": REPO}}
    run.update(overrides)
    return run


class TrustedRunTest(unittest.TestCase):
    def test_accepts_mains_merge_gate_workflow(self):
        self.assertTrue(lens_reuse.is_trusted_record_run(trusted_run(), REPO, "main"))

    def test_rejects_runs_a_pr_can_control(self):
        for overrides in [{"event": "pull_request"}, {"event": "push"}, {"event": "workflow_dispatch"},
                          {"path": ".github/workflows/lenses.yml"}, {"head_branch": "agent/1-x"},
                          {"head_repository": {"full_name": "someone/jofi"}},
                          {"repository": {"full_name": "someone/jofi"}}, {"repository": None}]:
            with self.subTest(overrides=overrides):
                self.assertFalse(lens_reuse.is_trusted_record_run(trusted_run(**overrides), REPO, "main"))
        self.assertFalse(lens_reuse.is_trusted_record_run([], REPO, "main"))


class RecordAndPlanTest(RepoTestCase):
    def lens_dir(self, results: dict[str, object]) -> Path:
        directory = Path(self._tmp.name) / f"lenses-{len(list(Path(self._tmp.name).iterdir()))}"
        for name, result in results.items():
            (directory / name).mkdir(parents=True)
            text = result if isinstance(result, str) else json.dumps(result)
            (directory / name / "lens-result.json").write_text(text, encoding="utf-8")
        return directory

    def record(self, results=None, claim=None, head=None, run_id="100") -> dict | None:
        results = {"lens-privacy": PASSED, "lens-risk-classifier": LOW_RISK} if results is None else results
        claim = {"base_sha": self.base} if claim is None else claim
        return lens_reuse.build_record(self.repo.path, claim, self.lens_dir(results), 7, head or self.head,
                                       "main", run_id, trusted_ref="main")

    def selected(self) -> list[dict]:
        return [{"name": "privacy", "title": "Privacy", "blocking": ["high"]},
                {"name": "risk-classifier", "title": "Risk", "blocking": []}]

    def test_a_rebased_pr_reuses_every_recorded_lens(self):
        record = self.record()
        new_base = self.advance_main()
        self.repo.git("rebase", "-q", "main")
        current = self.repo.fingerprint(new_base, self.repo.git("rev-parse", "HEAD"))
        matrix = lens_reuse.plan(self.selected(), current, json.loads(json.dumps(record)), 7)
        self.assertEqual(["privacy", "risk-classifier"], [lens["name"] for lens in matrix])
        self.assertEqual([PASSED, LOW_RISK], [json.loads(lens["reused_result"]) for lens in matrix])
        self.assertEqual({"100"}, {lens["reused_run"] for lens in matrix})
        self.assertEqual(["high"], matrix[0]["blocking"])  # blocking comes from the current selection

    def test_blocking_findings_are_reused_too_so_a_failure_stays_visible(self):
        blocking = {"summary": "Leak.", "findings": [{"severity": "high", "file": "a", "title": "t",
                                                      "explanation": "e"}]}
        record = self.record({"lens-privacy": blocking})
        matrix = lens_reuse.plan(self.selected(), self.repo.fingerprint(self.base, self.head), record, 7)
        self.assertEqual(blocking, json.loads(matrix[0]["reused_result"]))
        self.assertNotIn("reused_result", matrix[1])

    def test_a_changed_diff_reuses_nothing(self):
        record = self.record()
        self.repo.write("feature.txt", "different\n")
        current = self.repo.fingerprint(self.base, self.repo.commit("new push"))
        self.assertEqual(self.selected(), lens_reuse.plan(self.selected(), current, record, 7))

    def test_a_changed_lens_definition_reruns_only_that_lens(self):
        record = self.record()
        new_base = self.advance_main(".review/lenses/privacy.md", "privacy lens\n", "sharper\n")
        matrix = lens_reuse.plan(self.selected(), self.repo.fingerprint(new_base, self.head), record, 7)
        self.assertNotIn("reused_result", matrix[0])
        self.assertIn("reused_result", matrix[1])

    def test_a_record_of_another_pr_or_malformed_record_reuses_nothing(self):
        record = self.record()
        current = self.repo.fingerprint(self.base, self.head)
        for bad in [None, [], {**record, "pr": 8}, {**record, "pr": "7"}, {**record, "version": 2},
                    {**record, "fingerprint": "0" * 64}, {**record, "lenses": []},
                    {**record, "lenses": {"privacy": {"definition": "x", "result": PASSED}}},
                    {**record, "lenses": {"privacy": {**record["lenses"]["privacy"], "result": {"summary": "x"}}}}]:
            with self.subTest(record=bad):
                matrix = lens_reuse.plan(self.selected()[:1], current, bad, 7)
                self.assertEqual(self.selected()[:1], matrix)
        self.assertEqual(self.selected(), lens_reuse.plan(self.selected(), {}, record, 7))

    def test_the_record_uses_its_own_fingerprint_not_the_claimed_one(self):
        # A run whose workflow a PR controls could claim the fingerprint of a harmless diff it pushes later.
        claim = {"base_sha": self.base, "fingerprint": "f" * 64}
        record = self.record(claim=claim)
        self.assertEqual(self.repo.fingerprint(self.base, self.head)["fingerprint"], record["fingerprint"])

    def test_no_record_for_a_claimed_base_that_is_not_on_the_trusted_branch(self):
        for claim in [{}, {"base_sha": self.head}, {"base_sha": "0" * 40}, {"base_sha": "main"}, [],
                      {"base_sha": self.base[:12]}]:
            with self.subTest(claim=claim):
                self.assertIsNone(self.record(claim=claim))

    def test_no_record_for_an_unknown_head_or_run(self):
        self.assertIsNone(self.record(head="1" * 40))
        self.assertIsNone(self.record(run_id="12; rm"))

    def test_invalid_or_unknown_lens_results_are_left_out(self):
        record = self.record({
            "lens-privacy": "not json",
            "lens-risk-classifier": {"summary": "no findings key"},
            "lens-unknown": PASSED,
            "review-fingerprint": PASSED,
            "merge-gate": PASSED,
        })
        self.assertIsNone(record)
        big = {"summary": "x" * lens_reuse.MAX_RESULT_BYTES, "findings": []}
        self.assertIsNone(self.record({"lens-privacy": big}))
        self.assertEqual(["privacy"], list(self.record({"lens-privacy": PASSED, "lens-docs": PASSED})["lenses"]))

    def test_symlinked_results_are_ignored(self):
        directory = self.lens_dir({"lens-risk-classifier": LOW_RISK})
        secret = Path(self._tmp.name) / "secret.json"
        secret.write_text(json.dumps(PASSED), encoding="utf-8")
        (directory / "lens-privacy").mkdir()
        (directory / "lens-privacy" / "lens-result.json").symlink_to(secret)
        record = lens_reuse.build_record(self.repo.path, {"base_sha": self.base}, directory, 7, self.head,
                                         "main", "5", trusted_ref="main")
        self.assertEqual(["risk-classifier"], list(record["lenses"]))


class CommandLineTest(RepoTestCase):
    def run_cli(self, *args: str) -> subprocess.CompletedProcess:
        return subprocess.run((sys.executable, str(REVIEW_DIR / "lens_reuse.py"), *args), cwd=self.repo.path,
                              capture_output=True, text=True, env={**os.environ, **GIT_ENV})

    def test_fingerprint_record_and_plan_round_trip(self):
        tmp = Path(self._tmp.name)
        done = self.run_cli("fingerprint", "--base", self.base, "--head", self.head, "--base-ref", "main",
                            "--out", str(tmp / "fp.json"))
        self.assertEqual(0, done.returncode, done.stderr)
        (tmp / "lenses" / "lens-privacy").mkdir(parents=True)
        (tmp / "lenses" / "lens-privacy" / "lens-result.json").write_text(json.dumps(PASSED), encoding="utf-8")
        self.repo.git("switch", "-q", "main")  # merge-gate.yml checks out main
        done = self.run_cli("record", "--claim", str(tmp / "fp.json"), "--lens-dir", str(tmp / "lenses"),
                            "--pr", "7", "--head-sha", self.head, "--base-ref", "main", "--run-id", "42",
                            "--out", str(tmp / "record.json"))
        self.assertEqual(0, done.returncode, done.stderr)
        fingerprint = json.loads((tmp / "fp.json").read_text(encoding="utf-8"))["fingerprint"]
        self.assertEqual(f"lens-reuse-7-{fingerprint}", done.stdout.strip())
        selected = json.dumps([{"name": "privacy", "title": "Privacy", "blocking": ["high"]}])
        done = self.run_cli("plan", "--selected", selected, "--fingerprint", str(tmp / "fp.json"),
                            "--record", str(tmp / "record.json"), "--pr", "7")
        self.assertEqual("42", json.loads(done.stdout)[0]["reused_run"])
        done = self.run_cli("plan", "--selected", selected, "--fingerprint", str(tmp / "fp.json"), "--pr", "7")
        self.assertEqual(json.loads(selected), json.loads(done.stdout))

    def test_record_prints_nothing_when_nothing_can_be_trusted(self):
        tmp = Path(self._tmp.name)
        (tmp / "claim.json").write_text(json.dumps({"base_sha": self.head}), encoding="utf-8")
        done = self.run_cli("record", "--claim", str(tmp / "claim.json"), "--lens-dir", str(tmp / "none"),
                            "--pr", "7", "--head-sha", self.head, "--base-ref", "main", "--run-id", "42",
                            "--out", str(tmp / "record.json"))
        self.assertEqual((0, ""), (done.returncode, done.stdout))
        self.assertFalse((tmp / "record.json").exists())

    def test_trusted_run_exit_code(self):
        tmp = Path(self._tmp.name)
        for run, expected in [(trusted_run(), 0), (trusted_run(event="pull_request"), 1)]:
            (tmp / "run.json").write_text(json.dumps(run), encoding="utf-8")
            done = self.run_cli("trusted-run", "--run", str(tmp / "run.json"), "--repository", REPO,
                                "--default-branch", "main")
            self.assertEqual(expected, done.returncode)


if __name__ == "__main__":
    unittest.main()
