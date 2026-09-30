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
- **Cost:** `edited` (title or body edits) still starts a full lenses run, because a skipped `result` counts
  as passed and could hide an earlier lens failure. The lenses in it replay their recorded results instead of
  calling Claude (see "Reusing lens results"), so the run costs runner minutes, not quota.
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

## Reusing lens results

Lenses cost quota, so a lens runs again only when what it reviews changed. A rebase, an "update branch"
merge or a title/body edit replays the earlier result instead (`lens_reuse.py`, tests in `tests/test_lens_reuse.py`).

**Fingerprint.** `lens_reuse.py fingerprint` hashes:

- the PR's diff `git diff <base>...<head>` (from the merge base, with fixed diff options so no git config or
  `.gitattributes` driver changes it), with hunk line numbers and blob ids removed. Context lines stay, so a
  change that lands in different surroundings (main edited the lines around it) is reviewed again;
- the base branch name, so retargeting a PR reruns everything;
- the blob ids of `prompt.md`, `findings.schema.json`, `select-lenses.py` and `lenses.yml` on the base commit.

Each recorded result is also bound to the blob id of its own lens file, so sharpening one lens reruns only
that lens.

**Flow.**

1. `lenses.yml`, job `select`: computes the fingerprint (base = first parent of the merge commit the lenses
   see) and uploads it as the artifact `review-fingerprint`. It looks for an artifact named
   `lens-reuse-<pr>-<fingerprint>` and accepts it only from a run of `.github/workflows/merge-gate.yml` on the
   `workflow_run` event on `main` in this repository (`lens_reuse.py trusted-run`). `lens_reuse.py plan`
   attaches every valid recorded result to its lens in the matrix.
2. Job `lens`: a lens with a recorded result skips checkout, context and the Claude step. *Evaluate findings*
   replays the recorded output exactly like a fresh one, so blocking findings fail the job again, the step
   summary shows the findings and the run they came from, and the result is uploaded again for the gate.
3. `merge-gate.yml`, job `record` (main's definition): downloads `review-fingerprint` and the `lens-*`
   results of the finished run as untrusted data, fetches the head commit from the payload (objects only,
   nothing checked out or run) and runs `lens_reuse.py record`, main's copy. It takes the claimed base only if
   it is a commit on `main`, **recomputes the fingerprint itself**, keeps only well-formed results of lenses
   that exist on that base, and uploads them as `lens-reuse-<pr>-<fingerprint>` (30 days).

**Rules.**

- Results are reused per lens and whatever they found: a lens that failed with blocking findings fails
  again. A lens that produced no result is never recorded, so it runs again on the next event.
- Nothing about the required checks changes: every event still gives a full run with `lens (…)`, `gate` and
  `result` for the head commit, and `merge-gate.yml` still decides and sets `merge-gate` for it.
- A manual re-run (*Re-run jobs*, `run_attempt > 1`) never reuses: it is the way to retry a lens.
- `edited` without a base change keeps the fingerprint and replays; a base change reruns. An edit while a
  run is still in progress cancels that run (concurrency), so its unfinished lenses run again.
- Drafts run no lenses and record nothing.

**Why this is safe.** Records live only in artifacts of main's `merge-gate.yml`, which no PR can change or
upload to (a PR's own workflows run on `pull_request` or `push`, and `trusted-run` rejects those). The
fingerprint is computed there from the head commit in GitHub's payload, never read from the PR's run, so a
run can't file results under the fingerprint of a diff it pushes later. The lens results themselves come
from the PR's run and are therefore only as trustworthy as that run: for a PR that doesn't touch
`.github/` or `.review/` that is main's `lenses.yml`; a PR that does can fake its own results anyway, and a
record made from its run matches only the very same diff, which the gate sends to `needs-human` as a
protected path. What reuse gives up: a lens that also read files outside the diff doesn't see main's newer
versions of them after a rebase, and PR description edits don't trigger a new review. Push a change or
re-run the lenses for a fresh review.

## Running a lens locally

```bash
python3 .review/select-lenses.py $(git diff --name-only origin/main...HEAD)
claude -p "$(cat .review/prompt.md .review/lenses/privacy.md)" --allowedTools "Read,Grep,Glob,Bash(git diff *),Bash(git log *)"
```
