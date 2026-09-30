// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.adapter.persistence

import io.github.scriptibus.jofi.companies.domain.Company
import io.github.scriptibus.jofi.companies.domain.CompanyDetails
import io.github.scriptibus.jofi.companies.domain.CompanyId
import io.github.scriptibus.jofi.companies.domain.CompanyPreference
import io.github.scriptibus.jofi.companies.domain.CompanyProfile
import io.github.scriptibus.jofi.companies.domain.CompanySize
import io.github.scriptibus.jofi.companies.domain.PreferenceKind
import io.github.scriptibus.jofi.companies.domain.WebAddress
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.COMPANY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.tables.records.CompanyRecord
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
