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
- Each lens gets known-bad fixture diffs in `fixtures/<lens>/` that it must keep catching (weekly self-test,
  added with the nightly workflow).

## Running a lens locally

```bash
python3 .review/select-lenses.py $(git diff --name-only origin/main...HEAD)
claude -p "$(cat .review/prompt.md .review/lenses/privacy.md)" --allowedTools "Read,Grep,Glob,Bash(git diff *),Bash(git log *)"
```
