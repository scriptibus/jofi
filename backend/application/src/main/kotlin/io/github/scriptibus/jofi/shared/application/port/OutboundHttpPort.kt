// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.application.port

import io.github.scriptibus.jofi.shared.domain.http.FetchResult
import io.github.scriptibus.jofi.shared.domain.http.OutboundRequest

/**
 * The only way to reach the internet (threat model T1). Implemented once, in `adapters/net`, by the
 * SSRF guard (#18): scheme allowlist, internal-address block after DNS resolution, re-validated
 * redirects and the request's limits. Implementations never throw.
 */
interface OutboundHttpPort {
    fun fetch(request: OutboundRequest): FetchResult
}
