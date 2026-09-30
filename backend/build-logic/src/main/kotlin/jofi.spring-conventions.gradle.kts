// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// For modules that may use Spring (adapters, bootstrap): the Spring Boot BOM as a Gradle
// platform (no io.spring.dependency-management plugin) and the kotlin-spring compiler plugin,
// which opens Spring-annotated classes for proxying.

plugins {
    id("jofi.kotlin-conventions")
    id("org.jetbrains.kotlin.plugin.spring")
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    implementation(platform(libs.findLibrary("spring-boot-bom").get()))
    implementation(libs.findLibrary("kotlin-reflect").get())
    testImplementation(platform(libs.findLibrary("spring-boot-bom").get()))
}
