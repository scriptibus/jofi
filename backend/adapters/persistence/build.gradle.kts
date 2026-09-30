// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// Flyway migrations, jOOQ repositories and the jOOQ code generated from the migrated schema.

plugins {
    id("jofi.spring-conventions")
}

val postgresImage = providers.gradleProperty("jofi.postgresImage")
val migrations = layout.projectDirectory.dir("src/main/resources/db/migration")
val generatedJooq = layout.buildDirectory.dir("generated-sources/jooq")

// The code generator runs in its own JVM with its own locked classpath (not on the app classpath).
val codegen: SourceSet by sourceSets.creating

dependencies {
    implementation(project(":application"))
    implementation(libs.spring.boot.starter.jooq)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.jooq)
    implementation(libs.flyway.core)
    implementation(libs.jackson.module.kotlin)
    // DatabaseBackupRepository streams tables with the driver's COPY API (ADR-0042).
    implementation(libs.postgresql)
    runtimeOnly(libs.flyway.database.postgresql)

    "codegenImplementation"(platform(libs.spring.boot.bom))
    "codegenImplementation"(libs.jooq.codegen)
    "codegenImplementation"(libs.jooq.meta)
    "codegenImplementation"(libs.flyway.core)
    "codegenImplementation"(libs.testcontainers.postgresql)
    "codegenRuntimeOnly"(libs.flyway.database.postgresql)
    "codegenRuntimeOnly"(libs.postgresql)

    testImplementation(libs.testcontainers.postgresql)
    // JobRunrSchemaTest compares our migration with the schema JobRunr's own migrations create.
    testImplementation(libs.jobrunr) { exclude(group = "org.jobrunr", module = "jobrunr-bom") }
    testRuntimeOnly(libs.flyway.database.postgresql)
}

// Flyway from zero on a throwaway PostgreSQL container, then jOOQ codegen from the migrated
// schema (spec 4.5). Cached on the migrations, so it only reruns when a migration changes.
val generateJooq by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Migrates a Testcontainers PostgreSQL from zero and generates jOOQ code from it."
    classpath = codegen.runtimeClasspath
    mainClass.set("io.github.scriptibus.jofi.shared.adapter.persistence.codegen.JooqCodegenKt")
    inputs.dir(migrations).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("migrations")
    inputs.property("postgresImage", postgresImage)
    outputs.dir(generatedJooq).withPropertyName("generatedJooq")
    outputs.cacheIf { true }
    args(migrations.asFile.absolutePath, generatedJooq.get().asFile.absolutePath, postgresImage.get())
}

sourceSets.main {
    java.srcDir(generateJooq)
}

tasks.withType<Test>().configureEach {
    systemProperty("jofi.postgresImage", postgresImage.get())
}

// detekt's `check` wiring only covers main and test; the generator's sources get the same rules.
tasks.named("check") {
    dependsOn("detektCodegen")
}
