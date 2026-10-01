// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.application

import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.ChangelogEntry
import io.github.scriptibus.jofi.shared.domain.ChangelogLimit
import io.github.scriptibus.jofi.shared.domain.ChangelogResult
import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.tasks.application.port.CountdownRepositoryPort
import io.github.scriptibus.jofi.tasks.domain.Countdown
import io.github.scriptibus.jofi.tasks.domain.CountdownDetails
import io.github.scriptibus.jofi.tasks.domain.CountdownId
import io.github.scriptibus.jofi.tasks.domain.TaskStoreResult
import java.time.LocalDate
import java.util.UUID

/**
 * An in-memory countdown store behind every port the countdown use cases take, with a transaction that restores the
 * countdowns and the changelog when the result is not committed. Like the database, it stores only on top of the
 * version a change was based on. The clock and the confirmation gate are [TaskFixtures]'.
 */
class CountdownFixtures {
    private val tasks = TaskFixtures()
    val countdowns = linkedMapOf<CountdownId, Countdown>()
    val entries = mutableListOf<ChangelogEntry>()
    var failingChangelog = false
    var failingStore = false
    val confirmation = tasks.confirmation

    val repository =
        object : CountdownRepositoryPort {
            override fun add(countdown: Countdown): TaskStoreResult<Unit> =
                if (failingStore) {
                    TaskStoreResult.StorageFailure("add")
                } else {
                    TaskStoreResult.Success(Unit).also { countdowns[countdown.id] = countdown }
                }

            override fun update(countdown: Countdown): TaskStoreResult<Unit> {
                val stored = countdowns[countdown.id]
                return when {
                    stored == null -> TaskStoreResult.NotFound
                    stored.version != countdown.version - 1 -> TaskStoreResult.VersionConflict
                    else -> TaskStoreResult.Success(Unit).also { countdowns[countdown.id] = countdown }
                }
            }

            override fun findById(id: CountdownId): TaskStoreResult<Countdown> =
                countdowns[id]?.let { TaskStoreResult.Success(it) } ?: TaskStoreResult.NotFound

            override fun list(): TaskStoreResult<List<Countdown>> =
                if (failingStore) {
                    TaskStoreResult.StorageFailure("list")
                } else {
                    TaskStoreResult.Success(countdowns.values.sortedBy { it.details.targetDate })
                }

            override fun delete(
                id: CountdownId,
                proof: ConfirmationResult.Confirmed,
            ): TaskStoreResult<Unit> =
                when {
                    !proof.covers(Countdown.DELETE_OPERATION, id.value.toString()) -> TaskStoreResult.NotConfirmed
                    countdowns.remove(id) == null -> TaskStoreResult.NotFound
                    else -> TaskStoreResult.Success(Unit)
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

    val transactions =
        object : TransactionPort {
            override fun <T> inTransaction(
                commitIf: (T) -> Boolean,
                work: () -> T,
            ): T {
                val countdownsBefore = countdowns.toMap()
                val entriesBefore = entries.toList()
                val result = work()
                if (!commitIf(result)) {
                    countdowns.clear()
                    countdowns.putAll(countdownsBefore)
                    entries.clear()
                    entries.addAll(entriesBefore)
                }
                return result
            }
        }

    /** A stored countdown, created before [TaskFixtures.NOW]. */
    fun countdown(
        title: String = "Notice ends",
        target: String = "2026-12-31",
    ): Countdown {
        val countdown =
            Countdown.create(
                CountdownId(UUID.randomUUID()),
                CountdownDetails(title, LocalDate.parse(target)),
                TaskFixtures.CREATED,
            )
        countdowns[countdown.id] = countdown
        return countdown
    }
}
