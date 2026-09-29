# Jofi: tech stack & agentic dev setup (proposal)

Version 1.0, 2026-09-29. Status: **decided** (see [02-decisions.md](02-decisions.md) rounds 4–5): Kotlin + Spring Boot on the JVM (no GraalVM native for now), TypeScript/React frontend, PostgreSQL, low-risk auto-merge, lenses on the Claude subscription token. License: **AGPL-3.0** (decided in the requirements thread). Only the repo name is still open (section 6).

> **Version note:** every version named in this file (Kotlin 2.x, Spring Boot 4, JDK 25, Postgres 17, …) reflects what was known on 2026-09-29. Per rule 4.10, re-check each one against its official release page when M0 starts, and take the latest stable.
Inputs: [03-requirements-spec.md](03-requirements-spec.md), [02-decisions.md](02-decisions.md).

---

## 1. Guiding idea

Lucas wants the backend to be **strongly validatable** and written in **classic OO** style: hexagonal architecture, a clean domain layer, known design patterns, and clean-code rules (a lighter version of the original). The stack should make those rules **mechanically enforceable**, so coding agents can't drift from them quietly:

- The compiler catches what it can: null safety, sealed types, exhaustive `when`, type-safe SQL.
- The build catches layer violations: the domain module simply has no framework on its classpath.
- Architecture tests and static analysis catch the rest. Review lenses cover what tools can't judge.

## 2. Backend language: Kotlin vs C#

| | **Kotlin + Spring Boot** (recommended) | C# + ASP.NET Core |
|---|---|---|
| Validatability | Null safety is part of the type system (compile errors); sealed classes + exhaustive `when` for states such as the status pipeline; data/value classes | Nullable reference types are warnings (can be made errors); records, pattern matching. Very close |
| Classic OO / hexagonal | Excellent. Gradle modules give **hard** layer boundaries (domain can't even see Spring); ArchUnit + Konsist + Spring Modulith check the rest | Excellent. Clean Architecture templates are very widespread; projects give hard boundaries; ArchUnitNET / NetArchTest |
| AI providers (all 5 + local) | **Spring AI**: Anthropic, OpenAI, Gemini, Mistral, Ollama/OpenAI-compatible, embeddings, structured output, tool calling, pgvector store | Microsoft.Extensions.AI: OpenAI/Azure/Ollama first-class; Anthropic, Gemini and Mistral less mature |
| MCP | Official Java SDK (co-maintained with Spring) + Spring AI MCP server/client starters; official Kotlin SDK (JetBrains) | Official C# SDK (Microsoft), good |
| Domain libraries | JGit (knowledge in git), Apache Tika (CV/Zeugnis extraction incl. OCR hook), PDFBox (merge, inspect) | LibGit2Sharp, PdfPig; weaker document extraction |
| Agent fluency | Good. Agents sometimes write Java-style Kotlin or overuse `!!`; detekt rules stop that | Very good, lots of Clean Architecture training data |
| Build speed (agent feedback loop) | Slower (Gradle + Kotlin compiler); mitigated with build/config cache and per-module tests | Faster |
| Native binary | GraalVM native image (see 2.1) | Native AOT, more mature, but EF Core + AOT still limited |

**Decision: Kotlin + Spring Boot on the JVM.** C# was a close second and a perfectly good choice. The deciding factor is Spring AI's native coverage of all five providers plus MCP, together with Tika and JGit. That covers exactly the hard parts of Jofi. Ktor was considered and rejected: lighter, but you'd rebuild what Spring AI and Spring Modulith give for free.

### 2.1 GraalVM native image: not now (decided)
- **Pros:** ~100 MB RAM instead of ~300–500 MB, startup in milliseconds, small image.
- **Cons:** native builds take several minutes and need a lot of RAM (slow agent loop, slow CI). Reflection-heavy libraries (Spring AI, jOOQ, Tika) need extra hints, and some break at runtime only in native mode.
- **For a long-running, single-user home server, the gains don't matter much.** Plan: run on the **JVM (JDK 25 LTS) with the AOT cache** (Project Leyden) for faster startup. Optionally add a nightly native build job later, to see whether it's viable before switching.

## 3. Stack

| Area | Choice | Why |
|---|---|---|
| Backend | **Kotlin 2.x, Spring Boot 4, JDK 25**, Spring MVC on **virtual threads** | Blocking code on virtual threads: simple for agents, scales fine. No reactive/coroutine complexity |
| Build | Gradle (Kotlin DSL), version catalog, dependency locking, build + configuration cache | Reproducible, cacheable |
| Architecture | **Hexagonal**: Gradle modules `domain` → `application` (use cases + ports) → `adapters/*` → `bootstrap`. Bounded contexts (applications, companies, knowledge, documents, scanners, chat, training, tasks, setup) as packages, verified by **Spring Modulith** | Layer rules enforced by the build itself, context rules by tests |
| Persistence | **PostgreSQL 17** + pgvector + pg_trgm; **Flyway** migrations; **jOOQ** with code generated from the migrated schema (Testcontainers at build time) | Type-safe SQL: a schema change breaks compilation where it matters. The domain stays free of persistence annotations (no JPA entities leaking into the domain, no lazy-loading traps) |
| Background jobs | **JobRunr** (Postgres storage, retries, cron, built-in dashboard) in a separate `worker` container from the same image (Spring profile) | Job log visible to the user, no Redis |
| AI | **Spring AI** behind our own ports (`LlmPort`, `EmbeddingPort`, `SpeechToTextStreamPort`, `TextToSpeechStreamPort`) | Capability checks, per-task routing, cost tracking and the "never send to AI" filter live in the application layer; Spring AI types stay in one adapter |
| Voice streaming | Spring WebSocket between browser and server; one streaming adapter per voice provider; optional local Speaches container | Keys stay server-side |
| MCP | Spring AI MCP server (Streamable HTTP). The chat agent is an MCP client of our own server | One tool surface for the in-app chat and Claude Desktop |
| Knowledge store | Markdown in a git repo in the data volume, via **JGit**; Postgres holds the rebuildable index + embeddings + proposal queue | As in v0.1 |
| Document extraction | **Apache Tika** (PDF, DOCX; Tesseract OCR as optional profile), vision model as fallback | – |
| PDF | **HTML/CSS templates → Gotenberg** (Chromium) in its own container without network; PDFBox for checks and post-processing | Language-agnostic; LLMs are fluent in HTML/CSS for "reproduce my design"; Gotenberg also merges and converts DOCX |
| API contract | springdoc-openapi → **orval** generates the TypeScript client (types, Zod, TanStack Query hooks). CI fails if the generated client is stale; **oasdiff** flags breaking changes | The frontend gets compile-time safety from the backend's types |
| Auth | Spring Security, single user, argon2id, session cookie; API keys encrypted (Tink/AES-GCM) with a master key generated into the volume | – |
| Frontend | **TypeScript**, React + Vite, TanStack Router/Query, Paraglide i18n (a missing DE/EN key is a compile error); UI layer see 3.4 | – |
| PWA | vite-plugin-pwa, installable + Web Share Target from **M0**. No push | Notifications removed from scope |
| Testing | JUnit 5 + Kotest assertions, MockK, Testcontainers, Spring Modulith tests, Vitest, Playwright e2e against `docker compose` | – |

### 3.4 Design system (decided 2026-09-29)
Goal: a distinctive, really good-looking app that agents can extend consistently.
- **Own look, borrowed behaviour.** Visual language (tokens, components, layouts, motion) is ours. Interaction and accessibility come from **React Aria Components** (Adobe): unstyled, WCAG-grade keyboard/screen-reader/touch behaviour, and locale-aware date, number and calendar handling for DE/EN. Hand-writing comboboxes, date pickers, dialogs and focus management would cost months, and agents get a11y subtly wrong.
- **Styling:** Tailwind CSS v4, driven entirely by **our design tokens** as CSS custom properties (colour incl. dark mode, type scale, spacing, radius, elevation, motion). No arbitrary values in feature code.
- **Component library** `frontend/ui`, owned code, documented in **Storybook** with the a11y addon. Feature code may only use `ui` components and tokens.
- Rejected: fully styled libraries (MUI, Mantine; generic look, fighting overrides); pure hand-written CSS/JS components (unique, but a11y and cross-device cost too high); shadcn/ui (good, but Radix-based, React Aria is stronger on a11y and i18n).
- **Enforcement slices:** lint rules against raw colours/arbitrary values and direct primitive imports outside `ui`; Playwright **visual regression** screenshots of every Storybook story; **axe** accessibility checks (WCAG 2.2 AA) in e2e; viewport matrix (phone, tablet, desktop) + dark mode + reduced motion; a **design lens** reviewing UI PRs against the design principles.
- **Phase D (before M0 UI work):** design principles + moodboard → 2–3 visual directions as clickable prototypes → Lucas picks one → tokens → core components in Storybook → then feature UI.

### 3.1 Docker Compose services
`app` (API + SPA + MCP + WebSocket), `worker` (same image, jobs profile), `db` (Postgres 17 + pgvector), `pdf` (Gotenberg, no outbound network). Optional profiles: `voice-local`, `llm-local` (Ollama), `https` (Caddy).

### 3.2 Phone access
PWA install and the share sheet need HTTPS. Localhost-only stays the default; docs recommend Tailscale, and the Caddy profile is for people with their own domain.

### 3.3 Answers to spec section 15
1. Storage: Postgres (jOOQ/Flyway) for entities, git-backed Markdown (JGit) for knowledge, DB index rebuilt from files.
2. PDF: HTML/CSS via Gotenberg.
3. MCP: Spring AI MCP server; chat = Spring AI ChatClient with MCP tool callbacks against our own server.
4. Spring AI behind own ports; voice over WebSocket with per-provider streaming adapters.
5. JobRunr in a worker container.
6. Dropped (no notifications).
7. pgvector in Postgres.

---

## 4. Development setup for agentic coding

### 4.1 Repository shape
```
backend/
  domain/                pure Kotlin: entities, value objects, domain services, domain events. No Spring, no jOOQ
  application/           use cases (one class per use case), inbound + outbound ports
  adapters/
    web/                 REST controllers, DTOs, OpenAPI
    persistence/         jOOQ repositories, Flyway migrations   ← serialized hot spot
    ai/                  Spring AI adapters (the only place provider types appear)
    mcp/                 MCP tool adapters (thin: call use cases)
    net/                 the only outbound HTTP client (SSRF guard)
    scanners/ pdf/ knowledge-git/ voice/ jobs/
  bootstrap/             Spring Boot app, wiring, config
  architecture-tests/    ArchUnit + Konsist + Modulith verification
frontend/                React PWA, generated API client
docs/  spec/ adr/ threat-model.md
.review/lenses/          one prompt file per lens (+ known-bad fixture diffs)
AGENTS.md, CLAUDE.md (root) + short AGENTS.md per module
```
Gradle enforces the direction: `domain` has no dependencies besides the Kotlin stdlib, `application` depends only on `domain`, adapters depend on `application`, and only `bootstrap` sees everything.

### 4.2 Code-quality rules ("clean code, lighter")
Enforced by **detekt** (build fails), formatted by **ktlint via Spotless**:
- Function length ≤ ~30 lines, cyclomatic complexity ≤ 10, ≤ 5 parameters, class size and nesting limits.
- No `!!`, no generic `catch (e: Exception)` outside adapters, no `lateinit` in domain, no wildcard imports, no magic numbers in domain.
- Domain errors as **sealed result types**, not exceptions crossing ports.

Enforced by **ArchUnit/Konsist tests**:
- Layer direction and module boundaries; no Spring or jOOQ annotations in `domain`/`application`.
- Naming conventions: `*UseCase`, `*Port`, `*Adapter`, `*Repository`.
- Use cases have one public method; controllers and MCP tools contain no business logic (they only call use cases).
- Value objects are immutable; ports are interfaces in `application`.

Encouraged patterns (documented in an ADR with examples): Repository, Factory, Strategy (scoring, providers, scanner adapters), Adapter, Command/use case, Domain events (Modulith event publication registry), State (status pipeline transitions).

### 4.3 Branching & parallelisation
- **Trunk-based.** `main` is protected and always releasable.
- **One issue → one branch → one agent → one PR**, `agent/<issue>-<slug>`, each agent in its own worktree or cloud session.
- **Issues are the contract:** goal, acceptance criteria, spec section, **bounded contexts in scope**, out of scope.
- **Contracts first per milestone:** a small PR lands first with domain model + ports + Flyway migration + OpenAPI shape, then feature PRs run in parallel against it.
- **Serialized hot spot:** only one open PR at a time may add Flyway migrations (CI check). Flyway version numbers are timestamps, which avoids number clashes.
- Squash merge, conventional commits, branch must be up to date before merge, auto-delete branches. Soft limit ~400 changed lines excluding generated code and tests.

### 4.4 Merge gate: what counts as "low risk"
A PR auto-merges only if **all** of these hold:
- All CI checks and all triggered lenses are green, with no blocking findings.
- It touches **none** of the protected paths: Flyway migrations, `adapters/net`, `adapters/ai` privacy filter, auth/crypto, export/import, CI workflows, `.review/lenses/`, Dockerfiles/compose, Gradle/pnpm dependency files (except Renovate patch/minor updates), `AGENTS.md`/`CLAUDE.md`.
- A separate **risk-classifier lens** (fresh context) rates it low risk.
- The label comes from CI, never from the authoring agent.

Everything else waits for Lucas. Changes to lenses, workflows and agent instructions always need a human, so agents can never weaken their own reviewers.

### 4.5 Quality pipeline (cheapest slices first)
| Where | Checks |
|---|---|
| Agent loop (Claude Code hooks) | After edits: Spotless + detekt + compile of the touched module; before finishing: that module's tests + architecture tests |
| Pre-commit (lefthook) | Spotless, gitleaks, Biome for frontend |
| CI on every PR | Gradle build with dependency verification · detekt · ktlint · **architecture tests** · Spring Modulith verify · unit tests · integration tests (Testcontainers Postgres) · Flyway from zero + jOOQ codegen up to date · **OpenAPI client drift + oasdiff** · **Kover diff coverage** · export/import round-trip test · frontend: Biome, `tsc`, Vitest, Paraglide compile · Docker build · Playwright smoke on the compose stack · AI tests on recorded fixtures |
| Nightly / weekly | Full e2e, live-provider prompt evals, **PIT mutation testing** on `domain` + `application`, lens self-test on known-bad fixtures, docs-drift agent, optional GraalVM native build |

### 4.6 Security pipeline
- **Secrets:** gitleaks, GitHub secret scanning + push protection.
- **Code:** CodeQL (Kotlin + TypeScript), Semgrep.
- **Supply chain:** Gradle **dependency verification** (checksums) + dependency locking + wrapper validation; pnpm `minimumReleaseAge` and a script allowlist on the frontend; Renovate; OSV-Scanner over both lockfiles.
- **CI:** actions pinned by SHA, least-privilege `permissions:`, zizmor, OpenSSF Scorecard.
- **Containers:** Hadolint, Trivy, non-root, read-only FS where possible, `pdf` without network. Images on GHCR with SBOM + build attestations.
- **Threat model** (`docs/threat-model.md`): SSRF (all fetches through `adapters/net`), prompt injection from postings/pages/uploads (the scoring pipeline has no tools; the chat can't delete or act outward without confirmation), privacy leaks ("never send to AI", keys, PII in logs).

### 4.6a License compliance (Jofi is AGPL-3.0)
- Every dependency's license must be compatible with AGPL-3.0. Allowlist: MIT, Apache-2.0, BSD-2/3, ISC, MPL-2.0, LGPL, EPL-2.0 (with care), GPL-3.0, AGPL-3.0. Anything else (unknown, proprietary, SSPL, "Commons Clause", GPL-2.0-only) fails the build until a human approves it.
- Gradle: **licensee** plugin (fails the build on disallowed licenses). Frontend: `pnpm licenses list` checked against the same allowlist by a script. PRs: GitHub **dependency-review-action** with `allow-licenses`.
- Docker images: Trivy license scan. Gotenberg, Postgres etc. are used as separate services, not linked.
- Release artifacts include a generated third-party notices file; source files carry SPDX headers (`SPDX-License-Identifier: AGPL-3.0-or-later`), checked by the REUSE tool.

### 4.6b GitHub workflows
Every check in 4.5–4.6a runs in GitHub Actions **and** locally with the same command (`./gradlew check`, `pnpm check`), so an agent sees the same result before pushing that CI will produce.

| Workflow | Runs on | Contents | Required for merge |
|---|---|---|---|
| `ci.yml` | every PR + push to main | build, ktlint, detekt (style, class size, complexity, function length), architecture tests, unit + integration tests, diff coverage, OpenAPI drift, frontend lint/types/tests, e2e smoke, visual regression, axe | yes |
| `security.yml` | every PR + weekly | CodeQL, Semgrep, gitleaks, OSV-Scanner, Trivy, Hadolint, zizmor, dependency-review (vulns + licenses), licensee, REUSE | yes |
| `lenses.yml` | PR marked ready for review | review lenses (matrix, one job per lens), risk classifier, auto-merge gate | yes |
| `nightly.yml` | schedule | full e2e, live-provider evals, PIT mutation, lens self-tests, docs drift, Scorecard | no (opens issues on failure) |
| `release.yml` | tag | images to GHCR, SBOM, attestations, third-party notices | – |

Static analysis also feeds GitHub **code scanning** (SARIF upload from CodeQL, Semgrep, detekt, Trivy), so all findings show up in one place in the Security tab and inline in PRs.

### 4.7 Review lenses (Swiss-cheese layer)
Each lens is one narrow prompt in `.review/lenses/<name>.md`: the single risk it looks for, bad/good examples, trigger paths, severity rules, JSON findings. In CI every triggered lens runs as its **own job with a fresh context** (Claude Code GitHub Action with Lucas's subscription token, matrix per lens) and sees only the diff, the files it needs and its lens.

| Lens | Looks only for |
|---|---|
| architecture & design | Logic in the wrong layer, anemic domain where behaviour belongs on the entity, misapplied patterns, use cases doing two things (the semantic part ArchUnit can't see) |
| privacy | Data reaching an AI provider without the "never send to AI" filter; PII/keys in logs; any telemetry |
| human-in-the-loop | Deletes or outward actions without confirmation; AI writing knowledge without the proposal flow |
| untrusted-input | Posting/page/upload/email content reaching a tool-enabled agent or treated as instructions |
| egress | Outbound calls outside `adapters/net` |
| audit | Mutations that skip the changelog or record the wrong actor |
| portability | New tables/files missing from export/import; destructive migrations |
| provider-agnostic | Provider details leaking out of `adapters/ai`; capabilities used without checks |
| i18n & tone | Hardcoded UI strings; generated content using the UI language instead of the application language / Du-Sie |
| spec & scope | Diff vs issue acceptance criteria and spec; changes outside the declared contexts |
| test adequacy | Tests that don't exercise the acceptance criteria, or that only test mocks |
| docs | README, ADRs, MCP tool docs, AGENTS.md not updated |
| general security | Auth, sessions, crypto, injection |
| freshness | New dependencies not on the latest stable version, deprecated APIs, missing version/doc links (rule 4.10) |
| risk classifier | Rates the PR for the auto-merge gate (4.4) |

Rules: author ≠ reviewer; every escaped bug gets "which slice should have caught this?" → regression test + new or sharper lens; path triggers keep a typical PR to 4–6 lenses; each lens has known-bad fixture diffs it must keep catching (weekly self-test). Because the lenses share Lucas's subscription limits with his own Claude use, they run on ready-for-review PRs only, not on every push to a draft.

### 4.8 Documentation as a slice
Spec and ADRs live in the repo; `AGENTS.md` per module says what it owns and its rules. The docs lens checks every PR, and a weekly doc-gardener agent opens a PR for drift. Every decision in this file becomes an ADR in M0.

### 4.8a AI-verifiable UI (headless browser)
Every user-facing feature must be checkable by an agent driving a headless browser (spec section 13).
- **During development:** agents use the **Playwright MCP server** (official `playwright` plugin) to click through the feature they just built and attach screenshots to the PR. The `webapp-testing` skill (anthropics/skills) adds a scripted variant.
- **In CI:** Playwright e2e tests run against the `e2e` compose profile: app + worker + Postgres + Gotenberg + a **fake AI provider** (deterministic, scripted responses, implements our `LlmPort`) + **WireMock** stubs for BA API and ATS feeds. Seeded demo data, no real keys, no internet.
- **Frontend rules** (lint-enforced where possible): every interactive element has an accessible role and label; `data-testid` only where no role/label fits; no timing-based waits in tests.
- Frontend PRs include before/after screenshots from the headless run; the spec & scope lens checks they exist.

### 4.8b External MCP server
- The Spring AI MCP server (Streamable HTTP) is the only tool surface. The built-in chat uses it internally; external access is a **settings toggle, off by default**.
- External clients authenticate with revocable per-client bearer tokens (OAuth later if clients need it); tokens can be limited to read-only tool groups.
- Confirmation for deletes and outward actions is enforced **server-side** (a destructive tool first returns a confirmation request; MCP elicitation where the client supports it), so external clients can't skip it.
- The privacy filter applies to tool results: "never send to AI" data never leaves via MCP.
- An **MCP contract test** suite runs the MCP Inspector / SDK client against the server in CI, and the human-in-the-loop lens also triggers on MCP adapter changes.

### 4.9 Definition of done
Issue acceptance criteria met · CI green · UI changes verified in a headless browser (4.8a) · no blocking lens findings · docs updated · **versions and docs checked (4.10)** · small and in scope · merged by the gate in 4.4.

### 4.10 Rule: always use current versions and current documentation
Agents' built-in knowledge ends at their training cutoff, so without this rule we would build outdated software. Therefore:
- **Before adding anything new** (a dependency, plugin, Docker image, GitHub Action, framework feature, API or config key), the agent **looks up the latest stable version** at the official source (Maven Central, npm, the project's release page, Docker Hub/GHCR, GitHub releases) and **reads the current official documentation** for that version, including migration guides. It does not rely on memory.
- Preferred tools: a docs MCP server (e.g. Context7) or web fetch of the official docs; the agent notes the version and the doc link it used.
- **Deprecated APIs are not used**, even when they still compile. If the docs mark something deprecated, the agent uses the replacement.
- The **PR description lists every new or bumped dependency with its version and the doc link** consulted.
- This rule is written into the root `AGENTS.md`/`CLAUDE.md`, so every agent session loads it.
- Enforcement slices: a **freshness lens** (flags new dependencies that aren't the latest stable, deprecated API use, and missing doc links); compiler deprecation warnings as errors (Kotlin `allWarningsAsErrors`, `tsc`); a CI report of outdated dependencies (Gradle versions plugin, `pnpm outdated`); Renovate keeps existing dependencies current; OpenRewrite recipes for framework upgrades.

---

## 5. M0 bootstrap order
1. Repo, license, branch protection, issue/PR templates, AGENTS.md, CI + security pipeline skeleton, Gradle multi-module skeleton with architecture tests from day one.
2. Compose stack, Postgres/Flyway/jOOQ, auth, JobRunr, `adapters/net`, AI ports + Spring AI adapter, frontend shell with i18n + PWA + generated client.
3. Lens runner with the first lenses (spec & scope, architecture & design, privacy, egress, docs, risk classifier) and the auto-merge gate.
4. Setup agent, export/backup; then M1 in parallel slices.

## 6. Still open (non-blocking)
- GitHub repo name/owner.

## 7. Default scanner schedule
Scanners run as JobRunr recurring jobs in the `worker`, each with its own cron expression that the user can edit. Defaults:

| Scanner type | Default schedule | Why |
|---|---|---|
| BA Jobsuche API | twice a day, 07:00 and 13:00 local time | New postings appear during business hours; fresh results in the morning and at lunch |
| ATS feeds | once a day, 06:30 | Company career pages change slowly |
| Page watcher | once a day, 06:00 | Fragile and costlier (AI extraction); keeps load on company sites low and respects robots.txt |
| Posting change/offline check (known sources) | once a day, 05:30 | Only for applications not yet in a terminal state |

- Each run gets a random delay of up to 15 minutes, so we never hit a source at an exact, predictable time.
- A missed run (instance was off) runs once at startup, not once per missed slot.
- "Run now" is always available per scanner.
- When the monthly AI budget cap is reached, fetching continues but AI scoring pauses (spec section 3), and findings wait in the queue.
