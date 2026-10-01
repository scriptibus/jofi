// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.github.scriptibus.jofi.shared.domain.job.JobType
import io.github.scriptibus.jofi.shared.domain.text.WebAddress
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Identifies one posting import. */
@JvmInline
value class ImportId(
    val value: UUID,
) {
    /** How changelog entries refer to this import (entity type [ENTITY_TYPE]). */
    fun toEntityRef(): EntityRef = EntityRef(ENTITY_TYPE, value.toString())

    companion object {
        /** The changelog entity type of posting imports; never rename it, stored entries use it. */
        const val ENTITY_TYPE = "posting_import"
    }
}

/** Where a posting import stands. Never rename a constant: the database stores the names. */
enum class ImportStatus { PENDING, SUCCEEDED, FAILED }

/** Why a posting import failed; the user can retry each. Never rename a constant: the database stores the names. */
enum class ImportFailure {
    /** No model is assigned to the extraction task (any more). */
    AI_NOT_CONFIGURED,

    /** The provider rejected the configured key. */
    AI_AUTHENTICATION_FAILED,

    /** The provider could not be reached, throttled the call or failed on its side; retrying later may help. */
    AI_UNAVAILABLE,

    /**
     * The request was refused and the same request would be refused again: by the provider or the model (no
     * structured output, the posting is too long for it), by the "never send to AI" filter, or by the budget cap.
     */
    AI_REJECTED,

    /** The model's answer was cut off or is no JSON object of the requested shape. */
    UNREADABLE_ANSWER,

    /** The answer names no valid job title or company: the text is probably no job posting. */
    NOT_A_POSTING,

    /** The import could not be queued for the worker. */
    NOT_QUEUED,

    /** The worker could not finish the import (a store kept failing, or something unexpected went wrong). */
    NOT_COMPLETED,
}

/**
 * A job posting pasted or fetched from a [sourceUrl], on its way to a `DISCOVERED` application (spec §8.1, #96,
 * #97). The [text] is untrusted data, never instructions. A worker job extracts the fields with AI and creates the
 * [application] with a source (`MANUAL_CHAT` for pasted text, `URL` for [sourceUrl]) whose first description
 * snapshot is the [text]. The text is kept while the import is pending or failed, so a failed import can be
 * retried ([retried]), and dropped when it succeeds: from then on the application holds it, and deleting the
 * application leaves no copy behind. [attempt] counts the runs asked for (the first and every retry), so a stale
 * job run cannot overwrite a newer one. [toString] leaves out the text and the link.
 */
data class PostingImport(
    val id: ImportId,
    val text: DescriptionText?,
    val status: ImportStatus,
    val failure: ImportFailure?,
    val application: ApplicationId?,
    val attempt: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val sourceUrl: WebAddress? = null,
) {
    init {
        require((status == ImportStatus.SUCCEEDED) == (application != null)) {
            "Exactly a succeeded import names its application"
        }
        require(
            (status == ImportStatus.SUCCEEDED) == (text == null),
        ) { "Exactly a succeeded import has handed on its text" }
        require((status == ImportStatus.FAILED) == (failure != null)) { "Exactly a failed import names its failure" }
        require(attempt >= 1) { "An import has at least one attempt" }
        require(!updatedAt.isBefore(createdAt)) { "An import cannot change before it was started" }
    }

    /** This pending import done, having created [application] at [at]. */
    fun succeeded(
        application: ApplicationId,
        at: Instant,
    ): PostingImport {
        require(status == ImportStatus.PENDING) { "Only a pending import can succeed" }
        return copy(
            text = null,
            status = ImportStatus.SUCCEEDED,
            application = application,
            updatedAt = maxOf(at, updatedAt),
        )
    }

    /** This pending import failed for [reason] at [at]. */
    fun failed(
        reason: ImportFailure,
        at: Instant,
    ): PostingImport {
        require(status == ImportStatus.PENDING) { "Only a pending import can fail" }
        return copy(status = ImportStatus.FAILED, failure = reason, updatedAt = maxOf(at, updatedAt))
    }

    /**
     * Whether this import has been pending for at least [STALLED_AFTER] at [at]: its job is gone (a restored backup
     * has no job queue) or keeps failing, so it may be retried or given up.
     */
    fun stalled(at: Instant): Boolean = status == ImportStatus.PENDING && !updatedAt.plus(STALLED_AFTER).isAfter(at)

    /** A failed or [stalled] import pending again as its next attempt, asked for at [at]; `null` otherwise. */
    fun retried(at: Instant): PostingImport? =
        if (status == ImportStatus.FAILED || stalled(at)) {
            copy(status = ImportStatus.PENDING, failure = null, attempt = attempt + 1, updatedAt = maxOf(at, updatedAt))
        } else {
            null
        }

    /** Where the application's job was found: the link fetched, or pasted text, discovered when the import started. */
    fun toSourceInput(): SourceInput =
        if (sourceUrl != null) {
            SourceInput(SourceKind.URL, sourceUrl.value, createdAt, text?.value)
        } else {
            SourceInput(SourceKind.MANUAL_CHAT, null, createdAt, text?.value)
        }

    override fun toString(): String =
        "PostingImport(id=${id.value}, status=$status, failure=$failure, attempt=$attempt)"

    companion object {
        /** The worker job that runs an import; also the name of its failure reasons in the job log. */
        val JOB_TYPE = JobType("posting-import")

        /** The job argument that names the import (ids only, ADR-0038). */
        const val JOB_ARGUMENT = "import"

        /**
         * How long an import may stay pending before it counts as stalled (ADR-0051). A run takes seconds; JobRunr's
         * first retries after a failure come within minutes. Long enough not to race a live run, short enough that
         * the user is not left waiting when the job is gone.
         */
        val STALLED_AFTER: Duration = Duration.ofMinutes(30)

        /** A new import of [text], pending its first attempt, started [at]; fetched from [sourceUrl] for #97. */
        fun start(
            id: ImportId,
            text: DescriptionText,
            at: Instant,
            sourceUrl: WebAddress? = null,
        ): PostingImport = PostingImport(id, text, ImportStatus.PENDING, null, null, 1, at, at, sourceUrl)

        /**
         * An import that found [sourceUrl] already imported as [application] (#97): recorded, with its own
         * changelog entry, so the attempt is not silent, even though nothing was fetched.
         */
        fun alreadyImported(
            id: ImportId,
            application: ApplicationId,
            sourceUrl: WebAddress,
            at: Instant,
        ): PostingImport = PostingImport(id, null, ImportStatus.SUCCEEDED, null, application, 1, at, at, sourceUrl)
    }
}

/**
 * Outcome of starting a URL import (#97): a new pending [import] ([Started]), the one already pending for the link
 * ([AlreadyPending], a double submit, #187 finding F6), or one recorded at once because the link was already
 * imported successfully ([AlreadyImported]).
 */
sealed interface UrlImportOutcome {
    val import: PostingImport

    data class Started(
        override val import: PostingImport,
    ) : UrlImportOutcome

    data class AlreadyPending(
        override val import: PostingImport,
    ) : UrlImportOutcome

    data class AlreadyImported(
        override val import: PostingImport,
    ) : UrlImportOutcome
}

/**
 * The fields a model read from a posting: as untrusted as the posting itself. [toDetails] passes them through
 * [ApplicationInput.validate] like any input. [toString] names only which fields are present.
 */
data class ExtractedPosting(
    val title: String?,
    val company: String?,
    val location: String? = null,
    val remoteShare: Int? = null,
    val employmentType: EmploymentType? = null,
    val seniority: Seniority? = null,
    val deadline: LocalDate? = null,
    val postingLanguage: String? = null,
    val payBand: PayBandInput? = null,
) {
    /**
     * This reading checked with [ApplicationInput.validate] before any company is looked up or created: an optional
     * field that breaks a rule is left out (the model's reading is only a suggestion the user can correct); `null` if
     * the company name is missing or blank, or the title is missing or broken, which leaving out cannot fix. Whether
     * the company exists is not part of the check.
     */
    fun checked(): CheckedPosting? {
        val name = company?.trim()?.takeIf(String::isNotEmpty)
        val input = toInput(UNCHECKED_COMPANY)
        return if (name != null && input != null) CheckedPosting(name, input) else null
    }

    private fun toInput(company: CompanyRef): ApplicationInput? {
        val input =
            ApplicationInput(
                title = title.orEmpty(),
                company = company,
                location = location,
                remoteShare = remoteShare,
                employmentType = employmentType,
                seniority = seniority,
                deadline = deadline,
                payBand = payBand,
                languageAndTone = postingLanguage?.let { LanguageAndToneInput(postingLanguage = it) },
            )
        val checked = input.validate() as? ApplicationValidation.Invalid ?: return input
        return input.without(checked.violations).takeIf { it.validate() is ApplicationValidation.Valid }
    }

    private fun ApplicationInput.without(violations: List<ApplicationViolation>): ApplicationInput {
        val broken = violations.map { it.field }.toSet()
        return copy(
            location = location.takeUnless { ApplicationField.LOCATION in broken },
            remoteShare = remoteShare.takeUnless { ApplicationField.REMOTE_SHARE in broken },
            payBand = payBand.takeUnless { broken.any(PAY_FIELDS::contains) },
            languageAndTone = languageAndTone.takeUnless { ApplicationField.POSTING_LANGUAGE in broken },
        )
    }

    override fun toString(): String {
        val present =
            mapOf(
                "title" to title,
                "company" to company,
                "location" to location,
                "remoteShare" to remoteShare,
                "employmentType" to employmentType,
                "seniority" to seniority,
                "deadline" to deadline,
                "postingLanguage" to postingLanguage,
                "payBand" to payBand,
            ).filterValues { it != null }.keys
        return "ExtractedPosting(present=$present)"
    }

    private companion object {
        /** Stands in for the company while checking: `validate` never looks at whether it exists. */
        val UNCHECKED_COMPANY = CompanyRef(UUID(0, 0))

        val PAY_FIELDS =
            setOf(
                ApplicationField.PAY_MIN,
                ApplicationField.PAY_MAX,
                ApplicationField.PAY_CURRENCY,
                ApplicationField.PAY_ESTIMATE_BASIS,
                ApplicationField.PAY_ESTIMATE_CONFIDENCE,
            )
    }
}

/**
 * A reading that passed [ExtractedPosting.checked]: the [company] name to match and the valid input, which only needs
 * the matched company. [toString] leaves out both.
 */
class CheckedPosting internal constructor(
    val company: String,
    private val input: ApplicationInput,
) {
    fun toInput(company: CompanyRef): ApplicationInput = input.copy(company = company)

    override fun toString(): String = "CheckedPosting"
}

/** Outcome of reading a posting with AI: the fields, or why there are none. */
sealed interface PostingExtraction {
    data class Extracted(
        val posting: ExtractedPosting,
    ) : PostingExtraction

    data class Failed(
        val reason: ImportFailure,
    ) : PostingExtraction
}
