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
    // The guarded transport for the AI vendor SDKs implements their HttpClient interfaces (ADR-0037).
    // The SDK cores have no transport of their own.
    api(libs.openai.java.core)
    api(libs.anthropic.java.core)

    testImplementation(libs.wiremock.standalone)
}
