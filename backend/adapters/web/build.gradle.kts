// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// REST controllers and DTOs. Controllers only call use cases.

plugins {
    id("jofi.spring-conventions")
}

dependencies {
    implementation(project(":application"))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.jackson.module.kotlin)

    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.security.test)
    // Test-only: OpenApiSpecTest renders the spec from the controllers; the app does not serve it.
    testImplementation(libs.springdoc.openapi.webmvc.api)
    testRuntimeOnly(libs.jackson2.module.kotlin)
    constraints {
        // Security override (see the version catalog): swagger-core brings a vulnerable Jackson 2.
        testImplementation(libs.security.jackson2.databind)
    }
}

// The API contract (ADR-0016), committed at the repository root so the frontend and oasdiff read it.
val openApiSpec: RegularFile = isolated.rootProject.projectDirectory.file("../api/openapi.json")

tasks.withType<Test>().configureEach {
    systemProperty("jofi.openapi.spec", openApiSpec.asFile.absolutePath)
}

tasks.test {
    // Re-run the drift check when the committed spec changes.
    inputs
        .file(openApiSpec)
        .withPropertyName("openApiSpec")
        .withPathSensitivity(PathSensitivity.NONE)
        .optional()
}

// `test` (and so `check`) fails when the committed spec differs from the controllers; this rewrites it.
tasks.register<Test>("updateOpenApiSpec") {
    description = "Regenerates ../api/openapi.json from the controllers."
    group = "api"
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("*.OpenApiSpecTest") }
    systemProperty("jofi.openapi.write", "true")
    outputs.file(openApiSpec)
    outputs.upToDateWhen { false }
}
