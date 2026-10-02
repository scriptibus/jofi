<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# adapters/web

Owns the REST API: Spring MVC controllers and their request/response DTOs.
Packages: `io.github.scriptibus.jofi.<context>.adapter.web`. Base path: `/api/<context>/...`.

Rules:
- Controllers are named `*Controller`, inject use cases only (never ports, adapters or
  repositories) and contain no business logic: parse input, call one use case, map the result.
- DTOs (`*Request`/`*Response`) stay in this module; domain types are not serialized directly.
- No dependency on other adapter modules.
- Test each controller with a `@WebMvcTest` slice + `MockMvcTester`; provide use cases from a
  `@TestConfiguration` backed by MockK port fakes. `WebAdapterTestApplication` (test sources)
  is the slice's configuration root.
- Errors are RFC 9457 problem details: map sealed failure results to a `ProblemDetail` via
  `ErrorResponseException` (see "API contract" in `backend/AGENTS.md`).
- After changing a controller or DTO, run `./gradlew :adapters:web:updateOpenApiSpec` and commit
  `api/openapi.json`; `OpenApiSpecTest` fails `check` until you do. `OpenApiSpecApplication`
  (test sources) picks up every `@RestController` and `@RestControllerAdvice` under
  `io.github.scriptibus.jofi` and stubs their use cases, so new controllers need no extra wiring.

## Job log (ADR-0038)

`system.adapter.web.JobLogController`: `GET /api/system/jobs?status=&page=&size=`, one page of the job log
(name, status, attempts, timestamps, failure reason code), newest change first. A page must end within the
newest 1000 jobs (`400 urn:jofi:problem:system:invalid-job-log-page`); an unreadable job store answers
`503 urn:jofi:problem:system:job-log-unavailable`. Never add job arguments or exception messages to it.

## Backup and restore (ADR-0042)

`system.adapter.web.BackupController`: `POST /api/system/backup/exports` (`{"password"}`) streams the zip
(the handler writes the response itself, so a failed database dump is still a clean `503`);
`POST /api/system/backup/restores` takes the raw `application/zip` body as a streamed
`InputStreamResource` and answers the checked `StagedBackupResponse` or a `backup-refused` problem with a
`reason` (`422`, `413` for the limits); `POST /api/system/backup/restores/{id}` (`{"password"}`) is the
confirmed restore (`Jofi-Confirmation`, listed in `ConfirmationRules.OUTWARD_FACING_ENDPOINTS`). Export and
restore check the current password (`AuthProblems.of(PasswordCheckResult)`: 403, 429 with `Retry-After`);
concurrent backup work is `409 backup-busy`, a restore that could not be undone `500
backup-restore-incomplete` (`BackupProblems`). A backup grants full access (it holds the master keyset):
say so wherever it is offered. `OpenApiSpecApplication.BinaryBodies` documents non-JSON bodies as
`string`/`binary`.

## Two-step confirmation (ADR-0039)

Every delete or outward-facing endpoint follows `shared.adapter.web.Confirmations`: take
`@RequestHeader(Confirmations.HEADER, required = false) confirmation: String?` and the
`HttpServletRequest`, pass `Confirmations.requester(request)` and `Confirmations.token(confirmation)`
to the use case, and map its `ConfirmationResult.Unconfirmed` with `throw Confirmations.problem(it)`
(428 with the token and the structured effect on the first call, 412 for a refused token). The header
parameter makes the contract document the 428 `ConfirmationRequiredProblem` automatically, and
`ConfirmationRulesTest` fails a `DELETE` handler without it. Never log the token or put it in a URL.
`ConfirmationFlowTest` (bootstrap) shows the pattern end to end behind the security filter chain.

## Companies (#73, ADR-0041)

`companies.adapter.web.CompanyController`: `GET /api/companies?search=&preference=&page=&size=`,
`POST /api/companies`, `GET|PUT /api/companies/{id}` (PUT replaces all details),
`PUT /api/companies/{id}/preference` and `DELETE /api/companies/{id}` (two steps, `Jofi-Confirmation`;
deletes the company's contacts too; the 428's effect counts them as `contacts`). Each handler calls one
use case (#88) as `Actor.User` and maps its failure with `orThrow()`. `CompanyProblems.of` maps each
`CompanyResult.Failure`: `Invalid` -> 400 `ValidationProblem` (`violations: [{field, problem}]`, request
field names), `NotFound` -> 404, `VersionConflict` and `HasApplications` -> 409 with their own
`urn:jofi:problem:companies:*` type, `Unconfirmed` -> `Confirmations.problem`, `StorageFailure` -> 503.
Changes send `basedOnVersion`, the `version` of the last read. Responses carry `applicationCount`.
The slice tests' configuration root is `CompaniesWebTestApplication` (test sources), since
`@WebMvcTest` searches the test's own package.

## Contacts (#74, #89, ADR-0041)

`companies.adapter.web.ContactController`: `GET /api/contacts?search=&companyId=&page=&size=`,
`POST /api/contacts`, `GET|PUT /api/contacts/{id}` (PUT replaces all details and channels) and
`DELETE /api/contacts/{id}` (two steps, `Jofi-Confirmation`; the 428's effect counts the linked
applications as `applications`). Contacts belong to the `companies` context but get their own resource
path, since they exist without a company too. Each handler calls one contact use case (#89). `ContactProblems.of`
maps each `ContactResult.Failure`; channel violations name the request field with its position
(`channels[2].value`), an unknown company is `companyId` `NOT_FOUND`. Contacts are third-party personal
data: their DTOs override `toString()` without it, and no `detail` names a contact.

## AI provider setup (#23)

`setup.adapter.web.AiProviderController` (`/api/setup/providers`): `GET`, `POST` (201), `PUT /{id}` (name,
base URL; an absent key keeps the stored one, unless the base URL moves to another origin, which needs
the key again), `DELETE /{id}` (two steps, `Jofi-Confirmation`; 409
`provider-in-use` while tasks are assigned), `POST /{id}/models/refresh` (the connection test: lists the
models through the guarded transport and stores them as detected capability profiles; provider failures
answer 502 with `provider-authentication-failed`, `-rate-limited`, `-unreachable` or `-rejected`),
`GET /{id}/models`, `PUT /{id}/models` (the user's capability correction; the model name is in the body
because names contain `/` and `:`). `TaskAssignmentController` (`/api/setup/assignments`): `GET` (every
task with `needs` and `missing`), `PUT /{task}`. Keys go in only: responses carry `apiKeySet`, never the
key or its secret id; request DTOs hide the key in `toString()` and mark it `@WriteOnlySecret`
(`writeOnly`, `format: password` in the contract). Controllers always act as
`Actor.User`; the use cases refuse every other actor (`403 urn:jofi:problem:setup:forbidden`), so MCP
and AI tools must never be given these use cases. `SetupProblems.of` maps each `SetupResult.Failure`.
`ProviderPrivacyController`: `GET /api/setup/providers/privacy` (read-only, #138): one entry per provider
kind with `zeroDataRetention`, `noTraining` and `dataLocation` (each a `status`, a `summary` in `en`/`de`
and `evidence` quotes with their https `source`), the entry's `checkedOn` and `stale` (older than
`staleAfterMonths`), plus the `disclaimer` (`key` for Paraglide and its `en`/`de` text) that the UI must
show with every entry. The data comes from `provider-privacy.json` in `adapters/ai`.

## AI costs and monthly budget (#24)

`setup.adapter.web.AiCostController`: `GET /api/setup/costs?month=YYYY-MM` (default the current UTC month; a
future month or one before 2000 is `400 invalid-input` on `month`): totals plus `byTask`, `byProviderKind`
(the kind recorded with each call, so a deleted provider's costs still count) and `byModel`, each with calls,
tokens, `knownCostMicros` and `unknownCostCalls` (calls without a price are counted, never priced); only the
current month carries `budget`, since the cap has no history. `GET /api/setup/costs/history?months=` (1-24,
default 12): one entry per month up to the current one, oldest first, empty months as zero.
`MonthlyBudgetController`: `GET /api/setup/budget` (cap, spent, remaining, `state`, `pausedTasks`, `pausedUntil`
= next UTC month) and `PUT /api/setup/budget` with `capMicros` (1 micro to 1,000,000 USD) or `null` to remove
the cap. `capMicros` is required (required + nullable in the contract): a body without it is `400 invalid-input`
(`capMicros` `REQUIRED`, mapped from the Kotlin module's missing-parameter error), so a truncated request never
lifts the cap. The cap is changed with `PUT`, not `DELETE`: removing it destroys nothing, so it needs no
confirmation, but it is a user-only setup mutation (`SetupRules`) with a changelog entry. Amounts are integer
USD micros everywhere.
`ModelPriceController` (ADR-0055, #142): `GET|PUT|DELETE /api/setup/providers/{id}/model-prices` lists, sets
(model name and `inputMicrosPerMillion`/`outputMicrosPerMillion` in the body; 0 to 10,000,000,000, 0 allowed) and
removes (`?model=`, since model names contain slashes) the user's price of a model of an OpenAI-compatible
provider; a cloud provider is `409 price-not-allowed`. The `DELETE` takes no confirmation header (reviewed entry in
`ConfirmationRules.ENDPOINTS_WITHOUT_CONFIRMATION`, same reason as the cap). A price applies to later calls only.

## Applications (#76, ADR-0041)

`applications.adapter.web.ApplicationController`: `GET /api/applications?search=&companyId=&contactId=&page=&size=`,
`POST /api/applications`, `GET|PUT /api/applications/{id}` (PUT replaces all details),
`PUT /api/applications/{id}/unread` (no version, the version stays), `PUT /api/applications/{id}/contacts`
(the full set of linked contact ids with `basedOnVersion`; linking and unlinking both send the changed set,
so no `DELETE` needs a confirmation), `PUT /api/applications/{id}/status` (the status matrix of ADR-0044;
a decline category exactly for `DECLINED`/`REJECTED`, `409 invalid-transition` for a move it does not
allow), `GET /api/applications/{id}/status-history` and `DELETE /api/applications/{id}` (two steps,
`Jofi-Confirmation`). The decline reason is set by the status change, not by the details.
Create, read, edit, read/unread and delete (#82) and the status change with its history (#84) call their use
cases as `Actor.User` and map failures with
`orThrow()`: an unknown company is `400` (`companyId`, `NOT_FOUND`), the delete's 428 effect counts
`contactLinks`, `statusChanges`, `sources` and `snapshots`. The list and the contact links still
answer `501` until #83 and #90 (the search already answers 400 for paging out of range). `ApplicationProblems.of` maps each `ApplicationResult.Failure`; violations name the
nested request field (`payBand.max`, `offer.salary.currency`, `contactIds`). API enums are copies of the
domain enums (`JobSeniority` for `Seniority`, ...), mapped with `mapByName` and tested for equal constants.
Amounts are gross, JSON numbers with at most two decimals; scores numbers with one decimal. DTOs holding
notes, reasons, pay amounts or an estimate basis print none of them.

`applications.adapter.web.ApplicationSourceController` (#78, ADR-0046) under `/api/applications/{id}`:
`POST /sources` (201; kind, original link, discovery time, optional text at discovery), `POST
/sources/{sourceId}/snapshots` (record the current text by hand, reason `MANUAL`; `added` says whether it was a
new version), `GET /sources/{sourceId}/snapshots` (versions without texts), `GET /snapshots/{snapshotId}` (one
version with its text) and `GET /description-diff?from=&to=` (line-level segments). None takes
`basedOnVersion` (sources and snapshots are no version of the application) and none deletes. An unknown source
or snapshot is 404 `source-not-found` / `snapshot-not-found`. `ApplicationResponse.sources` lists the sources.
Posting texts are untrusted (render sanitised) and links may carry tracking parameters: these DTOs print neither.

`applications.adapter.web.PostingImportController` (ADR-0051) under `/api/applications/imports`: `POST /text` (202, a
pending import; 400 for the text; 409 `ai-not-configured` without an extraction model), `GET /{importId}` (status,
failure reason, the created application; never the text) and `POST /{importId}/retry` (202; 409
`import-not-retryable` unless failed or stalled). `POST /url` (#97; 202 for a new or still-pending import, 200 with the
existing application when the normalised link was imported before; 400 with an `originalUrl` violation
`INVALID_URL` / `NOT_ALLOWED` / `UNREACHABLE`; 409 `ai-not-configured`; 409 `import-in-progress` when another request
holds the same link too long; 429 `import-busy` when the cap of concurrent fetches, `jofi.import.max-concurrent-fetches`,
is used up, #224: try again shortly): it translates and calls `StartUrlImportPort`.

## Documented problem responses (ADR-0041)

Annotate a handler with `@ProblemResponses(ProblemKind.INVALID_INPUT, NOT_FOUND, CONFLICT)`
(`shared.adapter.web`); the contract renderer (`ProblemResponsesCustomizer`, test sources) adds the 400
(`ValidationProblem`), 404 and 409 responses (`ProblemKind.TOO_MANY_REQUESTS` adds the 429 for the URL import). Throw `ValidationProblem.of(type, violations)` for 400s.
Web adapters of every context may use `shared.adapter.web` (the one exemption from adapter
independence for web, `AdapterRules`).

## Authentication (ADR-0035)

- `system.adapter.web.AuthController`: `GET /api/auth/session`, `POST /api/auth/first-run`,
  `POST /api/auth/login`, `POST /api/auth/logout`, `PUT /api/auth/password`. Only the first three are
  open without a session (`SecurityConfiguration.PUBLIC_API` in bootstrap; `AuthSecurityTest` walks
  every mapping, so a new endpoint is protected unless you add it there on purpose).
- `SessionSecurity` turns a successful password check into a session (new session id, new CSRF
  token, stored security context) and defines the CSRF cookie repository the filter chain shares.
- `SecurityProblemHandler` answers the filter chain's 401/403 as problem details; `AuthProblems`
  maps the auth use cases' failures (types `urn:jofi:problem:system:*`, `Retry-After` on 429).
- Request DTOs that carry passwords or tokens override `toString()` so debug logs stay clean.
- `SessionValidityFilter` (added to the chain in bootstrap) ends a session that belongs to another
  `account_id` or is older than `jofi.auth.session-max-age`; `ClientAddress` turns the peer address
  into the throttle key (IPv6 by /64).
- Slice tests run without the filter chain (`@AutoConfigureMockMvc(addFilters = false)`); the
  security behaviour is tested end to end in bootstrap.
