// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.fixture.adapter.web

import io.github.scriptibus.jofi.shared.adapter.mcp.Untrusted

/** Known-bad: a web adapter reaching into the shared MCP code, whose exemption is for MCP adapters only. */
class SharedMcpInWebAdapterFixture {
    fun mark(text: String) = Untrusted(text)
}
