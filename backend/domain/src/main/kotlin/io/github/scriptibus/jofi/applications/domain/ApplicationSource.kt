// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.github.scriptibus.jofi.shared.domain.text.WebAddress
import io.github.scriptibus.jofi.shared.domain.text.normalizedText
import java.time.Instant
import java.util.UUID

/** Identifies one source of an application. */
@JvmInline
value class SourceId(
    val value: UUID,
) {
    /** How changelog entries refer to this source (entity type [ENTITY_TYPE]). */
    fun toEntityRef(): EntityRef = EntityRef(ENTITY_TYPE, value.toString())

    companion object {
        /** The changelog entity type of application sources; never rename it, stored entries use it. */
        const val ENTITY_TYPE = "application_source"
    }
}

/** Where a job was found (spec §6.1). Never rename a constant: the database stores the names. */
enum class SourceKind {
    /** A scanner found it (M4). */
    SCANNER,

    /** Imported from a link the user gave (#97). */
    URL,

    /** Entered by hand or in the chat, e.g. pasted text (#96). */
    MANUAL_CHAT,
}

/**
 * One place the job was found (spec §6.1, §8.4): the same job found in several places is one application
 * with several sources, each with its own description history ([DescriptionSnapshot]). [originalUrl] is
 * the link as found, stored and shown but never fetched here (a fetch goes through the SSRF guard, #97); a
 * [SourceKind.URL] source always has one. [offlineSince] is when the posting was found gone; its
 * snapshots stay. [toString] leaves out the link, which may carry personal tracking parameters.
 */
data class ApplicationSource(
    val id: SourceId,
    val application: ApplicationId,
    val kind: SourceKind,
    val originalUrl: WebAddress?,
    val discoveredAt: Instant,
    val offlineSince: Instant? = null,
) {
    init {
        require(kind != SourceKind.URL || originalUrl != null) { "A URL source has a link" }
        require(offlineSince == null || !offlineSince.isBefore(discoveredAt)) {
            "A source cannot go offline before it was discovered"
        }
    }

    val online: Boolean get() = offlineSince == null

    /** The source marked offline [at] (its discovery time if [at] is earlier); an offline one stays as it is. */
    fun markOffline(at: Instant): ApplicationSource = if (online) copy(offlineSince = maxOf(at, discoveredAt)) else this

    /** The source marked online again (the posting is back). */
    fun markOnline(): ApplicationSource = if (online) this else copy(offlineSince = null)

    override fun toString(): String =
        "ApplicationSource(id=${id.value}, application=${application.value}, kind=$kind, online=$online)"

    companion object {
        /** The earliest discovery time input may name (as `BillingMonth.EARLIEST`); anything earlier is a typo. */
        val EARLIEST_DISCOVERY: Instant = Instant.parse("2000-01-01T00:00:00Z")
    }
}

/**
 * A source as entered: [originalUrl] is required for [SourceKind.URL]; [discoveredAt] defaults to now and lies between
 * [ApplicationSource.EARLIEST_DISCOVERY] and now; [description] is the posting's text at discovery, which becomes the
 * source's first snapshot ([SnapshotReason.DISCOVERY]). The text is untrusted data, never instructions. [toString]
 * leaves out the link and the text.
 */
data class SourceInput(
    val kind: SourceKind,
    val originalUrl: String? = null,
    val discoveredAt: Instant? = null,
    val description: String? = null,
) {
    /** The valid source, discovered [now] unless the input says earlier, or every problem found. */
    fun validate(now: Instant): ApplicationValidation<SourceDraft> {
        val checks = InputChecks()
        val url = originalUrl?.normalizedText()?.trim()?.takeIf(String::isNotEmpty)
        val address = url?.let(WebAddress::parse)
        if (url != null && address == null) checks.report(ApplicationField.SOURCE_URL, ApplicationProblem.INVALID_URL)
        if (url == null &&
            kind == SourceKind.URL
        ) {
            checks.report(ApplicationField.SOURCE_URL, ApplicationProblem.REQUIRED)
        }
        if (discoveredAt != null && !discoveredAt.isWithin(ApplicationSource.EARLIEST_DISCOVERY, now)) {
            checks.report(ApplicationField.DISCOVERED_AT, ApplicationProblem.OUT_OF_RANGE)
        }
        val text = description?.let { checks.description(it, required = false) }
        if (checks.count > 0) return ApplicationValidation.Invalid(checks.violations)
        return ApplicationValidation.Valid(SourceDraft(kind, address, discoveredAt ?: now, text))
    }

    override fun toString(): String = "SourceInput(kind=$kind, discoveredAt=$discoveredAt)"
}

private fun Instant.isWithin(
    earliest: Instant,
    latest: Instant,
): Boolean = !isBefore(earliest) && !isAfter(latest)

/**
 * A valid [SourceInput]: the source to add once it has an id, and its text at discovery, if any, which
 * becomes the source's first snapshot through [DescriptionSnapshot.discovery].
 */
data class SourceDraft(
    val kind: SourceKind,
    val originalUrl: WebAddress?,
    val discoveredAt: Instant,
    val description: DescriptionText?,
) {
    fun toSource(
        id: SourceId,
        application: ApplicationId,
    ): ApplicationSource = ApplicationSource(id, application, kind, originalUrl, discoveredAt)

    override fun toString(): String = "SourceDraft(kind=$kind, discoveredAt=$discoveredAt)"
}
