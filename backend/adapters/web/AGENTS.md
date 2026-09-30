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
deletes the company's contacts too). Contract only: every operation answers `501` until #88 injects the
use cases (the search already answers 400 for paging out of range). `CompanyProblems.of` maps each
`CompanyResult.Failure`: `Invalid` -> 400 `ValidationProblem` (`violations: [{field, problem}]`, request
field names), `NotFound` -> 404, `VersionConflict` and `HasApplications` -> 409 with their own
`urn:jofi:problem:companies:*` type, `Unconfirmed` -> `Confirmations.problem`, `StorageFailure` -> 503.
Changes send `basedOnVersion`, the `version` of the last read. Responses carry `applicationCount`.
The slice tests' configuration root is `CompaniesWebTestApplication` (test sources), since
`@WebMvcTest` searches the test's own package.

## Contacts (#74, ADR-0041)

`companies.adapter.web.ContactController`: `GET /api/contacts?search=&companyId=&page=&size=`,
`POST /api/contacts`, `GET|PUT /api/contacts/{id}` (PUT replaces all details and channels) and
`DELETE /api/contacts/{id}` (two steps, `Jofi-Confirmation`). Contacts belong to the `companies` context
but get their own resource path, since they exist without a company too. Contract only: every operation
answers `501` until #89 (the search already answers 400 for paging out of range). `ContactProblems.of`
maps each `ContactResult.Failure`; channel violations name the request field with its position
(`channels[2].value`), an unknown company is `companyId` `NOT_FOUND`. Contacts are third-party personal
data: their DTOs override `toString()` without it, and no `detail` names a contact.

## Documented problem responses (ADR-0041)

Annotate a handler with `@ProblemResponses(ProblemKind.INVALID_INPUT, NOT_FOUND, CONFLICT)`
(`shared.adapter.web`); the contract renderer (`ProblemResponsesCustomizer`, test sources) adds the 400
(`ValidationProblem`), 404 and 409 responses. Throw `ValidationProblem.of(type, violations)` for 400s.
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
