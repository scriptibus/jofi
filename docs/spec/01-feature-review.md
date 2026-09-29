# Jofi: review of the initial feature idea

Status: draft for discussion, 2026-09-29. Tech stack is deliberately out of scope here.

## 1. What's strong

- Two scores (Want vs Fit) instead of one is the right call; they fail for different reasons.
- Keeping rejected/declined jobs with reasons gives the AI something to learn from.
- Source provenance and job-description history per source are rare features and genuinely useful (postings disappear or change after you apply).
- A central Knowledge base feeding documents, scores and interview training is the right backbone.

## 2. Biggest risks

### 2.1 Scanners (feasibility and ToS)

| Source | Realistic access | Verdict |
|---|---|---|
| Bundesagentur für Arbeit | Public Jobsuche REST API (search, details, filters by location/radius, occupation, working time). No per-user login needed. | Solid. Best first scanner. |
| LinkedIn | No public job-search API. Scraping violates the User Agreement and risks an account ban; they actively detect it. | Do not scrape. Ingest LinkedIn job-alert emails, or paste/share URLs into chat. |
| StepStone | No public API. Scraping is against ToS and bot-protected. | Job-alert emails, or manual URL import. |
| Indeed | Public publisher API was shut down; scraping is blocked and against ToS. | Job-alert emails, or manual URL import. |
| Company websites | Most run on an ATS with public, allowed endpoints: Personio (XML feed), Greenhouse, Lever, SmartRecruiters, Workday, Recruitee, join.com. Custom pages need per-site scraping (fragile). | Good: an "ATS feed" scanner type covers many companies with one adapter each. Generic HTML scraping only as fallback. |

Implication: the scanner concept should be split into **adapter types** (BA API, ATS feed, email-alert inbox, URL import, generic page watcher) rather than one scanner per job board. The **email-alert inbox** adapter is the pragmatic way to cover LinkedIn/StepStone/Indeed legally.

Other scanner risks:
- The same job appears on 3 boards plus the company site. Needs **duplicate detection / merging** into one application with multiple sources (your "change history per source" already hints at this).
- AI scoring every hit costs money. Needs a cheap pre-filter (keywords, location, salary, blacklisted companies) before the LLM runs, and a budget cap.
- Postings get taken down. Snapshot the full description (and ideally a PDF/HTML copy) at discovery and at application time.

### 2.2 Privacy / DSGVO

The Knowledge base will hold CV, Arbeitszeugnisse, salary history, maybe health or family reasons for a job change. Questions this raises:
- Is this single-user, self-hosted, or a product for others? (Multi-user changes almost everything: auth, tenancy, DSGVO duties as a controller.)
- Which LLM provider sees the data, and is that acceptable? Option to redact or to mark some knowledge as "never send to AI".
- Contact persons are third-party personal data. Fine for private use, a real obligation if it becomes a product.
- Backups and export ("give me everything as a zip").

### 2.3 Knowledge base drift

"Challenged and updated on each action anywhere" is powerful but risky: silent AI edits to your own facts can quietly corrupt your CV. Suggest: AI **proposes** knowledge changes, you accept/reject (like a diff), and every change is versioned (git-like history on the .md store).

### 2.4 Scope of a first version

Everything listed is roughly 6 to 8 sub-products. A usable v1 probably needs: Applications + Knowledge + one document type (CV/cover letter) + BA scanner + URL import + Dashboard tasks. Chat-over-MCP and interview training with voice are strong v2 candidates. To be decided with you.

## 3. What's missing

**Application lifecycle**
- An explicit status pipeline (e.g. Discovered → Shortlisted → Preparing → Applied → Interviewing → Offer → Accepted / Rejected / Withdrawn / Ghosted) with timestamps, so the dashboard numbers mean something.
- Application deadlines, and auto follow-up tasks ("no answer after 14 days → nudge").
- Exactly which document versions were sent where (a frozen snapshot, not a link to a document that may change later).
- How you applied (portal, email, referral) and portal login hints.
- Outcome and feedback capture, feeding back into Want/Fit calibration.
- Offer comparison (salary, benefits, remote, notice period, commute).

**Communication**
- Email integration: detect replies, rejections and interview invitations and attach them to the right application. This is often the biggest time saver.
- Calendar integration for interviews and countdowns.
- Cover letter (Anschreiben) generation, which is still expected in Germany. Missing from Documents.

**Research**
- Company profile per application (size, industry, Kununu/Glassdoor sentiment, news, tech stack), reusable across multiple applications to the same company.
- Salary benchmarking for the "estimated" pay band: where does the estimate come from?

**Documents**
- Language variants (German/English) of every document.
- ATS-friendly PDF output (machine-readable text, no text in images).
- Photo, signature, and certificate bundling into one application PDF ("Bewerbungsmappe").

**Scoring**
- What goes into Want and Fit? Your preferences (salary floor, remote, location, industry, company size, tech) need a place, likely a "Preferences" section in Knowledge.
- Hard knockouts (e.g. "no relocation", "min salary X") that skip the LLM entirely.
- Explainability is covered by your AI overview; add a way to correct a score so the model learns.

**Operations**
- Notifications (push, email, Telegram…) for new high-score matches and upcoming tasks.
- AI cost tracking.
- Human in the loop: the app never sends an application on its own. (Assumed; to confirm.)

## 4. Open questions (interview backlog)

Round 1 (sent in chat):
1. Who is it for: just you, or a product others would use?
2. Where does it run: your own machine / home server, cloud, or don't care?
3. Which AI provider(s), and how sensitive is sending your full CV/Zeugnisse to them?
4. What's your current situation: actively searching now (deadline pressure) or building for the long run?
5. Email integration for alerts and replies: yes/no?

Later rounds:
- Want/Fit criteria in detail, and thresholds.
- Status pipeline: agree on the stages.
- Documents: which templates, languages, design freedom vs strict layout.
- Interview trainer: text first or voice from day one? Which interview types (HR, technical, case)?
- Chat: which actions may the chat perform without confirmation?
- Dashboard: which numbers matter; what countdowns besides contract end and next interview?
- Mobile use: do you need it on the phone (quick capture, notifications)?
- v1 cut line.
