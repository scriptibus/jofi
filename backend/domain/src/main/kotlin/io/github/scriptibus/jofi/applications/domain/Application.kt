// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.EntityRef
import java.time.Instant
import java.util.UUID

/** Identifies one application. */
@JvmInline
value class ApplicationId(
    val value: UUID,
) {
    /** How changelog entries refer to this application (entity type [ENTITY_TYPE]). */
    fun toEntityRef(): EntityRef = EntityRef(ENTITY_TYPE, value.toString())

    companion object {
        /** The changelog entity type of applications (spec §13); never rename it, stored entries use it. */
        const val ENTITY_TYPE = "application"
    }
}

/**
 * The company an application is for, by the id the companies context gave it. Applications refer to
 * other contexts' aggregates by id only (ADR-0041), so the domain does not depend on the companies
 * context; `application_company_fk` keeps the reference valid.
 */
@JvmInline
value class CompanyRef(
    val value: UUID,
)

/** A contact person linked to an application, by the companies context's id (see [CompanyRef]). */
@JvmInline
value class ContactRef(
    val value: UUID,
)

/**
 * A job the user tracks (spec §6.1): discovered, considered, applied to or declined. The user edits
 * [details]; [contacts] are linked separately; [unread] marks entries a scanner created that the user
 * has not opened yet. [wantScore] and [fitScore] are placeholders for the scoring pipeline (M2).
 * [status] moves only through [changeStatus] (ADR-0044); a `DECLINED` or `REJECTED` application holds
 * its [declineReason], any other none. [sources] say where the job was found (#78), each with its
 * description history ([DescriptionSnapshot], stored apart). Interviews (#79) and tasks (#80) attach to it.
 *
 * [version] counts changes: a change is stored only if the stored version is still the one it was
 * based on, so edits by the user, the AI and external clients cannot overwrite each other. Marking
 * it read or unread is not a change of the application and keeps the version, and so is adding a source
 * or marking one offline: imports and scanners record those while the user edits, in rows of their own.
 */
data class Application(
    val id: ApplicationId,
    val details: ApplicationDetails,
    val contacts: Set<ContactRef>,
    val unread: Boolean,
    val wantScore: Score?,
    val fitScore: Score?,
    val status: ApplicationStatus,
    /** Why the user declined or the company rejected (spec §6.1), exactly while [status] takes one. */
    val declineReason: DeclineReason?,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Where the job was found, oldest first. */
    val sources: List<ApplicationSource> = emptyList(),
) {
    init {
        require(contacts.size <= MAX_CONTACTS) { "An application links at most $MAX_CONTACTS contacts" }
        require(sources.size <= MAX_SOURCES) { "An application has at most $MAX_SOURCES sources" }
        require(sources.all { it.application == id } && sources.distinctBy { it.id }.size == sources.size) {
            "An application's sources are its own, each once"
        }
        require(version >= INITIAL_VERSION) { "An application version must not be negative" }
        require(!updatedAt.isBefore(createdAt)) { "An application cannot be updated before it was created" }
        require((declineReason != null) == status.takesDeclineReason) {
            "An application holds a decline reason exactly while it is declined or rejected"
        }
    }

    /**
     * The application with new [details], changed [at]; the same application (no new version, nothing
     * to record) if the details are unchanged. Callers check the client's version before.
     */
    fun edit(
        details: ApplicationDetails,
        at: Instant,
    ): Application =
        if (details ==
            this.details
        ) {
            this
        } else {
            copy(details = details, version = version + 1, updatedAt = at)
        }

    /** The application linked to exactly [contacts], changed [at]; the same application if nothing changes. */
    fun linkContacts(
        contacts: Set<ContactRef>,
        at: Instant,
    ): Application =
        if (contacts == this.contacts) this else copy(contacts = contacts, version = version + 1, updatedAt = at)

    /**
     * The application moved as [request] asks, by [actor] [at]: a new version holding the request's decline
     * reason (or none, which is how reopening clears it), with the history entry and the event; or
     * [StatusTransition.NotAllowed] if the matrix has no such move. Callers check the client's version before.
     */
    fun changeStatus(
        request: StatusChangeRequest,
        actor: Actor,
        at: Instant,
    ): StatusTransition {
        val target = request.status
        val reason = request.declineReason
        return when {
            target == status && reason == declineReason -> {
                StatusTransition.Unchanged
            }

            !status.canMoveTo(target) -> {
                StatusTransition.NotAllowed(status, target)
            }

            else -> {
                StatusTransition.Changed(
                    copy(status = target, declineReason = reason, version = version + 1, updatedAt = at),
                    StatusChange(id, status, target, request.reason, request.declineCategory, actor, at),
                    ApplicationStatusChanged(id, status, target, actor, at),
                )
            }
        }
    }

    /**
     * The application with [source] added (spec §6.1: the same job found in another place), neither a new
     * version nor a new `updatedAt`; [ApplicationField.SOURCES] [ApplicationProblem.TOO_MANY] if it has
     * [MAX_SOURCES] already.
     */
    fun addSource(source: ApplicationSource): ApplicationValidation<Application> {
        require(source.application == id) { "A source belongs to its application" }
        return if (sources.size < MAX_SOURCES) {
            ApplicationValidation.Valid(copy(sources = sources + source))
        } else {
            ApplicationValidation.Invalid(
                listOf(ApplicationViolation(ApplicationField.SOURCES, ApplicationProblem.TOO_MANY)),
            )
        }
    }

    /** The application marked [unread] (or read); neither a new version nor a new `updatedAt`. */
    fun markUnread(unread: Boolean): Application = copy(unread = unread)

    override fun toString(): String = "Application(id=${id.value}, version=$version)"

    companion object {
        const val INITIAL_VERSION = 0L
        const val MAX_CONTACTS = 50
        const val MAX_SOURCES = 50

        /** The confirmable operation (ADR-0039) of deleting applications; its targets are application ids. */
        const val DELETE_OPERATION = "applications.delete"

        /**
         * A new application in [ApplicationStatus.INITIAL] without contacts or scores, created [at]; [unread]
         * for entries the user did not create themselves (scanners, imports). Its first history entry is
         * [StatusChange.initial].
         */
        fun create(
            id: ApplicationId,
            details: ApplicationDetails,
            at: Instant,
            unread: Boolean = false,
        ): Application =
            Application(
                id,
                details,
                emptySet(),
                unread,
                null,
                null,
                ApplicationStatus.INITIAL,
                null,
                INITIAL_VERSION,
                at,
                at,
            )
    }
}

/**
 * A Want or Fit score (spec §6.1) on the 0–5 scale with one decimal, kept as [tenths] (0 to 50) so
 * it is exact. A placeholder until scoring (M2): nothing computes it yet.
 */
@JvmInline
value class Score(
    val tenths: Int,
) {
    init {
        require(tenths in 0..MAX_TENTHS) { "A score is between 0 and 5 with one decimal" }
    }

    override fun toString(): String = "$tenths/$TENTHS_PER_POINT"

    companion object {
        const val TENTHS_PER_POINT = 10
        const val MAX_TENTHS = 50
    }
}
