// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.persistence

import com.fasterxml.jackson.annotation.JsonInclude
import io.github.scriptibus.jofi.applications.domain.ApplicationSearchInput
import io.github.scriptibus.jofi.applications.domain.ApplicationSortKey
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.CompanyRef
import io.github.scriptibus.jofi.applications.domain.ContactRef
import io.github.scriptibus.jofi.applications.domain.SavedViewFilter
import io.github.scriptibus.jofi.applications.domain.Score
import io.github.scriptibus.jofi.applications.domain.SortDirection
import io.github.scriptibus.jofi.applications.domain.SourceKind
import org.jooq.JSONB
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.module.kotlin.jacksonMapperBuilder
import tools.jackson.module.kotlin.readValue
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * The stored form of a saved view's filter (`saved_view.filter`, format [VERSION] in `filter_version`, ADR-0050): a
 * JSON object with the keys of the list's query parameters (`search`, `companyId`, `status`, `wantMin`, ...), absent
 * keys for filters not set. Constants are stored by name. [read] is tolerant: unknown keys are ignored, a constant
 * that no longer exists is left out, and what today's rules refuse is left out by `SavedViewFilter.restore`; either
 * makes the view `adjusted`. A later format adds its version here and upgrades older documents step by step when
 * reading, so no migration has to rewrite JSON.
 */
internal object SavedViewDocument {
    const val VERSION = 1

    private val json = jacksonMapperBuilder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()

    fun write(filter: SavedViewFilter): JSONB = JSONB.valueOf(json.writeValueAsString(FilterV1.of(filter)))

    /** The filter of a document in format [version]; null if the version is unknown or the document unreadable. */
    fun read(
        version: Int,
        document: JSONB,
    ): SavedViewFilter.Restored? = if (version == VERSION) parse(document)?.restore() else null

    private fun parse(document: JSONB): FilterV1? =
        try {
            json.readValue<FilterV1>(document.data())
        } catch (_: JacksonException) {
            null
        }

    /** Format 1. Decoupled from the domain and the API on purpose: renaming either never changes stored views. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private data class FilterV1(
        val search: String? = null,
        val companyId: UUID? = null,
        val contactId: UUID? = null,
        val status: List<String> = emptyList(),
        val unread: Boolean? = null,
        val language: List<String> = emptyList(),
        val sourceKind: List<String> = emptyList(),
        val createdFrom: Instant? = null,
        val createdTo: Instant? = null,
        val updatedFrom: Instant? = null,
        val updatedTo: Instant? = null,
        val wantMin: BigDecimal? = null,
        val wantMax: BigDecimal? = null,
        val fitMin: BigDecimal? = null,
        val fitMax: BigDecimal? = null,
        val sort: String? = null,
        val direction: String? = null,
    ) {
        fun restore(): SavedViewFilter.Restored {
            val statuses = status.mapNotNull { name -> ApplicationStatus.entries.find { it.name == name } }
            val kinds = sourceKind.mapNotNull { name -> SourceKind.entries.find { it.name == name } }
            val sortKey = sort?.let { name -> ApplicationSortKey.entries.find { it.name == name } }
            val sortDirection = direction?.let { name -> SortDirection.entries.find { it.name == name } }
            val dropped =
                statuses.size < status.size || kinds.size < sourceKind.size ||
                    (sort != null && sortKey == null) || (direction != null && sortDirection == null)
            val input =
                ApplicationSearchInput(
                    search,
                    companyId?.let(::CompanyRef),
                    contactId?.let(::ContactRef),
                    statuses.toSet(),
                    unread,
                    language,
                    kinds.toSet(),
                    createdFrom,
                    createdTo,
                    updatedFrom,
                    updatedTo,
                    wantMin,
                    wantMax,
                    fitMin,
                    fitMax,
                    sortKey,
                    sortDirection,
                )
            return SavedViewFilter.restore(input, dropped)
        }

        companion object {
            fun of(filter: SavedViewFilter): FilterV1 =
                FilterV1(
                    filter.text,
                    filter.company?.value,
                    filter.contact?.value,
                    filter.statuses.names(),
                    filter.unread,
                    filter.languages.map { it.value },
                    filter.sourceKinds.names(),
                    filter.created?.from,
                    filter.created?.to,
                    filter.updated?.from,
                    filter.updated?.to,
                    filter.wantScore?.min.decimal(),
                    filter.wantScore?.max.decimal(),
                    filter.fitScore?.min.decimal(),
                    filter.fitScore?.max.decimal(),
                    filter.order?.key?.name,
                    filter.order?.direction?.name,
                )

            private fun Set<Enum<*>>.names(): List<String> = map { it.name }

            private fun Score?.decimal(): BigDecimal? = this?.let { BigDecimal.valueOf(it.tenths.toLong(), 1) }
        }
    }
}
