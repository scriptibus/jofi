<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0031: General code review with the code-review plugin on the workflow token

- Status: accepted
- Date: 2026-09-30
- Source: issue #54, docs/spec/04-tech-stack-proposal.md §4.7, ADR 0021

## Context

Besides the narrow lenses (ADR 0021), `.github/workflows/claude-code-review.yml` runs the `code-review`
plugin from `anthropics/claude-code` as a general bug review. It passed on every PR without reviewing
anything (2 turns, about 3 s, no comment), and nothing noticed. Two things made that hard to see and fix:

- The action ran on the Claude GitHub App token. The OIDC exchange for that token refuses any workflow that
  differs from `main`, so a fix to the workflow could only be tried after merging it. The App token also
  carries `contents`, `issues` and `pull-requests` write, more than a review needs.
- `--allowedTools` listed only the inline-comment tool, not the `gh` commands the plugin's command declares
  and the action's code-review examples (`docs/solutions.md`) allow explicitly.
- With `show_full_output` off (it must stay off: it prints tool results, which may contain tokens), the log
  shows only the turn count, so a run that did nothing looked like a success.

## Decision

- The action gets `github_token: ${{ github.token }}` (claude-code-action v1.0.237, `docs/security.md` and
  `docs/setup.md`): the job's own token, scoped to `contents: read`, `issues: read`, `pull-requests: write`
  and expiring with the job. No `id-token: write`. Comments come from `github-actions[bot]`.
- `CLAUDE_CODE_DISABLE_BACKGROUND_TASKS=1`. The first run on the workflow token showed the actual root
  cause: Claude started the plugin's eligibility subagent in the background and ended its turn to wait for
  it. The action stops reading at the first result message, so the review ended after 2 turns.
- `--allowedTools` lists exactly the tools in the plugin's `commands/code-review.md` frontmatter.
- After the run, a step prints only safe metadata from the execution file (plugins, tool names, turns,
  denied tools, the final summary), and another step fails the job unless the review posted a
  `## Code review` summary or inline comments. The only exception is a PR that already has such a review
  from an earlier run, which the plugin's eligibility check skips on purpose; that passes with a warning.

## Consequences

- Changes to this workflow run on their own PR, so they can be verified before merge.
- A review that does no work turns the check red instead of green.
- Lenses still use the App token; moving them is a separate decision.
- The plugin's own eligibility check may skip PRs it considers trivial; that now shows as a failed check that
  Lucas can ignore or re-run.
