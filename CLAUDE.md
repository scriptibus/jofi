<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# CLAUDE.md

All project rules live in AGENTS.md so that every coding agent reads the same file:

@AGENTS.md

## Claude Code specifics

- Hooks in `.claude/settings.json` format and lint every file you edit and run the affected tests before
  you finish. A failing hook is feedback for you: fix the cause, don't bypass the hook.
- Use the Context7 MCP server (plugin `context7`) to read current library docs before using an API.
- Use the Playwright MCP server (plugin `playwright`) to click through UI changes and take screenshots.
- Work in a worktree per issue (`claude --worktree <name>`), then rename the branch to `agent/<issue>-<slug>`
  before pushing.
