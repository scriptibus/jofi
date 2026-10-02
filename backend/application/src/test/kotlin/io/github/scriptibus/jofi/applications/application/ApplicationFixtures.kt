// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.ApplicationRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.DescriptionSnapshotRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.InterviewRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.spi.LinkedTasksPort
import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationDetails
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationPage
import io.github.scriptibus.jofi.applications.domain.ApplicationSearch
import io.github.scriptibus.jofi.applications.domain.ApplicationSource
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.CompanyRef
import io.github.scriptibus.jofi.applications.domain.ContactRef
import io.github.scriptibus.jofi.applications.domain.DescriptionSnapshot
import io.github.scriptibus.jofi.applications.domain.Interview
import io.github.scriptibus.jofi.applications.domain.InterviewId
import io.github.scriptibus.jofi.applications.domain.SnapshotId
import io.github.scriptibus.jofi.applications.domain.SnapshotSummary
import io.github.scriptibus.jofi.applications.domain.SortDirection
import io.github.scriptibus.jofi.applications.domain.SourceId
import io.github.scriptibus.jofi.applications.domain.SourceKind
import io.github.scriptibus.jofi.applications.domain.StatusChange
import io.github.scriptibus.jofi.applications.domain.UpcomingInterview
import io.github.scriptibus.jofi.shared.application.ConfirmActionUseCase
import io.github.scriptibus.jofi.shared.application.RedactForAiUseCase
import io.github.scriptibus.jofi.shared.application.port.AiVisibilityPort
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.ConfirmationStorePort
import io.github.scriptibus.jofi.shared.application.port.DomainEventPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ChangelogEntry
import io.github.scriptibus.jofi.shared.domain.ChangelogLimit
import io.github.scriptibus.jofi.shared.domain.ChangelogResult
import io.github.scriptibus.jofi.shared.domain.DomainEvent
import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.github.scriptibus.jofi.shared.domain.ai.AiVisibilityResult
import io.github.scriptibus.jofi.shared.domain.ai.ContentSource
import io.github.scriptibus.jofi.shared.domain.ai.FlaggedValue
import io.github.scriptibus.jofi.shared.domain.ai.NeverSendRules
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import io.github.scriptibus.jofi.shared.domain.confirmation.PendingConfirmation
import io.github.scriptibus.jofi.shared.domain.paging.PageRequest
import io.github.scriptibus.jofi.shared.domain.paging.Paged
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * An in-memory application store behind every port the application use cases take, with a transaction
 * that restores all of it (applications, history, changelog, events) when the result is not committed.
 * Like the database, it refuses applications of unknown companies and links to unknown contacts.
 */
class ApplicationFixtures {
    val applications = linkedMapOf<ApplicationId, Application>()
    val history = mutableListOf<StatusChange>()
    val companies = mutableSetOf(ACME)

    /** The contacts that exist, which links may name (`application_contact_contact_fk`). */
    val contacts = mutableSetOf<ContactRef>()
    val entries = mutableListOf<ChangelogEntry>()
    val events = mutableListOf<DomainEvent>()
    var failingChangelog = false

    /** An entity type whose entries the changelog refuses, to fail a use case after its first entries. */
    var failingChangelogFor: String? = null
    var failingEvents = false
    var failingStore = false

    /** What the "never send to AI" source flags; `null` makes it unavailable. */
    var flaggedValues: Set<FlaggedValue>? = emptySet()

    val redaction =
        RedactForAiUseCase(
            object : AiVisibilityPort {
                override fun rulesFor(sources: Set<ContentSource>) =
                    flaggedValues
                        ?.let { AiVisibilityResult.Known(NeverSendRules(emptyMap(), it)) }
                        ?: AiVisibilityResult.Unavailable("test")
            },
        )

    /** Description snapshots per application, which the delete cascades to. */
    val snapshots = mutableMapOf<ApplicationId, Int>()

    /** A version another client stored between this use case's read and its write (the update race). */
    var concurrentVersion: Long? = null

    val repository =
        object : ApplicationRepositoryPort {
            override fun add(
                application: Application,
                initial: StatusChange,
            ): ApplicationStoreResult<Unit> =
                when {
                    failingStore -> {
                        ApplicationStoreResult.StorageFailure("add")
                    }

                    application.details.company !in companies -> {
                        ApplicationStoreResult.CompanyNotFound
                    }

                    else -> {
                        applications[application.id] = application
                        history += initial
                        ApplicationStoreResult.Success(Unit)
                    }
                }

            override fun updateDetails(application: Application): ApplicationStoreResult<Unit> {
                val stored = applications[application.id]
                return when {
                    stored == null -> {
                        ApplicationStoreResult.NotFound
                    }

                    (concurrentVersion ?: stored.version) != application.version - 1 -> {
                        ApplicationStoreResult.VersionConflict
                    }

                    application.details.company !in companies -> {
                        ApplicationStoreResult.CompanyNotFound
                    }

                    else -> {
                        applications[application.id] =
                            stored.copy(
                                details = application.details,
                                version = application.version,
                                updatedAt = application.updatedAt,
                            )
                        ApplicationStoreResult.Success(Unit)
                    }
                }
            }

            override fun replaceContacts(application: Application): ApplicationStoreResult<Unit> {
                val stored = applications[application.id]
                return when {
                    stored == null -> {
                        ApplicationStoreResult.NotFound
                    }

                    (concurrentVersion ?: stored.version) != application.version - 1 -> {
                        ApplicationStoreResult.VersionConflict
                    }

                    !contacts.containsAll(application.contacts) -> {
                        ApplicationStoreResult.ContactNotFound
                    }

                    else -> {
                        applications[application.id] =
                            stored.copy(
                                contacts = application.contacts,
                                version = application.version,
                                updatedAt = application.updatedAt,
                            )
                        ApplicationStoreResult.Success(Unit)
                    }
                }
            }

            override fun changeStatus(
                application: Application,
                change: StatusChange,
            ): ApplicationStoreResult<Unit> {
                val stored = applications[application.id]
                return when {
                    stored == null -> {
                        ApplicationStoreResult.NotFound
                    }

                    (concurrentVersion ?: stored.version) != application.version - 1 -> {
                        ApplicationStoreResult.VersionConflict
                    }

                    else -> {
                        applications[application.id] = application
                        history += change
                        ApplicationStoreResult.Success(Unit)
                    }
                }
            }

            override fun statusHistory(id: ApplicationId): ApplicationStoreResult<List<StatusChange>> =
                if (id in applications) {
                    ApplicationStoreResult.Success(history.filter { it.application == id })
                } else {
                    ApplicationStoreResult.NotFound
                }

            override fun setUnread(
                id: ApplicationId,
                unread: Boolean,
            ): ApplicationStoreResult<Unit> {
                val stored = applications[id] ?: return ApplicationStoreResult.NotFound
                applications[id] = stored.markUnread(unread)
                return ApplicationStoreResult.Success(Unit)
            }

            override fun findById(id: ApplicationId): ApplicationStoreResult<Application> =
                applications[id]?.let { ApplicationStoreResult.Success(it) } ?: ApplicationStoreResult.NotFound

            override fun search(search: ApplicationSearch): ApplicationStoreResult<ApplicationPage<Application>> =
                error("Not used by these use cases")

            override fun snapshotCount(id: ApplicationId): ApplicationStoreResult<Int> =
                if (id in applications) {
                    ApplicationStoreResult.Success(snapshots[id] ?: 0)
                } else {
                    ApplicationStoreResult.NotFound
                }

            override fun interviewCount(id: ApplicationId): ApplicationStoreResult<Int> =
                ApplicationStoreResult.Success(interviews.values.count { it.application == id })

            override fun delete(
                id: ApplicationId,
                proof: ConfirmationResult.Confirmed,
            ): ApplicationStoreResult<Unit> =
                when {
                    !proof.covers(Application.DELETE_OPERATION, id.value.toString()) -> {
                        ApplicationStoreResult.NotConfirmed
                    }

                    applications.remove(id) == null -> {
                        ApplicationStoreResult.NotFound
                    }

                    else -> {
                        history.removeAll { it.application == id }
                        interviews.values.removeAll { it.application == id }
                        linkedTasks.remove(id)
                        ApplicationStoreResult.Success(Unit)
                    }
                }
        }

    /** Tasks linked to each application, as the tasks context reports them; the delete clears the links (#168). */
    val linkedTasks = mutableMapOf<ApplicationId, List<EntityRef>>()
    var linkedTasksAvailable = true

    val linkedTaskPort =
        object : LinkedTasksPort {
            override fun linkedTo(application: UUID): LinkedTasksPort.Linked =
                if (linkedTasksAvailable) {
                    LinkedTasksPort.Linked.Found(linkedTasks[ApplicationId(application)].orEmpty())
                } else {
                    LinkedTasksPort.Linked.Unavailable
                }

            override fun linkedTasks(
                application: UUID,
                before: LinkedTasksPort.Before?,
                count: Int,
            ): LinkedTasksPort.Tasks = error("Not used by these use cases")
        }

    /** The stored interviews (#91); like the database, they go with their application. */
    val interviews = linkedMapOf<InterviewId, Interview>()

    /** An interview version another client stored between this use case's read and its write. */
    var concurrentInterviewVersion: Long? = null

    val interviewPort =
        object : InterviewRepositoryPort {
            override fun add(interview: Interview): ApplicationStoreResult<Unit> =
                when {
                    failingStore -> {
                        ApplicationStoreResult.StorageFailure("add interview")
                    }

                    interview.application !in applications -> {
                        ApplicationStoreResult.NotFound
                    }

                    !contacts.containsAll(interview.details.participants) -> {
                        ApplicationStoreResult.ContactNotFound
                    }

                    else -> {
                        interviews[interview.id] = interview
                        ApplicationStoreResult.Success(Unit)
                    }
                }

            override fun update(interview: Interview): ApplicationStoreResult<Unit> {
                val stored = interviews[interview.id]?.takeIf { it.application == interview.application }
                return when {
                    stored == null -> {
                        ApplicationStoreResult.NotFound
                    }

                    (concurrentInterviewVersion ?: stored.version) != interview.version - 1 -> {
                        ApplicationStoreResult.VersionConflict
                    }

                    !contacts.containsAll(interview.details.participants) -> {
                        ApplicationStoreResult.ContactNotFound
                    }

                    else -> {
                        interviews[interview.id] = interview
                        ApplicationStoreResult.Success(Unit)
                    }
                }
            }

            override fun findById(
                application: ApplicationId,
                id: InterviewId,
            ): ApplicationStoreResult<Interview> =
                interviews[id]?.takeIf { it.application == application }?.let { ApplicationStoreResult.Success(it) }
                    ?: ApplicationStoreResult.NotFound

            override fun pageByApplication(
                application: ApplicationId,
                request: PageRequest,
                direction: SortDirection,
            ): ApplicationStoreResult<Paged<Interview>> {
                val ascending =
                    interviews.values
                        .filter { it.application == application }
                        .sortedWith(compareBy({ it.details.time.startsAt }, { it.id.value.toString() }))
                val ordered = if (direction == SortDirection.ASCENDING) ascending else ascending.reversed()
                return ApplicationStoreResult.Success(Paged.slice(ordered, request))
            }

            override fun upcoming(
                from: Instant,
                limit: Int,
            ): ApplicationStoreResult<List<UpcomingInterview>> = error("Not used by these use cases")

            override fun delete(
                application: ApplicationId,
                id: InterviewId,
                proof: ConfirmationResult.Confirmed,
            ): ApplicationStoreResult<Unit> =
                when {
                    !proof.covers(Interview.DELETE_OPERATION, id.value.toString()) -> {
                        ApplicationStoreResult.NotConfirmed
                    }

                    interviews[id]?.application != application -> {
                        ApplicationStoreResult.NotFound
                    }

                    else -> {
                        interviews.remove(id)
                        ApplicationStoreResult.Success(Unit)
                    }
                }
        }

    /** Per application, the snapshots a freeze would pick; and when each one was frozen (only once). */
    val freezable = mutableMapOf<ApplicationId, List<SnapshotId>>()
    val frozenAt = mutableMapOf<SnapshotId, Instant>()
    var failingFreeze = false

    /** The stored description snapshots (#86), in the order they were added. */
    val descriptions = mutableListOf<DescriptionSnapshot>()

    val snapshotPort =
        object : DescriptionSnapshotRepositoryPort {
            override fun add(snapshot: DescriptionSnapshot): ApplicationStoreResult<Unit> {
                if (failingStore) return ApplicationStoreResult.StorageFailure("add snapshot")
                descriptions += snapshot
                return ApplicationStoreResult.Success(Unit)
            }

            override fun latest(source: SourceId): ApplicationStoreResult<DescriptionSnapshot?> =
                ApplicationStoreResult.Success(descriptions.lastOrNull { it.source == source })

            override fun listBySource(source: SourceId): ApplicationStoreResult<List<SnapshotSummary>> =
                ApplicationStoreResult.Success(descriptions.filter { it.source == source }.map { it.summary() })

            override fun findById(
                application: ApplicationId,
                id: SnapshotId,
            ): ApplicationStoreResult<DescriptionSnapshot> {
                val sources = applications[application]?.sources.orEmpty().map { it.id }
                return descriptions
                    .firstOrNull { it.id == id && it.source in sources }
                    ?.let { ApplicationStoreResult.Success(it) } ?: ApplicationStoreResult.NotFound
            }

            override fun freeze(
                application: ApplicationId,
                asOf: Instant,
            ): ApplicationStoreResult<List<SnapshotId>> {
                if (failingFreeze) return ApplicationStoreResult.StorageFailure("freeze")
                val newlyFrozen = freezable[application].orEmpty().filter { it !in frozenAt }
                newlyFrozen.forEach { frozenAt[it] = asOf }
                return ApplicationStoreResult.Success(newlyFrozen)
            }
        }

    val changelog =
        object : ChangelogPort {
            override fun append(entry: ChangelogEntry): ChangelogResult<Unit> {
                if (failingChangelog || entry.entity.type == failingChangelogFor) {
                    return ChangelogResult.StorageFailure("append")
                }
                entries += entry
                return ChangelogResult.Success(Unit)
            }

            override fun listByEntity(
                entity: EntityRef,
                limit: ChangelogLimit,
            ) = ChangelogResult.Success(entries.filter { it.entity == entity })

            override fun listRecent(limit: ChangelogLimit) = ChangelogResult.Success(entries.toList())
        }

    val eventPort =
        object : DomainEventPort {
            override fun publish(event: DomainEvent): Boolean {
                if (failingEvents) return false
                events += event
                return true
            }
        }

    val transactions =
        object : TransactionPort {
            override fun <T> inTransaction(
                commitIf: (T) -> Boolean,
                work: () -> T,
            ): T {
                val applicationsBefore = applications.toMap()
                val historyBefore = history.toList()
                val entriesBefore = entries.toList()
                val eventsBefore = events.toList()
                val frozenBefore = frozenAt.toMap()
                val descriptionsBefore = descriptions.toList()
                val interviewsBefore = interviews.toMap()
                val linkedTasksBefore = linkedTasks.toMap()
                val result = work()
                if (!commitIf(result)) {
                    applications.clear()
                    applications.putAll(applicationsBefore)
                    history.clear()
                    history.addAll(historyBefore)
                    entries.clear()
                    entries.addAll(entriesBefore)
                    events.clear()
                    events.addAll(eventsBefore)
                    frozenAt.clear()
                    frozenAt.putAll(frozenBefore)
                    descriptions.clear()
                    descriptions.addAll(descriptionsBefore)
                    interviews.clear()
                    interviews.putAll(interviewsBefore)
                    linkedTasks.clear()
                    linkedTasks.putAll(linkedTasksBefore)
                }
                return result
            }
        }

    val confirmation = ConfirmActionUseCase(TokenStore(), CLOCK, Duration.ofMinutes(5))

    /** A stored application of [ACME], created by the user, with its first history entry. */
    fun application(
        title: String = "Backend Engineer",
        version: Long = Application.INITIAL_VERSION,
        unread: Boolean = false,
    ): Application {
        val application =
            Application
                .create(ApplicationId(UUID.randomUUID()), ApplicationDetails(title, ACME), CREATED, unread)
                .copy(version = version)
        applications[application.id] = application
        history += StatusChange.initial(application, Actor.User)
        return application
    }

    /** A source of [application], found when it was created, stored with it. */
    fun source(application: Application): ApplicationSource {
        val source =
            ApplicationSource(SourceId(UUID.randomUUID()), application.id, SourceKind.MANUAL_CHAT, null, CREATED)
        applications[application.id] = application.copy(sources = application.sources + source)
        return source
    }

    private class TokenStore : ConfirmationStorePort {
        private val pending = mutableMapOf<String, PendingConfirmation>()

        override fun issue(
            pending: PendingConfirmation,
            now: Instant,
        ): ConfirmationToken {
            val token = "token-${this.pending.size + 1}-${System.nanoTime()}"
            this.pending[token] = pending
            return ConfirmationToken(token)
        }

        override fun redeem(token: ConfirmationToken): PendingConfirmation? = pending.remove(token.value)
    }

    companion object {
        val ACME = CompanyRef(UUID.fromString("00000000-0000-0000-0000-00000000000c"))
        val CREATED: Instant = Instant.parse("2026-09-01T08:00:00Z")

        /** Nanoseconds the use cases must cut to the microseconds `timestamptz` keeps. */
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-09-30T12:00:00.123456789Z"), ZoneOffset.UTC)
        val NOW: Instant = Instant.parse("2026-09-30T12:00:00.123456Z")
    }
}
