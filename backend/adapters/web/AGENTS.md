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

## Two-step confirmation (ADR-0039)

Every delete or outward-facing endpoint follows `shared.adapter.web.Confirmations`: take
`@RequestHeader(Confirmations.HEADER, required = false) confirmation: String?` and the
`HttpServletRequest`, pass `Confirmations.requester(request)` and `Confirmations.token(confirmation)`
to the use case, and map its `ConfirmationResult.Unconfirmed` with `throw Confirmations.problem(it)`
(428 with the token on the first call, 412 for a refused token). The header parameter makes the contract
document the 428 `ConfirmationRequiredProblem` automatically. Never log the token or put it in a URL.
`ConfirmationFlowTest` (bootstrap) shows the pattern end to end behind the security filter chain.

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
