// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.application

import io.github.scriptibus.jofi.companies.application.port.CompanyRepositoryPort
import io.github.scriptibus.jofi.companies.application.port.spi.ApplicationCountsPort
import io.github.scriptibus.jofi.companies.domain.Company
import io.github.scriptibus.jofi.companies.domain.CompanyDetails
import io.github.scriptibus.jofi.companies.domain.CompanyId
import io.github.scriptibus.jofi.companies.domain.CompanyPage
import io.github.scriptibus.jofi.companies.domain.CompanySearch
import io.github.scriptibus.jofi.companies.domain.CompanyStoreResult
import io.github.scriptibus.jofi.companies.domain.ContactId
import io.github.scriptibus.jofi.shared.application.ConfirmActionUseCase
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.ConfirmationStorePort
import io.github.scriptibus.jofi.shared.application.port.DomainEventPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
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
 * An in-memory companies store behind every port the company use cases take, with a transaction that
 * restores all of it (companies, changelog, events) when the use case's result is not committed.
 */
class CompanyFixtures {
    val companies = linkedMapOf<CompanyId, Company>()
    val contacts = linkedMapOf<CompanyId, List<ContactId>>()
    val applicationCounts = mutableMapOf<UUID, Int>()

    /** Companies whose delete the store refuses, as `application_company_fk` does for one with applications. */
    val restrictedByApplications = mutableSetOf<CompanyId>()
    val entries = mutableListOf<ChangelogEntry>()
    val events = mutableListOf<DomainEvent>()
    var countsAvailable = true
    var failingChangelog = false
    var failingEvents = false

    /** A version another client stored between this use case's read and its write (the update race). */
    var concurrentVersion: Long? = null

    val companyPort =
        object : CompanyRepositoryPort {
            override fun add(company: Company): CompanyStoreResult<Unit> {
                companies[company.id] = company
                return CompanyStoreResult.Success(Unit)
            }

            override fun update(company: Company): CompanyStoreResult<Unit> {
                val stored = companies[company.id]
                return when {
                    stored == null -> CompanyStoreResult.NotFound
                    (concurrentVersion ?: stored.version) != company.version - 1 -> CompanyStoreResult.VersionConflict
                    else -> CompanyStoreResult.Success(Unit).also { companies[company.id] = company }
                }
            }

            override fun findById(id: CompanyId) =
                companies[id]?.let { CompanyStoreResult.Success(it) } ?: CompanyStoreResult.NotFound

            override fun search(search: CompanySearch): CompanyStoreResult<CompanyPage<Company>> {
                val found =
                    companies.values.filter { company ->
                        search.text.let { it == null || company.details.name.contains(it, ignoreCase = true) } &&
                            (search.preference == null || company.preference.kind == search.preference)
                    }
                val page = found.drop(search.page * search.size).take(search.size)
                return CompanyStoreResult.Success(CompanyPage(page, found.size.toLong()))
            }

            override fun findContactIds(id: CompanyId) = CompanyStoreResult.Success(contacts[id].orEmpty())

            override fun delete(
                id: CompanyId,
                proof: ConfirmationResult.Confirmed,
            ): CompanyStoreResult<Unit> =
                when {
                    !proof.covers(Company.DELETE_OPERATION, id.value.toString()) -> CompanyStoreResult.NotConfirmed
                    id in restrictedByApplications -> CompanyStoreResult.HasApplications
                    companies.remove(id) == null -> CompanyStoreResult.NotFound
                    else -> CompanyStoreResult.Success(Unit).also { contacts.remove(id) }
                }
        }

    val applicationPort =
        object : ApplicationCountsPort {
            override fun countByCompany(companies: Set<UUID>): ApplicationCountsPort.Counts =
                if (countsAvailable) {
                    ApplicationCountsPort.Counts.Counted(applicationCounts.filterKeys { it in companies })
                } else {
                    ApplicationCountsPort.Counts.Unavailable
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
                val companiesBefore = companies.toMap()
                val contactsBefore = contacts.toMap()
                val entriesBefore = entries.toList()
                val eventsBefore = events.toList()
                val result = work()
                if (!commitIf(result)) {
                    companies.clear()
                    companies.putAll(companiesBefore)
                    contacts.clear()
                    contacts.putAll(contactsBefore)
                    entries.clear()
                    entries.addAll(entriesBefore)
                    events.clear()
                    events.addAll(eventsBefore)
                }
                return result
            }
        }

    val confirmation = ConfirmActionUseCase(TokenStore(), CLOCK, Duration.ofMinutes(5))

    fun company(
        name: String = "ACME GmbH",
        version: Long = Company.INITIAL_VERSION,
    ): Company {
        val company =
            Company
                .create(CompanyId(UUID.randomUUID()), CompanyDetails(name), CREATED)
                .copy(version = version)
        companies[company.id] = company
        return company
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
        val CREATED: Instant = Instant.parse("2026-09-01T08:00:00Z")

        /** Nanoseconds the use cases must cut to the microseconds `timestamptz` keeps. */
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-09-30T12:00:00.123456789Z"), ZoneOffset.UTC)
        val NOW: Instant = Instant.parse("2026-09-30T12:00:00.123456Z")
    }
}
