// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// The Spring Boot application: wiring, configuration and framework-bound adapters.

plugins {
    id("jofi.spring-boot-application")
}

dependencies {
    implementation(project(":application"))
    implementation(project(":adapters:web"))
    implementation(project(":adapters:persistence"))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.jackson.module.kotlin)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.actuator.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.jooq)
}

val postgresImage = providers.gradleProperty("jofi.postgresImage")

// Tests and `bootTestRun` (the app against a throwaway database) start the pinned PostgreSQL image.
tasks.withType<Test>().configureEach {
    systemProperty("jofi.postgresImage", postgresImage.get())
}

tasks.named<JavaExec>("bootTestRun") {
    systemProperty("jofi.postgresImage", postgresImage.get())
}
