// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

import io.github.scriptibus.jofi.shared.domain.text.WebAddress

/**
 * Sites Jofi never fetches for a URL import (spec §8.1): no scraping workarounds for LinkedIn, StepStone or
 * Indeed, whatever the result would be, so the user is asked to paste the text instead before any fetch is tried.
 */
object DisallowedPostingHosts {
    private val DISALLOWED_LABELS = setOf("linkedin", "stepstone", "indeed")

    /** Whether [address] names one of the disallowed sites, by a dot-separated label of its host. */
    fun isDisallowed(address: WebAddress): Boolean =
        address.host
            .lowercase()
            .split('.')
            .any { it in DISALLOWED_LABELS }
}

/**
 * [this] with its well-known tracking query parameters stripped, its scheme, host and remaining parameter order
 * canonicalised, its default port and fragment dropped (#97): so a link shared twice with different tracking
 * parameters is recognised as the same posting. The stored link becomes this normalised form, not what the user
 * pasted.
 */
fun WebAddress.normalizedForImport(): WebAddress {
    val uri = toUri()
    val scheme = uri.scheme.lowercase()
    val defaultPort = if (scheme == "https") HTTPS_PORT else HTTP_PORT
    val portSuffix = if (uri.port == -1 || uri.port == defaultPort) "" else ":${uri.port}"
    val query = keptQuery(uri.rawQuery)?.let { "?$it" }.orEmpty()
    val normalized = "$scheme://${uri.host.lowercase()}$portSuffix${uri.rawPath.orEmpty()}$query"
    return checkNotNull(WebAddress.parse(normalized)) { "Normalizing $this must still yield a web address" }
}

private const val HTTP_PORT = 80
private const val HTTPS_PORT = 443

/** Known tracking parameters stripped for [normalizedForImport]: campaign, click-id and referrer trackers. */
private val TRACKING_PARAMETERS =
    setOf(
        "utm_source",
        "utm_medium",
        "utm_campaign",
        "utm_term",
        "utm_content",
        "utm_id",
        "utm_name",
        "gclid",
        "fbclid",
        "msclkid",
        "mc_cid",
        "mc_eid",
        "igshid",
        "ref",
        "ref_src",
        "trk",
        "ocid",
    )

private fun keptQuery(rawQuery: String?): String? =
    rawQuery
        ?.split('&')
        ?.filter { parameter ->
            parameter.isNotEmpty() &&
                parameter.substringBefore('=').lowercase() !in TRACKING_PARAMETERS
        }?.sorted()
        ?.joinToString("&")
        ?.takeIf(String::isNotEmpty)
