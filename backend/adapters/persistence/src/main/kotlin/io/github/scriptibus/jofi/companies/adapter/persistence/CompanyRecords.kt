// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.adapter.persistence

import io.github.scriptibus.jofi.companies.domain.Company
import io.github.scriptibus.jofi.companies.domain.CompanyDetails
import io.github.scriptibus.jofi.companies.domain.CompanyId
import io.github.scriptibus.jofi.companies.domain.CompanyPreference
import io.github.scriptibus.jofi.companies.domain.CompanyProfile
import io.github.scriptibus.jofi.companies.domain.CompanySearch
import io.github.scriptibus.jofi.companies.domain.CompanySize
import io.github.scriptibus.jofi.companies.domain.PreferenceKind
import io.github.scriptibus.jofi.companies.domain.WebAddress
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.COMPANY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.tables.records.CompanyRecord
import org.jooq.Condition
import org.jooq.SortField
import org.jooq.impl.DSL
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Maps companies to `company` rows and back, explicitly and without business logic. */
internal object CompanyRecords {
    fun toRecord(company: Company): CompanyRecord =
        CompanyRecord().apply {
            val details = company.details
            id = company.id.value
            name = details.name
            website = details.website?.value
            industry = details.industry
            size = details.size?.name
            locations = details.locations.toTypedArray()
            careersPage = details.careersPage?.value
            researchNotes = details.researchNotes
            profile = company.profile?.markdown
            profileGeneratedAt = company.profile?.generatedAt?.toUtc()
            preference = company.preference.kind.name
            preferenceReason = company.preference.reason
            version = company.version
            createdAt = company.createdAt.toUtc()
            updatedAt = company.updatedAt.toUtc()
        }

    fun toDomain(record: CompanyRecord): Company =
        Company(
            id = CompanyId(record.id),
            details =
                CompanyDetails(
                    name = record.name,
                    website = record.website?.let(::WebAddress),
                    industry = record.industry,
                    size = record.size?.let(CompanySize::valueOf),
                    locations = record.locations.toList(),
                    careersPage = record.careersPage?.let(::WebAddress),
                    researchNotes = record.researchNotes,
                ),
            // `company_profile_has_generation_time`: both or neither.
            profile = record.profile?.let { CompanyProfile(it, record.profileGeneratedAt.toInstant()) },
            preference = CompanyPreference.of(PreferenceKind.valueOf(record.preference), record.preferenceReason),
            version = record.version,
            createdAt = record.createdAt.toInstant(),
            updatedAt = record.updatedAt.toInstant(),
        )

    private fun Instant.toUtc(): OffsetDateTime = atOffset(ZoneOffset.UTC)
}

/**
 * The filter and order of a [CompanySearch]. Text matches names fuzzily through the trigram index
 * `company_name_trgm_idx` (pg_trgm ignores case): similar as a whole (`%`), similar to a word of the
 * name (`<%`, so "acme" finds "ACME Robotics GmbH"), or contained in it. Best match first: word
 * similarity, then similarity, then name.
 */
internal class CompanyQuery(
    search: CompanySearch,
) {
    val condition: Condition
    val order: List<SortField<*>>

    init {
        val text = search.text
        val preference = search.preference?.let { COMPANY.PREFERENCE.eq(it.name) } ?: DSL.noCondition()
        val byName = listOf(COMPANY.NAME.asc(), COMPANY.ID.asc())
        if (text == null) {
            condition = preference
            order = byName
        } else {
            val value = DSL.value(text)
            val matches =
                DSL
                    .condition("{0} % {1}", COMPANY.NAME, value)
                    .or(DSL.condition("{0} <% {1}", value, COMPANY.NAME))
                    .or(COMPANY.NAME.likeIgnoreCase("%${text.escapedForLike()}%", LIKE_ESCAPE))
            condition = preference.and(matches)
            order =
                listOf(
                    DSL.field("word_similarity({0}, {1})", Double::class.java, value, COMPANY.NAME).desc(),
                    DSL.field("similarity({0}, {1})", Double::class.java, COMPANY.NAME, value).desc(),
                ) + byName
        }
    }

    private fun String.escapedForLike(): String =
        replace("$LIKE_ESCAPE", "$LIKE_ESCAPE$LIKE_ESCAPE")
            .replace("%", "$LIKE_ESCAPE%")
            .replace("_", "${LIKE_ESCAPE}_")

    private companion object {
        const val LIKE_ESCAPE = '\\'
    }
}
