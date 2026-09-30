<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0051: Posting import as a job with kept text, structured AI extraction and a strict company match

- Status: accepted
- Date: 2026-10-01
- Source: issue #96 (M1-6a); spec §6.1, §8.1; threat model T2, T4; refines ADR-0032, ADR-0038, ADR-0043, ADR-0046

## Context

The user pastes a job posting; Jofi has to read its fields with AI and create a `DISCOVERED` application with a
source and the text as its discovery snapshot. The posting is untrusted text from the web (T2), and so is whatever a
model makes of it. The call can fail or be slow, the text must survive a failure so the import can be retried, job
arguments may only be ids (ADR-0038), and the company the posting names has to be found in, or added to, the
companies context without reaching into it.

## Decision

- **Import record.** Table `posting_import` (status `PENDING`/`SUCCEEDED`/`FAILED`, a failure reason, the created
  application's id without a foreign key, an `attempt` counter). The pasted text is stored while the import is pending
  or failed and set to `NULL` when it succeeds: from then on the application's discovery snapshot holds it, so
  deleting the application leaves no copy. Exported and restored like every user table (ADR-0042).
- **Job.** `POST /api/applications/imports/text` validates the text (a job description, at most 100,000
  characters), refuses with `409 ai-not-configured` when no model is assigned to `EXTRACTION` (asked through the setup
  context's `api` port `CheckAiTaskAssignedPort`), stores the pending import, commits, then enqueues job
  `posting-import` with the import id only. A failed enqueue marks the import `NOT_QUEUED`. The client polls
  `GET /api/applications/imports/{id}`; `POST .../{id}/retry` queues a failed import again as the next attempt. A run
  writes its outcome only if the stored status and attempt are still the ones it read, so a late or duplicate run
  changes nothing. AI failures are an outcome of the run (the import is `FAILED`, the job is done); only storage
  failures make the job retry.
- **Structured extraction.** `LlmRequest` gets an optional `outputSchema` (JSON Schema text, Jofi's own constant,
  never user data), passed to Spring AI 2.0.1's `StructuredOutputChatOptions.outputSchema` (OpenAI and compatible
  endpoints: `response_format` `json_schema`; Anthropic: `output_config.format`). The extraction request has no tools,
  a system message that declares the posting data, and the posting alone in the user message between start and end
  lines carrying a random marker per call. Docs:
  https://docs.spring.io/spring-ai/docs/2.0.0/api/org/springframework/ai/model/tool/StructuredOutputChatOptions.Builder.html.
- **The answer is untrusted.** A cut-off answer, a tool call or anything but one JSON object is `UNREADABLE_ANSWER`.
  Of the object only the schema's fields are read, each only with its expected type; anything else (a `status`, a tool
  name) is ignored. The fields then go through `ApplicationInput.validate()`: an optional field that breaks a rule is
  dropped, a missing or broken title or company is `NOT_A_POSTING`. The run can only create an unread `DISCOVERED`
  application with one `MANUAL_CHAT` source; nothing else is reachable from the answer.
- **Company match.** The companies context gets an `api` named interface with `MatchCompanyPort`: a fuzzy search of the
  company list for the name without its legal form narrows the candidates, and only a candidate with the same
  `CompanyNameKey` (letters and digits after NFKC, ignoring case and trailing legal forms) counts; otherwise a company
  with that name is created through `CreateCompanyPort`. Deliberately strict: a wrong match files the job under another
  company, a missed one only adds a company the user can see. Creation is not destructive, so it needs no confirmation.
- **Actors.** Starting and retrying are the user's (`USER`). Everything the run creates, including a new company, is
  recorded as `AI`: the values come from the model's reading.

## Consequences

- The URL import (#97), the chat (#118) and scanners (M4) reuse `AddDiscoveredApplicationPort` and the job pattern.
- A model or endpoint without structured output answers with a rejection (`AI_REJECTED`); the user picks another model
  for the extraction task and retries.
- A company created by a run that then fails stays; the next attempt matches it.
- Failed imports keep their text until retried; discarding a failed import is left to the import page (#98).
