// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// Background jobs (ADR-0010, ADR-0038): JobSchedulerPort, the job log and the job handlers on
// JobRunr with PostgreSQL storage. `app` only writes jobs; the `worker` profile runs them.

plugins {
    id("jofi.spring-conventions")
}

dependencies {
    implementation(project(":application"))
    implementation(libs.spring.boot.starter)
    // JobRunr's BOM pins newer Jackson, logback, HikariCP, ... than the Spring Boot BOM: Boot decides.
    implementation(libs.jobrunr) { exclude(group = "org.jobrunr", module = "jobrunr-bom") }
    // JobRunr's Jackson 3 mapper; the version comes from the Spring Boot BOM.
    implementation(libs.jackson3.databind)
}
