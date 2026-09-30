// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.persistence

import io.github.scriptibus.jofi.applications.adapter.persistence.DetailColumns.writeDetails
import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationDetails
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationSource
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.CompanyRef
import io.github.scriptibus.jofi.applications.domain.ContactRef
import io.github.scriptibus.jofi.applications.domain.CurrencyCode
import io.github.scriptibus.jofi.applications.domain.DeclineCategory
import io.github.scriptibus.jofi.applications.domain.DeclineReason
import io.github.scriptibus.jofi.applications.domain.EmploymentType
import io.github.scriptibus.jofi.applications.domain.EstimateConfidence
import io.github.scriptibus.jofi.applications.domain.FormOfAddress
import io.github.scriptibus.jofi.applications.domain.HowApplied
import io.github.scriptibus.jofi.applications.domain.LanguageAndTone
import io.github.scriptibus.jofi.applications.domain.LanguageTag
import io.github.scriptibus.jofi.applications.domain.OfferDetails
import io.github.scriptibus.jofi.applications.domain.Pay
import io.github.scriptibus.jofi.applications.domain.PayBand
import io.github.scriptibus.jofi.applications.domain.PayPeriod
import io.github.scriptibus.jofi.applications.domain.PaySource
import io.github.scriptibus.jofi.applications.domain.PaySourceKind
import io.github.scriptibus.jofi.applications.domain.RemoteShare
import io.github.scriptibus.jofi.applications.domain.Score
import io.github.scriptibus.jofi.applications.domain.Seniority
import io.github.scriptibus.jofi.applications.domain.StatusChange
import io.github.scriptibus.jofi.applications.domain.Tone
import io.github.scriptibus.jofi.shared.adapter.persistence.ActorColumns
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.tables.records.ApplicationRecord
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.tables.records.ApplicationStatusChangeRecord
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Maps applications to `application` rows and back, explicitly and without business logic. Each write owns its columns (ADR-0041): [detailsRecord]
 * and [statusRecord] set only theirs, and jOOQ updates only the fields a record has set.
 */
internal object ApplicationRecords {
    fun toRecord(application: Application): ApplicationRecord =
        ApplicationRecord().apply {
            id = application.id.value
            writeDetails(application.details)
            writeStatus(application)
            unread = application.unread
            wantScore = application.wantScore?.decimal()
            fitScore = application.fitScore?.decimal()
            writeVersion(application)
            createdAt = application.createdAt.toUtc()
        }

    /** The detail columns, `version` and `updated_at`: what `updateDetails` writes. */
    fun detailsRecord(application: Application): ApplicationRecord =
        ApplicationRecord().apply {
            writeDetails(application.details)
            writeVersion(application)
        }

    /** The status, the decline reason, `version` and `updated_at`: what `changeStatus` writes. */
    fun statusRecord(application: Application): ApplicationRecord =
        ApplicationRecord().apply {
            writeStatus(application)
            writeVersion(application)
        }

    /** Only `version` and `updated_at`: what `replaceContacts` writes to the application row. */
    fun versionRecord(application: Application): ApplicationRecord =
        ApplicationRecord().apply { writeVersion(application) }

    fun toDomain(
        record: ApplicationRecord,
        contacts: Set<ContactRef>,
        sources: List<ApplicationSource>,
    ): Application =
        Application(
            id = ApplicationId(record.id),
            details = DetailColumns.details(record),
            contacts = contacts,
            unread = record.unread,
            wantScore = record.wantScore?.let(::score),
            fitScore = record.fitScore?.let(::score),
            status = ApplicationStatus.valueOf(record.status),
            declineReason =
                record.declineCategory?.let { DeclineReason(DeclineCategory.valueOf(it), record.declineReason) },
            version = record.version,
            createdAt = record.createdAt.toInstant(),
            updatedAt = record.updatedAt.toInstant(),
            sources = sources,
        )

    private fun ApplicationRecord.writeStatus(application: Application) {
        status = application.status.name
        declineCategory = application.declineReason?.category?.name
        declineReason = application.declineReason?.text
    }

    private fun ApplicationRecord.writeVersion(application: Application) {
        version = application.version
        updatedAt = application.updatedAt.toUtc()
    }

    private fun Score.decimal(): BigDecimal = BigDecimal.valueOf(tenths.toLong(), 1)

    private fun score(value: BigDecimal): Score = Score(value.movePointRight(1).intValueExact())
}

/** Maps status changes to `application_status_change` rows and back; the actor as in `changelog_entry`. */
internal object StatusChangeRecords {
    fun toRecord(change: StatusChange): ApplicationStatusChangeRecord =
        ApplicationStatusChangeRecord().apply {
            applicationId = change.application.value
            fromStatus = change.from?.name
            toStatus = change.to.name
            reason = change.reason
            declineCategory = change.declineCategory?.name
            actorKind = ActorColumns.kindOf(change.actor)
            actorName = ActorColumns.nameOf(change.actor)
            changedAt = change.at.toUtc()
        }

    fun toDomain(record: ApplicationStatusChangeRecord): StatusChange =
        StatusChange(
            application = ApplicationId(record.applicationId),
            from = record.fromStatus?.let(ApplicationStatus::valueOf),
            to = ApplicationStatus.valueOf(record.toStatus),
            reason = record.reason,
            declineCategory = record.declineCategory?.let(DeclineCategory::valueOf),
            actor = ActorColumns.toActor(record.actorKind, record.actorName),
            at = record.changedAt.toInstant(),
        )
}

/** The detail columns of `application` (what the user edits), written and read as a whole. */
internal object DetailColumns {
    fun ApplicationRecord.writeDetails(details: ApplicationDetails) {
        companyId = details.company.value
        title = details.title
        location = details.location
        remoteShare = details.remoteShare?.percent?.toShort()
        employmentType = details.employmentType?.name
        seniority = details.seniority?.name
        deadline = details.deadline
        howApplied = details.howApplied?.name
        portalNotes = details.portalNotes
        writePayBand(details.payBand)
        writeLanguageAndTone(details.languageAndTone)
        writeOffer(details.offer)
    }

    private fun ApplicationRecord.writePayBand(band: PayBand?) {
        val estimate = band?.source as? PaySource.Estimated
        payMin = band?.min
        payMax = band?.max
        payCurrency = band?.currency?.value
        payPeriod = band?.period?.name
        paySource = band?.source?.let(::kindOf)?.name
        payEstimateBasis = estimate?.basis
        payEstimateConfidence = estimate?.confidence?.name
    }

    private fun ApplicationRecord.writeLanguageAndTone(value: LanguageAndTone) {
        postingLanguage = value.postingLanguage?.value
        applicationLanguage = value.applicationLanguage?.value
        formOfAddress = value.formOfAddress?.name
        tone = value.tone?.name
    }

    private fun ApplicationRecord.writeOffer(offer: OfferDetails?) {
        offerSalary = offer?.salary?.amount
        offerSalaryCurrency = offer?.salary?.currency?.value
        offerSalaryPeriod = offer?.salary?.period?.name
        offerBonus = offer?.bonus
        offerBenefits = offer?.benefits
        offerRemoteShare = offer?.remoteShare?.percent?.toShort()
        offerVacationDays = offer?.vacationDays?.toShort()
        offerNoticePeriod = offer?.noticePeriod
        offerStartDate = offer?.startDate
        offerAnswerBy = offer?.answerBy
    }

    fun details(record: ApplicationRecord): ApplicationDetails =
        ApplicationDetails(
            title = record.title,
            company = CompanyRef(record.companyId),
            location = record.location,
            remoteShare = record.remoteShare?.let { RemoteShare(it.toInt()) },
            employmentType = record.employmentType?.let(EmploymentType::valueOf),
            seniority = record.seniority?.let(Seniority::valueOf),
            deadline = record.deadline,
            howApplied = record.howApplied?.let(HowApplied::valueOf),
            portalNotes = record.portalNotes,
            payBand = payBand(record),
            languageAndTone =
                LanguageAndTone(
                    record.postingLanguage?.let(::LanguageTag),
                    record.applicationLanguage?.let(::LanguageTag),
                    record.formOfAddress?.let(FormOfAddress::valueOf),
                    record.tone?.let(Tone::valueOf),
                ),
            offer = offer(record),
        )

    // `application_pay_band_complete` and `application_pay_estimate_complete`: all or nothing.
    private fun payBand(record: ApplicationRecord): PayBand? {
        val currency = record.payCurrency ?: return null
        val source =
            when (PaySourceKind.valueOf(record.paySource)) {
                PaySourceKind.POSTING -> {
                    PaySource.Posting
                }

                PaySourceKind.RECRUITER -> {
                    PaySource.Recruiter
                }

                PaySourceKind.ESTIMATED -> {
                    PaySource.Estimated(
                        record.payEstimateBasis,
                        EstimateConfidence.valueOf(record.payEstimateConfidence),
                    )
                }
            }
        return PayBand(
            record.payMin,
            record.payMax,
            CurrencyCode(currency),
            PayPeriod.valueOf(record.payPeriod),
            source,
        )
    }

    // `application_offer_salary_complete`: amount, currency and period together. No offer column set: no offer.
    private fun offer(record: ApplicationRecord): OfferDetails? {
        val columns =
            with(record) {
                listOf(offerSalary, offerBonus, offerBenefits, offerRemoteShare, offerVacationDays, offerNoticePeriod)
            }
        val dates = listOf(record.offerStartDate, record.offerAnswerBy)
        return if (columns.all { it == null } && dates.all { it == null }) null else offerDetails(record)
    }

    private fun offerDetails(record: ApplicationRecord): OfferDetails =
        with(record) {
            OfferDetails(
                offerSalary?.let { Pay(it, CurrencyCode(offerSalaryCurrency), PayPeriod.valueOf(offerSalaryPeriod)) },
                offerBonus,
                offerBenefits,
                offerRemoteShare?.let { RemoteShare(it.toInt()) },
                offerVacationDays?.toInt(),
                offerNoticePeriod,
                offerStartDate,
                offerAnswerBy,
            )
        }

    private fun kindOf(source: PaySource): PaySourceKind =
        when (source) {
            PaySource.Posting -> PaySourceKind.POSTING
            PaySource.Recruiter -> PaySourceKind.RECRUITER
            is PaySource.Estimated -> PaySourceKind.ESTIMATED
        }
}

private fun Instant.toUtc(): OffsetDateTime = atOffset(ZoneOffset.UTC)
