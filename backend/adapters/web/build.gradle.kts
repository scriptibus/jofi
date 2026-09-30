// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// REST controllers and DTOs. Controllers only call use cases.

plugins {
    id("jofi.spring-conventions")
}

dependencies {
    implementation(project(":application"))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.jackson.module.kotlin)

    testImplementation(libs.spring.boot.starter.webmvc.test)
}
