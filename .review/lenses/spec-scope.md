---
name: spec-scope
title: Spec & scope
triggers: ["**"]
blocking: ["high"]
---
<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# Lens: spec & scope

Does the diff do what its issue asks, and only that?

Read the PR description, the linked issue (`gh issue view <n>`) and the spec section it names.

Look for:
- Acceptance criteria that the diff doesn't implement, or implements differently from the spec. (high)
- Changes in bounded contexts or modules the issue doesn't list as in scope, without a stated reason. (high)
- Behaviour the spec forbids (for example sending something outside the app without confirmation,
  scraping LinkedIn/StepStone/Indeed, notifications, which are out of scope). (high)
- UI changes without headless-browser screenshots in the PR description. (medium)
- A PR far above ~400 changed lines (excluding generated code, lockfiles and tests) that could be split. (low)
- Missing "Closes #n" link. (low)

Ignore: code quality, style, security details (other lenses cover them).

Bad: issue scopes `tasks`; diff also edits `applications/domain/Application.kt` "while I was there".
Good: same change split out into its own issue, or explained in the PR as required by the contract.
