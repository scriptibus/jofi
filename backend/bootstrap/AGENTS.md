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
- Environment: `JOFI_DATA_DIR` (data volume: master keyset, setup token; required, absolute),
  `JOFI_SERVER_ADDRESS` (bind address of the server, default `127.0.0.1`), `JOFI_TRUSTED_PROXIES`
  (CIDRs allowed to send `X-Forwarded-*`, default loopback), `JOFI_SESSION_TIMEOUT` (idle, default
  `7d`), `JOFI_SESSION_MAX_AGE` (absolute, default `30d`), `JOFI_RESET_PASSWORD` (password
  recovery at startup), `JOFI_ACCEPT_SECRET_LOSS` (accept a lost master keyset). Tests get a data
  directory under the test task's temporary directory; `bootRun` needs `JOFI_DATA_DIR` set.
- `system.config.AuthStartup` (a `SmartLifecycle` in the phase just before the web server's, so it
  runs after the refresh but before the port is bound; web apps only and never under the `worker`
  profile) checks the master keyset against the database and refuses to start on a mismatch or a
  leftover `JOFI_ACCEPT_SECRET_LOSS`, applies `JOFI_RESET_PASSWORD` once per setting, then issues or removes
  the setup token. `StartupSafetyTest` starts the app on its own databases to prove each case, that
  the port stays closed while the checks run, and that the AOT training run (`spring.context.exit`)
  exits without running them.
- `InMemoryLoginThrottleAdapter` (backoff counts) and `SpringSessionUserSessionsAdapter` (ending
  sessions) are framework-bound adapters here.
