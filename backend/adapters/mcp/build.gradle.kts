// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// The MCP server (ADR-0012, ADR-0053): Streamable HTTP at /mcp through Spring AI's WebMvc transport over the
// official MCP Java SDK. Tools only translate and call one use case each; every result passes the
// "never send to AI" filter. No Spring AI starter: nothing is auto-configured.

plugins {
    id("jofi.spring-conventions")
}

dependencies {
    implementation(project(":application"))
    // api: bootstrap registers the transport's router function and closes the server.
    api(libs.spring.ai.mcp.webmvc)
    api(libs.mcp.sdk)
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.jackson.module.kotlin)

    // Spring's servlet mocks for McpOriginFilterTest.
    testImplementation(libs.spring.boot.starter.test)
}
