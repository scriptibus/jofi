// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.persistence

import io.github.scriptibus.jofi.setup.adapter.persistence.ConfirmedProofs
import io.github.scriptibus.jofi.shared.adapter.persistence.PostgresTestDatabase
import io.github.scriptibus.jofi.tasks.domain.Countdown
import io.github.scriptibus.jofi.tasks.domain.CountdownDetails
import io.github.scriptibus.jofi.tasks.domain.CountdownId
import io.github.scriptibus.jofi.tasks.domain.TaskStoreResult
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Custom countdowns (#112) round-trip through `countdown` on a real PostgreSQL. */
class CountdownRepositoryTest {
    private val dsl = PostgresTestDatabase.migratedFromZero()
    private val repository = CountdownRepository(dsl)
    private val at = Instant.parse("2026-09-30T08:00:00.123456Z")

    private fun countdown(
        title: String,
        target: String,
        id: UUID = UUID.randomUUID(),
    ) = Countdown.create(CountdownId(id), CountdownDetails(title, LocalDate.parse(target)), at)

    @Test
    fun `a countdown is stored and read back exactly`() {
        val notice = countdown("Notice ends", "2026-12-31")

        repository.add(notice) shouldBe TaskStoreResult.Success(Unit)

        repository.findById(notice.id) shouldBe TaskStoreResult.Success(notice)
        repository.findById(CountdownId(UUID.randomUUID())) shouldBe TaskStoreResult.NotFound
    }

    @Test
    fun `the list comes soonest target first, then by id, past ones included`() {
        val second = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val first = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val later = countdown("Later", "2027-03-01")
        val sameDayB = countdown("B", "2026-12-31", second)
        val sameDayA = countdown("A", "2026-12-31", first)
        val past = countdown("Past", "2025-01-01")
        listOf(later, sameDayB, sameDayA, past).forEach { repository.add(it) }

        repository.list() shouldBe TaskStoreResult.Success(listOf(past, sameDayA, sameDayB, later))
    }

    @Test
    fun `an update stores only on top of the version it was based on`() {
        val notice = countdown("Notice ends", "2026-12-31")
        repository.add(notice)
        val edited = notice.edit(CountdownDetails("Probation ends", LocalDate.parse("2027-01-31")), at.plusSeconds(60))

        repository.update(edited) shouldBe TaskStoreResult.Success(Unit)
        repository.findById(notice.id) shouldBe TaskStoreResult.Success(edited)
        repository.update(edited) shouldBe TaskStoreResult.VersionConflict
        repository.update(countdown("Gone", "2026-12-31").copy(version = 1)) shouldBe TaskStoreResult.NotFound
    }

    @Test
    fun `deleting needs the proof for exactly this countdown`() {
        val notice = countdown("Notice ends", "2026-12-31")
        repository.add(notice)

        repository.delete(notice.id, proofFor(UUID.randomUUID())) shouldBe TaskStoreResult.NotConfirmed
        repository.delete(notice.id, ConfirmedProofs.of("tasks.delete", notice.id.value.toString())) shouldBe
            TaskStoreResult.NotConfirmed
        repository.findById(notice.id) shouldBe TaskStoreResult.Success(notice)

        repository.delete(notice.id, proofFor(notice.id.value)) shouldBe TaskStoreResult.Success(Unit)
        repository.findById(notice.id) shouldBe TaskStoreResult.NotFound
        repository.delete(notice.id, proofFor(notice.id.value)) shouldBe TaskStoreResult.NotFound
    }

    @Test
    fun `a database it cannot reach is a storage failure, not an exception`() {
        val broken = CountdownRepository(DSL.using(SQLDialect.POSTGRES))

        broken.list() shouldBe TaskStoreResult.StorageFailure("list")
        broken.add(countdown("Notice ends", "2026-12-31")) shouldBe TaskStoreResult.StorageFailure("add")
    }

    private fun proofFor(id: UUID) = ConfirmedProofs.of(Countdown.DELETE_OPERATION, id.toString())
}
