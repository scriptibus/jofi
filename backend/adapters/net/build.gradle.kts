// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// The only outbound HTTP client (threat model T1): the SSRF guard behind OutboundHttpPort and the
// guarded request factory for the AI provider clients (ADR-0034).

plugins {
    id("jofi.spring-conventions")
}

dependencies {
    implementation(project(":application"))
    implementation(libs.httpclient5)
    implementation(libs.spring.web)

    testImplementation(libs.wiremock.standalone)
}
