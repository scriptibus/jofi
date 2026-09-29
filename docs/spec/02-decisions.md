# Jofi: decisions log

## Round 1 (2026-09-29, Lucas)
- **Audience:** self-hostable, primarily for Lucas, but open source on GitHub for others to run. Single-user per instance.
- **Deployment:** Docker Compose; local-first (laptop / home server). No cloud dependency.
- **AI providers:** pluggable via a setup agent. Start with Claude, OpenAI, Google, Mistral, and an OpenAI-compatible endpoint (Ollama, LM Studio, vLLM...). Setup shows info on ZDR / no-training policies, plus a disclaimer that users must verify it themselves.
- **Scope:** build the full version, delivered in milestones. No minimal MVP.
- **Email integration:** not a priority; chat paste always works. Optional IMAP adapter in a later milestone if cheap.

## Round 2 (2026-09-29, Lucas)
- Agreed: localhost-only default + simple login; per-task model choice; capability checks; dated provider-privacy info file.
- **Voice:** interview training needs streaming-capable STT and TTS (provider capability).
- **Email:** accepted that LinkedIn/StepStone/Indeed come in via pasted links until the late IMAP milestone.
- **New requirement: language and tone awareness.** Per application track the posting language, the language we apply in, and the tone of the posting (personal vs professional; German Du vs Sie). Generated documents follow it.
- **Profession:** generic, no profession-specific tuning. If ever needed, IT first.
- **Want criteria:** defined by the user in the Knowledge interview (defaults: salary, remote share, commute, industry, company size, tech/role), plus user-defined knockouts.
- **Documents:** ship templates AND allow reproducing the user's own design.
- **Chat autonomy:** create/edit freely; confirm before deleting or anything outward-facing.
- **Mobile:** installable web app (PWA).

## Round 3 (2026-09-29, Lucas)
- Status pipeline accepted: Discovered → Shortlisted → Preparing → Applied → Interviewing → Offer; terminal Accepted / Rejected / Withdrawn / Declined / Ghosted.
- Companies are their own entity (contacts, research, many applications).
- Interview training: application-specific + generic mode; types HR/behavioural, technical, salary negotiation, self-intro pitch.
- Notifications: web push only (no ntfy/Telegram/email for now).
- UI: German + English from day one.
- Full spec written: 03-requirements-spec.md

## Round 4 (2026-09-29, Lucas, tech-stack thread)
- **Notifications removed** from the requirements entirely (supersedes "web push only" from round 3). M6 is now "Email & polish".
- **PWA stays in M0** (installable, share sheet), because it's much cheaper to build in from the start than to add later.
- **Backend: not JavaScript/TypeScript.** Lucas wants a strongly validatable language with classic OO, hexagonal architecture, separated domain layers, design patterns and clean-code-style rules. Candidates: Kotlin (GraalVM curious) or C#. Frontend stays TypeScript. See 04-tech-stack-proposal.md.
- **Database:** PostgreSQL.
- **Merge gate:** low-risk PRs may auto-merge when all checks are green; everything else Lucas reviews.
- **Review lenses in CI** run on Lucas's Claude subscription token.

## Round 5 (2026-09-29, Lucas, tech-stack thread)
- **Backend: Kotlin + Spring Boot on the JVM** (JDK 25 LTS with AOT cache). GraalVM native image not now; may be evaluated later via a nightly build.
- **Rule: agents always check current versions and current official docs** for anything new they add (dependencies, images, actions, APIs), never relying on training data. Enforced via AGENTS.md, PR description listing versions + doc links, a freshness lens, deprecation warnings as errors, and Renovate. See 04-tech-stack-proposal.md section 4.10.
- Tech-stack proposal is now v1.0 (decided). Still open: license, repo name.

## Round 4 (2026-09-29, Lucas)
- Scores on a 0–5 scale. Default threshold: Want + Fit ≥ 7 (combined), configurable.
- Ghosted after 14 weeks without answer (configurable).
- License: wants no commercial exploitation and contributions flowing back. Decided: **AGPL-3.0**.
- License AGPL-3.0 reflected in the tech-stack proposal. Default scanner schedule proposed in 04-tech-stack-proposal.md section 7 (BA 2x/day, ATS and page watcher daily, change check daily; editable per scanner).

## Round 6 (2026-09-29, Lucas)
- **AI-verifiable UI:** everything must be testable by AI through a headless browser (Playwright MCP during development, Playwright e2e with a fake AI provider in CI). Spec section 13, proposal 4.8a.
- **External MCP server:** optional, off by default; lets clients like Claude Desktop replace Jofi's own chat. Same tools, server-enforced confirmations. Spec 9.1, proposal 4.8b.
- Kickoff guide written: 05-kickoff-guide.md.

## Round 7 (2026-09-29, Lucas)
- **UI foundation:** React Aria Components + Tailwind v4 + own design tokens, own component library in Storybook (proposal 3.4).
- License compliance and GitHub workflow layout added (proposal 4.6a/4.6b).
- **Design phase starts now** (before the move to Claude Code): taste questions → 2–3 clickable visual directions → Lucas picks → tokens + core components. Afterwards Lucas and Claude do the move to Claude Code together (05-kickoff-guide.md).
- Design taste recorded in 06-design-brief.md (crisp but airy, light+dark per system, neutral + one strong accent, characterful headings, sharp shapes, noticeable motion, professional companion, donkey mascot as logo and loader). Three directions prototyped: https://claude.ai/artifact/49fgnfv8NCPp394aP6ieFR
- **Design direction B · Stall chosen**; accent colour user-configurable from curated presets (Saffron default, Cobalt, Teal, Plum, Ink). See 06-design-brief.md.
