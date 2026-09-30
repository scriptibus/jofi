// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// The AI provider adapter (ADR-0011, ADR-0032, ADR-0037): Spring AI behind AiProviderPort. It has no
// HTTP client of its own; the vendor SDK clients get the guarded transport from adapters/net as a bean.

plugins {
    id("jofi.spring-conventions")
}

dependencies {
    implementation(project(":application"))
    implementation(platform(libs.spring.ai.bom))
    // Model modules only: no starter, so nothing is auto-configured and no unguarded client appears.
    // OkHttp is excluded: Spring AI would only use it to build its own, unguarded SDK clients, and
    // without it any such fallback fails loudly instead of bypassing the SSRF guard (ADR-0037).
    implementation(libs.spring.ai.openai) {
        exclude(group = "com.squareup.okhttp3")
    }
    implementation(libs.spring.ai.anthropic) {
        exclude(group = "com.squareup.okhttp3")
    }
    // api: ProviderModels takes the SDK transport interfaces that bootstrap wires in.
    api(libs.openai.java.core)
    api(libs.anthropic.java.core)

    // Tests run the adapter over the real guarded transport against WireMock.
    testImplementation(project(":adapters:net"))
    testImplementation(libs.wiremock.standalone)
    // LogPrivacyTest captures every log event.
    testImplementation(libs.logback.classic)
}
