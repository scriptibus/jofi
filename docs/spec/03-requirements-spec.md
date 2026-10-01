# Jofi (JobFinder): requirements & feature spec

Version 1.2, 2026-09-29 (round 4: notifications removed; round 6: AI-verifiable UI, external MCP access). Built from Lucas's initial idea ([01-feature-review.md](01-feature-review.md)) and three interview rounds ([02-decisions.md](02-decisions.md)).
Tech stack is **out of scope** and is decided in the next step. Section 13 lists the questions that step has to answer.

---

## 1. Product summary

Jofi is a self-hosted, AI-assisted job application manager. It finds jobs, scores them against what you want and what you can offer, and builds tailored application documents from a personal knowledge base. It tracks every application through to the offer, and trains you for interviews.

- **Audience:** a single user per instance. Built for Lucas first, published as open source on GitHub for others to self-host.
- **Profession-agnostic:** no hardcoded assumptions about a field. If tuning is ever needed, IT comes first.
- **Scope strategy:** the full product, delivered in milestones (section 14). No throwaway MVP.

## 2. Guiding principles

1. **Local-first & private.** All data stays on the user's instance. AI calls go only to the provider the user configured.
2. **Human in the loop.** Jofi never sends anything outside the app on its own. The AI proposes and the user decides.
3. **Traceable.** Every change to an application, document or knowledge entry is versioned, with who (user / AI / scanner) and why.
4. **Legal sources only.** No scraping of platforms whose terms forbid it (LinkedIn, StepStone, Indeed).
5. **Language- and tone-aware.** Every application knows its language and its form of address, and all generated output follows it.
6. **Bilingual UI.** German and English from day one.

---

## 3. Platform & setup

### 3.1 Deployment
- Runs with **Docker Compose**, on a laptop, NAS or home server.
- By default it listens on **localhost only**. A simple single-user login is required whenever it's exposed on a network.
- All persistent data lives in mounted volumes. One-click **export / backup** (a zip of the database, the knowledge .md files and the documents) and **restore**.

### 3.2 Setup agent (first-run wizard, conversational)
- Walks the user through choosing and configuring AI providers.
- **Supported providers:** Anthropic Claude, OpenAI, Google Gemini, Mistral, and an **OpenAI-compatible endpoint** (Ollama, LM Studio, vLLM, OpenRouter…).
- For each provider it shows **privacy info**: zero data retention (ZDR) options, no-training promises and data location, loaded from a **dated info file with links** to the provider's terms. It always carries a **disclaimer that users must verify these terms themselves**.
- **Per-task model selection.** Each AI task can use a different provider/model, for example:
  - cheap/fast: scanner pre-scoring, classification, language/tone detection
  - strong: knowledge interview, document generation, interview training
  - voice: streaming STT and streaming TTS
- **Capability checks** per provider/model: tool use, streaming, speech-to-text, text-to-speech, context size. Jofi warns when a task is assigned to a model lacking a needed capability (e.g. weak tool use in small local models).
- Local voice options (e.g. Whisper-class STT, Piper-class TTS) are supported through the compatible endpoint or a local service, so voice doesn't require a cloud provider.
- **Cost tracking:** tokens and estimated cost per task, per provider and per month, plus an optional monthly budget cap that pauses non-essential AI jobs (scanner scoring) when reached.

---

## 4. Knowledge module

The single source of truth about the user, feeding scores, documents and interview training.

### 4.1 Storage
- A **Markdown file store** (human-readable, editable outside Jofi, git-friendly) organised into sections such as:
  - Profile & contact data
  - Work history (roles, dates, achievements with numbers)
  - Education, certifications, languages
  - Skills (with self-rated level and evidence)
  - Projects
  - **Preferences** (Want criteria, section 4.3)
  - **Knockouts** (hard exclusion rules)
  - Stories for interviews (STAR format)
  - Current employment situation (e.g. notice period, contract end date → dashboard countdown)
- Uploaded source files (old CVs, Arbeitszeugnisse, certificates) are stored and linked. Their content is extracted into knowledge entries.
- Entries can be flagged **"never send to AI"**. Such entries are used only in deterministic places (e.g. inserting an address into a PDF template).

### 4.2 Knowledge interview
- AI-led interview that builds the knowledge base from scratch or from uploaded documents, and asks follow-ups to fill gaps (e.g. "you list Kubernetes: which project, which scale?").
- Can be resumed at any time, and can be targeted ("interview me about my last job only").
- Also derives knowledge from applications: e.g. a posting asks for X, the user explains experience with X in chat, and that becomes a knowledge proposal.

### 4.3 Want criteria & knockouts
- Defined by the user during the knowledge interview. Defaults offered: salary, remote share, commute/location, industry, company size, role/tech, growth. Users may add or replace criteria and weight them.
- **Knockouts** are deterministic rules (e.g. "salary below X", "on-site only", "relocation", "company Y"). They are evaluated **before** any AI call.

### 4.4 Change control (anti-drift)
- The AI **never edits knowledge silently**. Any action anywhere in Jofi may produce a **knowledge proposal** (add / change / contradicts-existing) that the user accepts, edits or rejects, shown as a diff.
- Every accepted change is **versioned**, with source (which application, chat or interview) and timestamp. Any version can be restored.
- Periodic "challenge" pass: the AI flags stale or inconsistent entries (e.g. skill claimed but never evidenced, outdated salary expectation).

---

## 5. Companies & contacts

- **Company** is its own entity: name, website, industry, size, locations, ATS/careers-page URL, research notes, AI-generated **company profile** (what they do, culture signals, news, public ratings). The user can edit it.
- A company has **many applications** and **many contacts**.
- **Contact persons:** name, role, company, channel details, relationship notes, and history of interactions (linked calls and interviews). A contact can be linked to several applications.
- Company-level **blacklist / favourite** flags feed scanners and knockouts.

---

## 6. Applications module

### 6.1 Entity
Each application (also covers discovered and declined jobs) holds:
- Job title, company (link), location, remote share, employment type, seniority.
- **Status** (section 6.2) with a full timestamped history.
- **Scores:** Want score, Fit score (each on a **0–5 scale**, one decimal, plus sub-scores per criterion), with the **AI overview** explaining both: strengths, weaknesses, standouts, mismatches with the user's wishes. The user can **override** a score with a reason, and overrides are kept as calibration data.
- **Pay band:** min/max/currency/period, with source = *provided in posting* / *estimated (with basis and confidence)* / *told by recruiter*.
- **Language & tone:**
  - posting language (detected)
  - application language (chosen; defaults to posting language)
  - form of address: *Du* / *Sie* / *neutral (EN)*, and overall tone: *personal* / *professional* (detected from the posting, overridable)
  - All generated documents, messages and interview training follow these settings.
- **Sources** (1..n): scanner / URL / manual-chat, with discovery date and original link. The same job found in several places is **one application with several sources** (section 8.4).
- **Job description history:** a full snapshot per source at discovery, on every detected change (with a diff view), and **frozen at the time of applying**. Stored locally, so it survives the posting being taken down.
- **Contacts** (links), **documents used** (frozen copies, section 7.4), **how applied** (portal / email / referral / other) and portal notes.
- **Deadline** (application deadline if known) and **follow-up rules** (e.g. auto-task "follow up" 14 days after Applied with no response).
- **Interviews & calls:** date/time, type (phone screen, HR, technical, case, on-site, final), participants (contacts), preparation notes, the user's notes afterwards, outcome, and a link to training sessions.
- **Offer details** (when reached): salary, bonus, benefits, remote, vacation, notice period, start date, deadline to answer.
- **Decline / rejection reason** (structured category + free text) for both "we decided against" and "they rejected".
- **Changelog:** every change to the application (by user, AI or scanner), readable as a timeline.
- **Unread / new** flag for scanner-created entries.

### 6.2 Status pipeline
`Discovered → Shortlisted → Preparing → Applied → Interviewing → Offer`
Terminal states: `Accepted`, `Rejected` (by company), `Withdrawn` (by user after applying), `Declined` (user decided against, before applying or on offer), `Ghosted` (no answer for **14 weeks** by default, configurable; suggested automatically).
Declined and rejected jobs stay visible and filterable, with their reasons.

### 6.3 Views
- List/table with filters (status, scores, source, company, language, date) and saved views.
- Kanban board by status.
- Detail page with tabs: Overview & scores, Description (with history), Documents, Contacts, Timeline (changelog + interviews + tasks).
- **Offer comparison:** side-by-side of applications in `Offer` state.

---

## 7. Documents module

### 7.1 Document types
CV / Lebenslauf, cover letter / Anschreiben, certificates, Arbeitszeugnisse / recommendation letters, other attachments. Types are extensible.

### 7.2 Defaults and variants
- Each type can have a **default document** (e.g. "Master CV DE", "Master CV EN").
- **Variants** are derived from a default and mapped to one or more applications (e.g. a CV emphasising backend work). A variant records its parent and what was changed.
- Every generated document has a language (DE/EN) and follows the application's form of address and tone.

### 7.3 Generation (PDF engine)
- Generates documents from **knowledge + template + application context** (the posting, the company, language/tone).
- **Templates:** Jofi ships a set of clean templates, **and** the user can bring their own design (e.g. upload an existing CV). Jofi reproduces it as a reusable template.
- Output is **ATS-friendly** (real selectable text, logical reading order, no text in images) and supports photo and signature where customary (DE).
- **Bewerbungsmappe:** merge cover letter + CV + selected certificates/Zeugnisse into one PDF in a chosen order.
- Editing loop: the AI drafts, the user edits (rich text or via chat), and the result is rendered to PDF. Uploaded static PDFs (certificates, Zeugnisse) are stored as-is.

### 7.4 Versioning & frozen snapshots
- Every document is versioned.
- When an application moves to `Applied`, the exact files sent are **frozen** onto the application and can no longer change, even if the source document does.

---

## 8. Scanners module

### 8.1 Adapter types
| Adapter | Covers | Notes |
|---|---|---|
| **Bundesagentur für Arbeit API** | BA Jobbörse | Official public Jobsuche API. Pre-configured default scanner. |
| **ATS feed** | Company career pages on Personio, Greenhouse, Lever, SmartRecruiters, Workday, Recruitee, join.com, … | Public job feeds/APIs; one adapter per ATS. The user adds a company, and Jofi detects its ATS. |
| **Page watcher** | Custom company career pages | Generic fetch + AI extraction; fragile, fallback only; respects robots.txt. |
| **URL / text import** | Any page that may be fetched; for LinkedIn, StepStone and Indeed only pasted text | User pastes a link or text in chat or via the PWA share sheet; Jofi extracts the posting. A link to LinkedIn, StepStone or Indeed (or a redirect into them) is refused: the user pastes the posting's text instead. |
| **Email alerts (IMAP)** | Job-alert mails from LinkedIn, StepStone, Indeed, etc. | Late milestone, optional (section 12). |

No direct scraping of LinkedIn, StepStone or Indeed.

### 8.2 Scanner management
- Create, edit, pause and delete scanners. Each scanner has search parameters (keywords, location + radius, remote, employment type…), a schedule and its own thresholds.
- **Default scanners** are pre-set up after the knowledge interview (e.g. a BA search derived from the user's preferences).
- A run log per scanner shows hits, new items, duplicates, knockouts, errors and AI cost.

### 8.3 Processing pipeline per finding
1. Fetch, then normalise.
2. **Dedup / merge** (section 8.4).
3. **Knockout rules** (deterministic, no AI).
4. **Cheap pre-filter** (keyword / embedding similarity).
5. **AI analysis:** Want + Fit scores, overview, language/tone detection, pay-band estimate.
6. If **Want + Fit ≥ 7** (default; combined threshold on the 0–5 scales, configurable globally and per scanner, with an optional per-score minimum), create the application in `Discovered` and mark it **new/unread**. Otherwise keep it in a "below threshold" archive, so the user can review what was filtered and why.

### 8.4 Duplicates & updates
- The same job from several sources is merged into one application, with each source listed (fuzzy match on company + title + location + description similarity; the user can split or merge manually).
- When a source's posting changes, Jofi stores a new description version, shows a diff, and optionally re-scores.
- When a posting disappears, Jofi marks the source "offline" and keeps the stored snapshot.

---

## 9. Chat module

- **Main entry point after the Dashboard.** A conversational interface over **all modules**, built on **MCP**: Jofi exposes its modules as MCP tools, and the chat agent uses them. External MCP clients (e.g. Claude Desktop) can optionally connect to the same server.
- Examples: "add this job: <link>", "why is the fit score for X so low?", "draft a cover letter for X in Sie-form", "what's due this week?", "I had a call with Anna from Y, here's what she said…".
- **Autonomy:** the chat may **create and edit** entries freely, and every change lands in the relevant changelog. It must **ask for confirmation before deleting** anything and before any action outside the app (none exist yet; future email sending would be one).
- Knowledge changes made via chat always go through the proposal flow (section 4.4).
- Voice input in chat is available when an STT capability is configured.
- Chat history is kept and searchable, and context-aware (opening chat from an application page scopes it to that application).

### 9.1 External MCP access (optional)
- Jofi can **expose its MCP server to external clients** (e.g. Claude Desktop, Claude Code, other MCP-capable apps), so the user can work with Jofi without its own chat UI.
- **Off by default.** Enabled in settings, with a per-client access token (revocable) and optionally a restricted tool set (e.g. read-only).
- External clients get **exactly the same tools** as the built-in chat. There is no second API surface.
- The safety rules live **in the server, not in the chat UI**: deletes and outward actions need a confirmation step the server enforces (for MCP clients this is MCP elicitation answered through the client; the confirmation token never reaches the client, so a client that cannot elicit cannot delete), knowledge changes still become proposals, and every change is logged with actor "AI (external client: <name>)".
- "Never send to AI" entries are never returned by MCP tools, because an external client is itself an AI.
- The MCP endpoint follows the same network rules as the app (localhost by default; HTTPS when exposed).

## 10. Dashboard & tasks

### 10.1 Dashboard
- Pipeline counts per status, plus funnel/conversion (applied → interview → offer) and response rate.
- New/unread scanner findings.
- **Countdowns:** end of current employment / notice period, next interview, application deadlines, offer answer deadlines, and custom countdowns.
- Upcoming tasks (section 10.2) and recent activity.
- AI cost this month vs budget.

### 10.2 Task list
- Tasks with **flexible timing**: a precise date/time, or a rough bucket ("today", "this week", "next week", "this month", "someday").
- Optional link to an application, company or contact.
- Created manually, via chat, or **suggested automatically** (follow-ups after Applied, prepare for an interview the day before, answer an offer before its deadline). Suggested tasks need one click to accept.

### 10.3 Notifications
- **Out of scope** (decision 2026-09-29, round 4). Due items, new matches and deadlines are surfaced on the dashboard only. May be revisited after M6.

---

## 11. Interview training module

- **Modes:**
  - **Application-specific** (default): uses the posting, the company profile, the user's knowledge and the application's language + form of address.
  - **Generic:** by type and role, without a specific application.
- **Interview types:** HR/behavioural, technical, salary negotiation, self-introduction pitch. Extensible later (e.g. case interviews).
- **Conduct:** the AI plays the interviewer with realistic follow-ups, and **time pressure** (per-answer timer, total session length).
- **Voice:** full voice conversation using **streaming STT and streaming TTS** (low latency). Text mode is always available as a fallback.
- **Grading:** an overall grade and per-dimension feedback (content/relevance, structure e.g. STAR, concreteness, language & tone, time management). Includes suggested better answers, drawn from the user's own knowledge.
- **History:** sessions are stored per application with transcripts and grades, and progress is shown over time. Insights from answers can produce knowledge proposals (new stories, clarified skills).

## 12. Email integration (optional, late)
- **IMAP** with app password (GMX, web.de, Gmail with 2FA, most providers); OAuth-only providers (Outlook.com) are harder and come later if at all.
- Reads a (preferably dedicated) mailbox:
  - Job-alert emails, which feed the scanner pipeline.
  - Employer replies (confirmations, rejections, invitations), matched to applications as suggested status changes and tasks.
- Read-only. No sending.

---

## 13. Non-functional requirements

- **Privacy / DSGVO:** single-user, self-hosted, and no telemetry by default. Only the configured AI provider receives data, and "never send to AI" flags are respected. API keys are stored encrypted. Contacts are third-party personal data: they can be deleted at any time and are included in the export.
- **Reliability:** scanners and AI jobs run as background jobs with retries and a visible job log. A failed AI call never loses user data.
- **Auditability:** changelogs on applications, documents and knowledge; source attribution (user / AI / scanner).
- **Portability:** full export/import; knowledge as plain Markdown.
- **i18n:** UI in DE + EN; content (documents, training) in the application's language independent of UI language.
- **Responsive PWA:** installable on the phone from M0, with share-sheet capture of job links and interview training on the go. No push notifications. Using it on the phone needs HTTPS access to the instance (e.g. via Tailscale); the default stays localhost-only.
- **AI-verifiable UI (headless-browser testability):** every user-facing feature must be verifiable end-to-end by an AI agent driving a headless browser (Playwright). That requires: stable test selectors and proper accessible roles/labels on all interactive elements; a **deterministic fake AI provider** and fake scanner sources, so e2e runs need no real keys or network; a seeded demo-data profile; and a one-command e2e stack (`docker compose` profile). UI changes are not done until they're verified this way.
- **Open source hygiene:** easy `docker compose up`, sample `.env`, documentation for providers and scanners, no secrets in the repo.

## 14. Milestones

| # | Milestone | Contents |
|---|---|---|
| **M0** | Foundation | Docker Compose setup, auth, DE/EN i18n, PWA shell, e2e test harness (headless browser, fake AI provider, demo data), background-job runner, provider abstraction + **setup agent** (providers, capability checks, privacy info file, per-task models, cost tracking), export/backup. |
| **M1** | Tracking core | Applications (full entity, status pipeline, changelog, description history), Companies & Contacts, interviews/calls log, URL/text import, Tasks, Dashboard v1, **Chat v1 with MCP tools** for these modules, optional external MCP access (9.1). |
| **M2** | Knowledge & scoring | Knowledge .md store, **knowledge interview**, document upload & extraction, proposal/versioning flow, Want criteria + knockouts, **Want/Fit scoring + AI overview**, language/tone detection, pay-band estimate, score overrides. |
| **M3** | Documents | Templates + own-design import, PDF engine, CV & cover letter generation (DE/EN, Du/Sie), variants, Bewerbungsmappe, frozen snapshots on Applied. |
| **M4** | Scanners | BA API adapter, ATS feed adapters, page watcher, pipeline (dedup, knockouts, pre-filter, AI scoring, thresholds), default scanners, run logs, posting change detection. |
| **M5** | Interview training | Text mode first, then streaming voice (STT/TTS), app-specific + generic modes, four interview types, grading, history. |
| **M6** | Email & polish | Optional IMAP (alerts + reply matching), offer comparison, dashboard analytics polish. |

Notes: M1 is deliberately usable without AI scoring, so Lucas can track his live search early. The MCP tool surface grows with each milestone.

## 15. Questions for the tech-stack step

1. Storage split: relational DB for entities + Markdown files for knowledge? How are the two kept in sync and versioned (git under the hood?).
2. PDF engine: HTML/CSS-to-PDF vs Typst/LaTeX. Which handles "reproduce my own design" and ATS-friendliness best?
3. MCP server implementation, and the chat agent loop (provider-agnostic tool calling).
4. Provider abstraction library vs own adapter layer; streaming STT/TTS transport (WebSocket/WebRTC).
5. Background jobs & scheduling in a single-user Docker setup.
6. ~~PWA push~~ (dropped: notifications are out of scope).
7. Embeddings / vector search for pre-filter and dedup: in-DB extension vs separate service.

## 16. Still open (non-blocking)
- ~~License~~ decided: **AGPL-3.0**.
- Default scanner schedule.
