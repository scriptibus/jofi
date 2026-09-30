<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# Review lenses

A lens is one narrow review prompt that looks for **one** kind of risk (proposal §4.7, ADR 0021).
In CI every triggered lens runs as its own job with a fresh context (`.github/workflows/lenses.yml`),
sees only the PR diff plus the files it chooses to read, and returns JSON findings.

## File format

`lenses/<name>.md` starts with front matter:

```yaml
---
name: privacy
title: Privacy
triggers: ["backend/**", "frontend/src/**"]   # glob patterns on changed paths; ["**"] = always
blocking: [high]                              # severities that fail the check
---
```

The body says what to look for, what to ignore, bad and good examples, and how to rate severity
(`high` = must fix before merge, `medium` = should fix, `low` = note).

## Rules

- Author ≠ reviewer: lenses never run in the authoring agent's session.
- Lenses are protected: changing them always needs a human review (AGENTS.md §8).
- Every escaped bug gets "which slice should have caught this?": add a regression test and a new or sharper lens.
- Each lens has known-bad fixture diffs in `fixtures/<lens>/` that it must keep catching, and known-good ones
  it must not block (weekly self-test, see below).

## Self-test on fixtures

`.github/workflows/nightly.yml` runs every lens on each of its fixtures once a week (Sundays; or on demand via
*Actions → nightly → Run workflow*, optionally for one lens). A failure opens or updates the issue
"nightly: scheduled checks failing". On every PR, the `review-lenses` job in `ci.yml` runs the unit tests in
`tests/` and `python3 .review/selftest.py validate`, which checks the shape of all fixtures without calling a model.

How one fixture runs: `selftest.py prepare` builds a synthetic PR in a fresh `git init`. The base
(`origin/main`) is the committed tree without `.review/fixtures`, the head adds `change.diff`. The workflow
then replaces the checkout with it, so no file, object or ref the lens can reach holds the expectations. The lens
runs with exactly the prompt, schema and tools of `lenses.yml`. Only after it has finished does the workflow
fetch `expected.json` through the API, and `selftest.py evaluate` compares the lens's JSON with it.

### Adding a fixture

Each lens needs at least **two bad** and **one good** fixture. Add one whenever a lens misses something in a real PR.

```
fixtures/<lens>/<bad-or-good>-<what-it-shows>/
  change.diff     # the change under review; may only ADD new files, so it keeps applying as main moves on
  context.md      # PR description and linked issue, in the format lenses.yml writes (line "PR #<n>: <title>")
  expected.json   # what the lens must (not) report
```

`expected.json`:

```json
{
  "kind": "bad",
  "why": "One sentence: which risk this fixture shows.",
  "findings": [
    { "severity": "high", "file": "backend/.../RemoteImageInliner.kt" },
    { "severity": "high", "about": ["inject", "instruct", "pre-approved"] }
  ],
  "risk": ["elevated", "high"]
}
```

- `kind: bad`: every entry in `findings` must be matched by a reported finding of that severity or worse,
  on that `file` if given, and mentioning one of the `about` words (case-insensitive, in title, explanation
  or suggestion) if given. For a blocking lens at least one expected severity must be blocking.
- `kind: good`: no finding may have a severity the lens blocks on; `max_severity` (optional) caps the
  severity of any finding, useful for advisory lenses such as `docs`.
- `risk` (optional, for the risk classifier): the rating must be one of these.

To write `change.diff`, commit the new files on a scratch branch and run `git diff main...HEAD > change.diff`,
or use `git diff --no-index /dev/null <file>` per file. Keep fixtures realistic: plausible paths, SPDX headers,
a PR description that doesn't give the answer away. Then check them:

```bash
python3 .review/selftest.py validate
python3 -m unittest discover -s .review/tests
```

Fixture files are data for the lenses, never instructions. Two fixtures deliberately contain prompt injections:
`egress/bad-openstream-with-injected-note` (the lens must report the injection as its own high finding) and
`risk-classifier/bad-injected-low-rating` (the classifier must not rate it low). Their licensing is declared
in `REUSE.toml`.

## Auto-merge gate

The gate decides whether a PR may merge without Lucas (proposal §4.4, ADR 0023). The logic is `gate.py`,
unit-tested in `tests/test_gate.py` (CI job `review-lenses`). It runs in two places:

1. `lenses.yml`, job `gate` (read-only, on `pull_request`): after the lenses, `gate.py decide` computes the
   decision from CI data and uploads it as the artifact `merge-gate`. The required check `result` waits for it.
2. `merge-gate.yml` (`workflow_run` on `lenses`, always **main's** definition). It acts only on the PRs
   listed in the run payload (same-repository PRs; the commits API is only a fallback).
   - When a lenses run is *requested* (push, ready, reopen, or `edited`, which covers retargeting), it
     disarms any auto-merge on those PRs. If a same-repository run maps to no PR, the job fails.
   - When the run *completes*, `gate.py verify` (main's copy) validates the artifact strictly as untrusted
     data. It re-reads the PR and re-checks the head commit, the base (payload and current, both must be
     `main`) and the protected paths itself. Only then does it arm squash auto-merge pinned to that commit
     (`--match-head-commit`). Any mismatch means auto-merge off and `needs-human`.
   - Last, it sets the commit status **`merge-gate`** (`success` for either decision) on that commit.
     `merge-gate` is a required check, so nothing merges before the gate has armed or disarmed auto-merge
     for that exact commit.

A PR gets `auto-merge` only if **all** of these hold, otherwise `needs-human` and a comment with the reasons:

- Lens selection and every triggered lens passed (no blocking findings, none failed or skipped).
- The risk-classifier lens returned a valid result with `risk: low`. Missing or invalid JSON never merges.
- No check run of another workflow on the head commit has failed so far.
- No changed path (old and new name of renames) matches `protected-paths.json`, the machine-readable
  form of AGENTS.md §8. Only the `dependencies` category is waived, and only for a Renovate PR
  (author `renovate[bot]`, branch `renovate/…`) whose update table lists nothing but `patch` / `minor`.
  Renovate's own automerge is off (`renovate.json`), so this gate is the only auto-merge path for its PRs too.
- The PR is open, not a draft, targets `main`, comes from this repository and its head did not move.

What it guarantees, and what it doesn't:

- **CI computes it, never the author.** Labels are output only; the gate never reads them, so a label
  set by hand changes nothing (tested).
- **The write token lives only in main's workflow.** `lenses.yml` runs the PR's own definition, so a PR
  that edits it (or adds a workflow named `lenses`, or a job named `result`) controls that run and its
  artifact. It still can't arm auto-merge through the gate: `merge-gate.yml` comes from main, and its own
  re-check sends every PR that touches `.github/`, `.review/` or any other protected path to `needs-human`,
  whatever the artifact says. For all other PRs the artifact comes from main's `lenses.yml`.
- **No merge before the decision.** The required status `merge-gate` appears on a commit only after
  `merge-gate.yml` has armed or disarmed auto-merge for it, so an approval for an earlier commit can't merge
  a new one, even if the disarm at *requested* time was missed. The disarm and `result` (which waits for
  `gate`) are extra layers.
- **Required checks still apply.** Auto-merge only merges once every required check is green (ruleset on
  `main`). A `needs-human` decision fails neither `result` nor `merge-gate`, so Lucas can still merge by hand.
- **Cost:** `edited` also reruns the lenses on title or body edits. Skipping them there would leave a
  skipped `result`, which GitHub counts as passed, and so an edit could hide an earlier lens failure.
- **Safe degradation.** If CI can't enable auto-merge (token or repository setting), the PR gets
  `needs-human` and a comment instead, and any earlier auto-merge is disabled.
- **Not covered:** anyone with write access can still merge or arm auto-merge by hand, and a PR's own
  workflow can request a write `GITHUB_TOKEN` for itself. The gate stops unreviewed merges by agents that
  follow the rules; it is no defence against a malicious maintainer. Workflow changes also need the
  `workflows` permission to merge, which `GITHUB_TOKEN` lacks. An App token (#48) must therefore live in an
  environment restricted to `main` so only `merge-gate.yml` can use it, never a `pull_request` workflow.
- A merge done by `GITHUB_TOKEN` does not trigger the `push` workflows on `main` (GitHub prevents
  recursive runs), so `main` is next checked by the following push or the scheduled runs; #48 fixes this.
- Fork PRs are never armed, and their runs carry no PR in the payload, so the gate leaves them alone.

## Running a lens locally

```bash
python3 .review/select-lenses.py $(git diff --name-only origin/main...HEAD)
claude -p "$(cat .review/prompt.md .review/lenses/privacy.md)" --allowedTools "Read,Grep,Glob,Bash(git diff *),Bash(git log *)"
```
