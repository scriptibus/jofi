<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# bootstrap

Owns the runnable Spring Boot application (`io.github.scriptibus.jofi.JofiApplication`), the
wiring and the runtime configuration (`src/main/resources/application.yaml`). Sees every module.

Rules:
- Wire use cases per context in `io.github.scriptibus.jofi.<context>.config.<Context>Configuration`
  (`@Configuration(proxyBeanMethods = false)` + `@Bean` methods). Domain/application stay annotation-free.
- Framework-bound adapters with no better home go in `<context>.adapter.<kind>` here and end in
  `Adapter` (e.g. `system.adapter.buildinfo.BuildPropertiesAdapter`).
- The only class allowed directly in the base package is the application class.
- Keep the `@SpringBootTest` smoke test green: context starts, `/actuator/health` is `UP`.
  Context tests `@Import(PostgresTestConfiguration::class)` (Testcontainers service connection).
- The database comes from `JOFI_DB_URL`, `JOFI_DB_USERNAME`, `JOFI_DB_PASSWORD`; Flyway migrates at
  startup. Credentials never go into `application.yaml`, the URL or logs (`DatabaseConfigurationTest`).
- Actuator exposes `health` only; widen exposure deliberately.
- Security (ADR-0035): `system.config.SecurityConfiguration` is the filter chain (every `/api/**`
  call needs a session except `PUBLIC_API`, SPA CSRF, problem-details 401/403, session cookie flags).
  Tests that call protected endpoints without logging in use `@WithMockUser`; `AuthSecurityTest`
  drives the real flow with `Browser` (cookies + CSRF header like the SPA).
- Environment: `JOFI_DATA_DIR` (data volume: master keyset, setup token), `JOFI_SERVER_ADDRESS`
  (bind address of the server, default `127.0.0.1`), `JOFI_BIND_ADDRESS` (where Jofi is reachable;
  not loopback = first run needs the setup token), `JOFI_TRUSTED_PROXIES` (CIDRs allowed to send
  `X-Forwarded-*`, default loopback), `JOFI_SESSION_TIMEOUT` (default `7d`). Tests get a
  data directory under the test task's temporary directory.
- `InMemoryLoginThrottleAdapter` (backoff counts) and `SpringSessionUserSessionsAdapter` (ending
  sessions) are framework-bound adapters here.
