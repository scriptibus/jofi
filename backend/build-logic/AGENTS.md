<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# build-logic

Included build with the convention plugins every module applies. Versions come from
`../gradle/libs.versions.toml`; nothing here pins a version of its own.

- `jofi.kotlin-conventions`: Kotlin JVM, JDK 25 toolchain, warnings as errors, STRICT dependency
  locking, JUnit 6 + Kotest assertions + MockK; applies detekt, Spotless and licensee conventions.
- `jofi.detekt-conventions`, `jofi.spotless-conventions` (ktlint + SPDX header),
  `jofi.licensee-conventions` (license allowlist), `jofi.kover-conventions` (coverage gate).
- `jofi.spring-conventions` (Boot BOM platform + kotlin-spring) and
  `jofi.spring-boot-application` (Boot plugin, build info) for Spring modules.
- `jofi.root-conventions`: Spotless for root scripts and this build.

Changes here affect every module and are protected paths (spec 4.4): keep them small, run
`./gradlew check` from `backend/`, and refresh locks/checksums when plugin versions change.
