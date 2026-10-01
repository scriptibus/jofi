// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

import io.github.scriptibus.jofi.shared.domain.text.WebAddress
import java.net.IDN

/**
 * Sites Jofi never fetches for a URL import (spec §8.1): no scraping workarounds for LinkedIn, StepStone or
 * Indeed, whatever the result would be, so the user is asked to paste the text instead before any fetch is tried.
 */
object DisallowedPostingHosts {
    /** The sites' brand names: the registrable name under any country domain (`linkedin.com`, `de.indeed.com`). */
    private val DISALLOWED_BRANDS = setOf("linkedin", "stepstone", "indeed")

    /**
     * First-party domains of the sites under another name, with their subdomains: LinkedIn's own shortener
     * (`lnkd.in`), Indeed's careers and e-mail domains (`indeedjobs.com`, `indeedemail.com`). Gathered from the sites'
     * own published domains in 2026-10; StepStone is known to run none besides its country domains. A link that
     * reaches one of these by redirect is caught by the final-address check in any case.
     */
    private val DISALLOWED_DOMAINS = setOf("lnkd.in", "indeedjobs.com", "indeedemail.com")

    /** Second-level labels under a country domain (`indeed.co.uk`): the brand is one label further left. */
    private val SECOND_LEVEL_LABELS = setOf("co", "com", "org", "net", "ac", "gov", "edu")
    private const val COUNTRY_CODE_LENGTH = 2
    private const val LABELS_WITH_SECOND_LEVEL = 3
    private const val LABELS_FROM_RIGHT = 2

    /** Whether [address] names one of the disallowed sites. */
    fun isDisallowed(address: WebAddress): Boolean = isDisallowedHost(address.host)

    /** Whether [host] (as a web address or a fetch's final URI gives it) belongs to a disallowed site. */
    fun isDisallowedHost(host: String): Boolean {
        val normalized = (runCatching { IDN.toASCII(host) }.getOrNull() ?: host).lowercase().trimEnd('.')
        return registrableLabel(normalized.split('.')) in DISALLOWED_BRANDS ||
            DISALLOWED_DOMAINS.any { normalized == it || normalized.endsWith(".$it") }
    }

    /** The label that names the owner of a host: second from the right, or third under `co.uk` and alike. */
    private fun registrableLabel(labels: List<String>): String? {
        val underCountrySuffix =
            labels.size >= LABELS_WITH_SECOND_LEVEL && labels.last().length == COUNTRY_CODE_LENGTH &&
                labels[labels.size - 2] in SECOND_LEVEL_LABELS
        return labels.getOrNull(labels.size - if (underCountrySuffix) LABELS_WITH_SECOND_LEVEL else LABELS_FROM_RIGHT)
    }
}

/**
 * [this] with its well-known tracking query parameters stripped, its scheme, host and remaining parameter order
 * canonicalised, its default port dropped (#97): so a link shared twice with different tracking parameters is
 * recognised as the same posting. The stored link becomes this normalised form, not what the user pasted. The
 * fragment is dropped unless it is a route (`#/jobs/1`), which single-page career sites use to name the posting.
 * Null when there is no such form: a host `java.net.URI` cannot parse as a server name (an underscore, a bad
 * escape), or a normalised link that is too long (an internationalised host grows in its ASCII form).
 */
fun WebAddress.normalizedForImport(): WebAddress? {
    val uri = toUriOrNull()
    val host =
        uri
            ?.host
            ?.lowercase()
            ?.trimEnd('.')
            ?.takeIf(String::isNotEmpty)
    if (uri == null || host == null) return null
    val scheme = uri.scheme.lowercase()
    val defaultPort = if (scheme == "https") HTTPS_PORT else HTTP_PORT
    val portSuffix = if (uri.port == -1 || uri.port == defaultPort) "" else ":${uri.port}"
    val query = keptQuery(uri.rawQuery)?.let { "?$it" }.orEmpty()
    val route =
        uri.rawFragment
            ?.takeIf { it.startsWith("/") || it.startsWith("!/") }
            ?.let { "#$it" }
            .orEmpty()
    return WebAddress.parse("$scheme://$host$portSuffix${uri.rawPath.orEmpty()}$query$route")
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
