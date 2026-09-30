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
