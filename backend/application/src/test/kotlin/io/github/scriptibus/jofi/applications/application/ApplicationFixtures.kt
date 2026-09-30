// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.ApplicationRepositoryPort
import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationDetails
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationPage
import io.github.scriptibus.jofi.applications.domain.ApplicationSearch
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.CompanyRef
import io.github.scriptibus.jofi.applications.domain.StatusChange
import io.github.scriptibus.jofi.shared.application.ConfirmActionUseCase
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
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import io.github.scriptibus.jofi.shared.domain.confirmation.PendingConfirmation
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * An in-memory application store behind every port the application use cases take, with a transaction
 * that restores all of it (applications, history, changelog, events) when the result is not committed.
 * Like the database, it refuses applications of unknown companies (`application_company_fk`).
 */
class ApplicationFixtures {
    val applications = linkedMapOf<ApplicationId, Application>()
    val history = mutableListOf<StatusChange>()
    val companies = mutableSetOf(ACME)
    val entries = mutableListOf<ChangelogEntry>()
    val events = mutableListOf<DomainEvent>()
    var failingChangelog = false
    var failingEvents = false
    var failingStore = false

    /** A version another client stored between this use case's read and its write (the update race). */
    var concurrentVersion: Long? = null

    val repository =
        object : ApplicationRepositoryPort {
            override fun add(
                application: Application,
                initial: StatusChange,
            ): ApplicationStoreResult<Unit> {
                if (failingStore) return ApplicationStoreResult.StorageFailure("add")
                if (application.details.company !in companies) return ApplicationStoreResult.CompanyNotFound
                applications[application.id] = application
                history += initial
                return ApplicationStoreResult.Success(Unit)
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

            override fun replaceContacts(application: Application): ApplicationStoreResult<Unit> =
                error("Not used by these use cases")

            override fun changeStatus(
                application: Application,
                change: StatusChange,
            ): ApplicationStoreResult<Unit> = error("Not used by these use cases")

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
                        ApplicationStoreResult.Success(Unit)
                    }
                }
        }

    val changelog =
        object : ChangelogPort {
            override fun append(entry: ChangelogEntry): ChangelogResult<Unit> {
                if (failingChangelog) return ChangelogResult.StorageFailure("append")
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
