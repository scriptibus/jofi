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
    implementation(project(":adapters:net"))
    implementation(project(":adapters:crypto"))
    implementation(project(":adapters:ai"))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.session.jdbc)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.jackson.module.kotlin)
    // Module metadata annotations only (`shared.ModuleMetadata`); no Modulith runtime.
    implementation(platform(libs.spring.modulith.bom))
    implementation(libs.spring.modulith.api)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.security.test)
    testImplementation(libs.spring.boot.starter.actuator.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.jooq)
}

val postgresImage = providers.gradleProperty("jofi.postgresImage")

// Tests and `bootTestRun` (the app against a throwaway database) start the pinned PostgreSQL image.
tasks.withType<Test>().configureEach {
    systemProperty("jofi.postgresImage", postgresImage.get())
    // The data volume of the test JVM (master keyset, setup token), never the developer's own.
    systemProperty("jofi.data-dir", temporaryDir.resolve("data").absolutePath)
}

tasks.named<JavaExec>("bootTestRun") {
    systemProperty("jofi.postgresImage", postgresImage.get())
    systemProperty(
        "jofi.data-dir",
        layout.buildDirectory
            .dir("bootTestRun-data")
            .get()
            .asFile.absolutePath,
    )
}
