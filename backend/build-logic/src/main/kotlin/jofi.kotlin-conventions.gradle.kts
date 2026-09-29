// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// Base convention for every backend module: Kotlin on JDK 25, warnings as errors, locked
// dependencies, JUnit 6 + Kotest assertions + MockK, and the quality gates (detekt, Spotless,
// licensee) wired into `check`.

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("jofi.detekt-conventions")
    id("jofi.spotless-conventions")
    id("jofi.licensee-conventions")
}

val libs = the<VersionCatalogsExtension>().named("libs")

group = "io.github.scriptibus.jofi"

dependencyLocking {
    lockAllConfigurations()
    lockMode = LockMode.STRICT
}

// Refresh lockfiles after a dependency change: ./gradlew resolveAndLockAll --write-locks
tasks.register("resolveAndLockAll") {
    group = "dependency locking"
    description = "Resolves every resolvable configuration so --write-locks records all of them."
    notCompatibleWithConfigurationCache("Filters configurations at execution time")
    doFirst {
        require(gradle.startParameter.isWriteDependencyLocks) {
            "$path must be run from the command line with the `--write-locks` flag"
        }
    }
    doLast {
        configurations.filter { it.isCanBeResolved }.forEach { it.resolve() }
    }
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        allWarningsAsErrors = true
        // Spring Boot 4.1 recommends -Xannotation-default-target=param-property; it is the
        // default since Kotlin 2.4, where passing it explicitly is a compiler error.
    }
}

dependencies {
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testImplementation(libs.findLibrary("kotest-assertions-core").get())
    testImplementation(libs.findLibrary("mockk").get())
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
