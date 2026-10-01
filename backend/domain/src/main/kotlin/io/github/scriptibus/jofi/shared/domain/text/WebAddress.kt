// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.text

import java.net.IDN
import java.net.URI

/**
 * An absolute http(s) URL with a host and without user info (credentials never belong in a stored
 * link), at most [MAX_LENGTH] characters, kept as entered. Hosts may be internationalised
 * (`bücher.example`) or contain underscores (`my_team.example`), which `java.net.URI` alone does not
 * accept as a host, so the host is checked as its ASCII form (`IDN.toASCII`) and the rest as a URI
 * (ADR-0041). Only a value: nothing here fetches it (outbound fetches go through `OutboundHttpPort`).
 * [toString] prints only the [host], so paths and tracking parameters stay out of logs; use [value] for the
 * link itself. The companies context keeps its own copy until it is next touched.
 */
@JvmInline
value class WebAddress(
    val value: String,
) {
    init {
        require(isValid(value)) { "A web address must be an absolute http(s) URL with a host and no user info" }
    }

    /** The host as entered, without port, path, query or fragment. */
    val host: String get() = checkNotNull(SHAPE.matchEntire(value)?.groups?.get(1)).value.replace(PORT, "")

    /**
     * This address as a [URI], ready for `OutboundHttpPort`: the host in its ASCII form ([IDN.toASCII]), since
     * `URI` alone does not resolve an internationalised host. A host [value] accepts but `URI` cannot parse as a
     * server authority at all (an underscore) still becomes a `URI`, just one whose `host` answers `null`; the net
     * adapter then cannot resolve a destination for it, so such a link is reported unreachable, never fetched.
     */
    fun toUri(): URI {
        val authority = checkNotNull(SHAPE.matchEntire(value)?.groups?.get(1))
        val asciiAuthority = authority.value.replace(host, IDN.toASCII(host))
        val rest = value.substring(authority.range.last + 1)
        return URI("${value.substringBefore("://")}://$asciiAuthority$rest")
    }

    /**
     * [toUri], or null for a value `java.net.URI` rejects (`https://[abc/x`, a malformed escape, a character no
     * authority may contain). No exception escapes: its message would carry the whole link into a log.
     */
    fun toUriOrNull(): URI? = runCatching(::toUri).getOrNull()

    override fun toString(): String = "WebAddress(host=$host)"

    companion object {
        const val MAX_LENGTH = 2_048

        // scheme, authority (no user info, no whitespace), then an optional path, query or fragment
        private val SHAPE = Regex("""^(?i)https?://([^/?#@\s]+)([/?#]\S*)?$""")
        private val PORT = Regex(""":\d{1,5}$""")

        /** The address [raw] names, or `null` if it is not a valid web address. */
        fun parse(raw: String): WebAddress? = raw.takeIf(::isValid)?.let(::WebAddress)

        private fun isValid(raw: String): Boolean {
            val authority = SHAPE.matchEntire(raw)?.groups?.get(1) ?: return false
            val host = authority.value.replace(PORT, "")
            // The URI check sees a placeholder host, since URI rejects some valid hosts (underscores).
            val rest = raw.substring(authority.range.last + 1)
            return raw.length <= MAX_LENGTH &&
                !raw.hasUnstorableCharacter() &&
                ':' !in host &&
                !runCatching { IDN.toASCII(host) }.getOrNull().isNullOrEmpty() &&
                runCatching { URI("http://host.invalid$rest") }.isSuccess
        }
    }
}
