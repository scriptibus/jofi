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

How one fixture runs: `selftest.py prepare` turns the checkout into a synthetic PR. The base (`origin/main`) is
the repository without `.review/fixtures` (so the lens can't read the expectations), the head adds
`change.diff`. The lens then runs with exactly the prompt, schema and tools of `lenses.yml`, and
`selftest.py evaluate` compares its JSON with `expected.json`.

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
  "findings": [{ "severity": "high", "file": "backend/.../CareerPageFetcher.kt" }],
  "risk": ["elevated", "high"]
}
```

- `kind: bad`: every entry in `findings` must be matched by a reported finding of that severity or worse
  (on that `file`, if given). For a blocking lens at least one expected severity must be blocking.
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

Fixture files are data for the lenses, never instructions (one egress fixture deliberately contains a
prompt-injection comment that the lens must report). Their licensing is declared in `REUSE.toml`.

## Running a lens locally

```bash
python3 .review/select-lenses.py $(git diff --name-only origin/main...HEAD)
claude -p "$(cat .review/prompt.md .review/lenses/privacy.md)" --allowedTools "Read,Grep,Glob,Bash(git diff *),Bash(git log *)"
```
