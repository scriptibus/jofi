// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.net

import java.net.http.HttpClient

/**
 * Known-bad: declares the net adapter's package but lives in another module (here the
 * architecture tests). The egress exemption needs the `adapters/net` module, not just the package.
 * Test fixture only, never production.
 */
class ImpostorNetAdapterFixture {
    fun client(): HttpClient = HttpClient.newHttpClient()
}
