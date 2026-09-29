# Jofi: kickoff guide (from planning to parallel agents)

Version 1.0, 2026-09-29. Claude Code commands checked against the current docs at code.claude.com on 2026-09-29. Re-check them if something doesn't match your version (`claude --version`, then `claude update`).

**Short answer:** yes, you can ask Claude to do almost all of this. You do the handful of things only you can do (accounts, secrets, clicks in GitHub settings), and Claude does the rest. Two ways to work:

- **A: locally with Claude Code** in a terminal (steps below). Best for watching agents work and using the headless browser on your machine.
- **B: here in this project.** Create the GitHub repo and add it to this project (Project settings → Repositories). After that, every thread here can work on the repo as its own agent: "work on issue #12" in the project chat starts one. A and B can be mixed.

---

## Step 1. What you do yourself (≈30 min, once)

| # | Task | How |
|---|---|---|
| 1 | Install tools | git, Docker (with Compose), **JDK 25** (e.g. via SDKMAN), **Node LTS + pnpm**, GitHub CLI `gh`, **Claude Code** (native installer from code.claude.com). Log in: `gh auth login`, then `claude` once |
| 2 | Create the repo | `gh repo create <name> --public --license agpl-3.0 --clone` (repo name is still open) |
| 3 | Put the planning files in | Download the four files from this project's `planning/` folder into `docs/spec/` in the repo and commit. (Or use way B and let a thread do it.) |
| 4 | Claude GitHub App + token | In Claude Code inside the repo: `/install-github-app`. For the review lenses on your subscription: run `claude setup-token` and store the result as repo secret `CLAUDE_CODE_OAUTH_TOKEN` (`gh secret set CLAUDE_CODE_OAUTH_TOKEN`) |
| 5 | GitHub settings Claude can't click for you | Enable secret scanning + push protection, Dependabot alerts, "Allow auto-merge", "Automatically delete head branches"; install the Renovate app. Branch protection can be applied by Claude via `gh api` once the CI checks exist (step 3 below) |

## Step 2. Set up Claude Code in the repo

Start `claude` in the repo folder and accept the trust prompt. Then:

**Plugins** (official marketplace `claude-plugins-official`; it's available by default):
```
/plugin install kotlin-lsp@claude-plugins-official        # Kotlin code intelligence (needs the kotlin-lsp binary; the plugin tells you)
/plugin install typescript-lsp@claude-plugins-official    # frontend code intelligence
/plugin install playwright@claude-plugins-official        # headless browser for agents (Playwright MCP)
/plugin install context7@claude-plugins-official          # current library docs, supports the "latest versions & docs" rule
/plugin install security-guidance@claude-plugins-official # security warnings while editing
/plugin install pr-review-toolkit@claude-plugins-official # review agents (tests, error handling, type design...)
/plugin install code-review@claude-plugins-official       # multi-agent PR review
/plugin install commit-commands@claude-plugins-official   # commit / push / PR shortcuts
/plugin install feature-dev@claude-plugins-official       # explore → design → implement workflow
/plugin install claude-md-management@claude-plugins-official  # keeps CLAUDE.md / AGENTS.md current
```
Install them at **project scope** when asked, so they're recorded in `.claude/settings.json` and every worktree and every agent gets them.

**Skills** from Anthropic's skills repo (for `webapp-testing` and `skill-creator`, the latter useful for writing our lenses):
```
/plugin marketplace add anthropics/skills
/plugin install example-skills@anthropic-agent-skills
```

Optional later: `semgrep` or `sonarqube` plugins for in-loop security/quality feedback, `hookify` to turn repeated corrections into hooks.

## Step 3. Let Claude bootstrap the repo (first agent, one session)

Press Shift+Tab until you're in **plan mode**, then give it the job. Suggested prompt:

> Read docs/spec/ (especially 04-tech-stack-proposal.md and 05-kickoff-guide.md). Plan M0 step 1 from section 5 of the proposal: AGENTS.md + CLAUDE.md with our rules (hexagonal architecture, clean-code rules, "always check latest versions and docs", headless-browser verification, definition of done), .claude/settings.json with hooks, Gradle multi-module skeleton with architecture tests, frontend skeleton, CI + security workflows, issue/PR templates, the first review lenses, ADRs for every decision in the proposal. Look up current versions for everything first. Show me the plan, then implement it on a branch and open a PR.

Claude creates, among other things, **hooks** in `.claude/settings.json` so formatting and checks run automatically. Example (official format):
```json
{
  "hooks": {
    "PostToolUse": [
      { "matcher": "Edit|Write",
        "hooks": [{ "type": "command", "command": ".claude/hooks/format-and-check.sh" }] }
    ],
    "Stop": [
      { "hooks": [{ "type": "command", "command": ".claude/hooks/affected-tests.sh" }] }
    ]
  }
}
```
(The script reads the edited file path from the JSON on stdin, runs Spotless/detekt or Biome on it, and a failing exit code sends the error back to the agent.)

You review and merge this first PR yourself. It sets the rules for every agent after it. Afterwards, ask Claude to apply branch protection with the new CI checks as required (`gh api`).

Also add `.claude/worktrees/` to `.gitignore` (the bootstrap should do it).

## Step 4. Turn the spec into issues

> Create GitHub issues for M0 (rest) and M1 from docs/spec, using our issue template: goal, acceptance criteria, spec section, bounded contexts in scope, out of scope. Put the "contracts" issue for each milestone first and mark the others as depending on it. Add labels per context and milestone.

Skim the issues and fix anything that's wrong. They are the agents' contracts.

## Step 5. Run agents in parallel

Pick whichever fits, in this order of simplicity:

1. **One terminal per issue, each in its own worktree** (stable, recommended to start):
   ```
   claude --worktree issue-12
   claude --worktree issue-13     # second terminal
   ```
   Each gets its own checkout under `.claude/worktrees/<name>/` on branch `worktree-<name>`. Tell it: "Implement issue #12 per AGENTS.md, verify in the headless browser, open a PR." (Our convention prefers `agent/<issue>-<slug>` branch names; the bootstrap can set that up or agents can rename before pushing.)
2. **Background sessions from one screen** (research preview): `claude agents` opens agent view; type a task and press Enter to start a background session, or run `claude --bg "implement issue #14"` from the shell. The view shows which sessions are working, need input or are done.
3. **Agent teams** (experimental, off by default: set `CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS=1`): one lead session splits a milestone into tasks and spawns teammates. Powerful but token-hungry. Use it later, once the rules are proven.
4. **Way B, here in the project:** post an issue number in the project chat and a thread takes it on in the cloud.

Start with **2–3 agents at once**, all on different bounded contexts, after that milestone's contracts PR has merged. Scale up when the review lenses and CI are catching problems reliably.

## Step 6. Daily loop

1. Agents open PRs. CI and the review lenses run on each one.
2. Low-risk PRs that pass everything merge automatically (gate in proposal 4.4). The rest show up for your review.
3. When a bug slips through: "Which slice should have caught this?" → Claude adds a regression test plus a new or sharper lens.
4. Every few days: ask a session to update the issues for the next milestone and check docs drift.

---

## Who does what

| You | Claude |
|---|---|
| Install tools, create the repo, `/install-github-app`, `claude setup-token` + secret, GitHub setting clicks, approve plugin installs | Repo skeleton, AGENTS.md/CLAUDE.md, hooks, CI + security workflows, lenses, ADRs, branch protection via `gh`, issues from the spec, all implementation PRs |
| Review and merge the bootstrap PR and every non-low-risk PR | Headless-browser verification, fixing CI and review findings, keeping docs current |
| Decide product questions | Ask you when a question changes your goal; otherwise pick a sensible default and say so |
