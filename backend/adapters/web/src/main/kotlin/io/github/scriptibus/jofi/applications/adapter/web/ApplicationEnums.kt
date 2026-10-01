// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

// The API's copies of the domain enums (ADR-0041): the contract never exposes domain types, and a
// renamed domain constant fails `ApplicationDtosTest` instead of silently changing the API. They map
// by name with [mapByName].

/** Copy of `EmploymentType`. */
enum class JobEmploymentType {
    FULL_TIME,
    PART_TIME,
    CONTRACT,
    FREELANCE,
    INTERNSHIP,
    WORKING_STUDENT,
    APPRENTICESHIP,
    OTHER,
}

/** Copy of `Seniority`. */
enum class JobSeniority { ENTRY, JUNIOR, MID, SENIOR, LEAD, PRINCIPAL, EXECUTIVE }

/** Copy of `HowApplied`: how the user applied. */
enum class ApplyChannel { PORTAL, EMAIL, REFERRAL, OTHER }

/** Copy of `PayPeriod`: what an amount is paid per. */
enum class PayInterval { HOUR, DAY, MONTH, YEAR }

/** Copy of `PaySourceKind`: the posting, a recruiter, or an estimate (with basis and confidence). */
enum class PayBandSource { POSTING, RECRUITER, ESTIMATED }

/** Copy of `EstimateConfidence`. */
enum class PayEstimateConfidence { LOW, MEDIUM, HIGH }

/** Copy of `FormOfAddress`: German "Du", German "Sie", or neither (e.g. English). */
enum class AddressForm { DU, SIE, NEUTRAL }

/** Copy of `Tone`. */
enum class WritingTone { PERSONAL, PROFESSIONAL }

/** Copy of `DeclineCategory`: why the user declined or the company rejected. */
enum class DeclineReasonCategory {
    SALARY,
    LOCATION,
    REMOTE_POLICY,
    ROLE,
    COMPANY,
    SKILLS,
    TIMING,
    OTHER_OFFER,
    POSITION_FILLED,
    NO_REASON_GIVEN,
    OTHER,
}

/** Copy of `ApplicationStatus`: the pipeline, then the terminal statuses (spec §6.2, ADR-0044). */
enum class PipelineStatus {
    DISCOVERED,
    SHORTLISTED,
    PREPARING,
    APPLIED,
    INTERVIEWING,
    OFFER,
    ACCEPTED,
    REJECTED,
    WITHDRAWN,
    DECLINED,
    GHOSTED,
}

/** Who made a change: the user, the built-in AI, a scanner, an external client or an automatic rule or job. */
enum class ChangeActorKind { USER, AI, SCANNER, EXTERNAL_CLIENT, SYSTEM }

/** Copy of `SourceKind`: a scanner, an imported link, or entered by hand or in the chat. */
enum class PostingSourceKind { SCANNER, URL, MANUAL_CHAT }

/** Copy of `SnapshotReason`: why a description version was taken. */
enum class SnapshotCaptureReason { DISCOVERY, CHANGE_DETECTED, MANUAL }

/** Copy of `DiffOperation`. */
enum class DiffSegmentOperation { UNCHANGED, ADDED, REMOVED }

/** Copy of `InterviewType`: what kind of interview or call. */
enum class InterviewKind { PHONE_SCREEN, HR, TECHNICAL, CASE, ON_SITE, FINAL, OTHER }

/** Copy of `InterviewOutcome`: how it ended for the user; absent while it is to come or undecided. */
enum class InterviewResultKind { PASSED, REJECTED, WITHDRAWN, CANCELLED }

/** Copy of `ImportStatus`: where a posting import stands. */
enum class PostingImportStatus { PENDING, SUCCEEDED, FAILED }

/** Copy of `ImportFailure`: why a posting import failed; each can be retried. */
enum class PostingImportFailure {
    AI_NOT_CONFIGURED,
    AI_AUTHENTICATION_FAILED,
    AI_UNAVAILABLE,
    AI_REJECTED,
    UNREADABLE_ANSWER,
    NOT_A_POSTING,
    NOT_QUEUED,
    NOT_COMPLETED,
}

/** The constant of [T] with this constant's name (API enum to domain enum and back). */
internal inline fun <reified T : Enum<T>> Enum<*>.mapByName(): T = enumValueOf<T>(name)
