// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.persistence

import io.github.scriptibus.jofi.applications.domain.ApplicationSettings
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.shared.adapter.persistence.PostgresTestDatabase
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION_SETTINGS
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.time.Instant

/** The optional single settings row (ADR-0050) against the real schema. */
class ApplicationSettingsRepositoryTest {
    private val dsl = PostgresTestDatabase.migratedFromZero()
    private val repository = ApplicationSettingsRepository(dsl)
    private val at = Instant.parse("2026-09-30T08:00:00.123456Z")

    @Test
    fun `no row reads as the defaults, and the first change inserts it`() {
        repository.find() shouldBe ApplicationStoreResult.Success(ApplicationSettings.DEFAULT)
        dsl.fetchCount(APPLICATION_SETTINGS) shouldBe 0

        val first = ApplicationSettings.DEFAULT.edit(ApplicationSettings.Values(10, 30), at)
        repository.update(first) shouldBe ApplicationStoreResult.Success(Unit)

        repository.find() shouldBe ApplicationStoreResult.Success(first)
        dsl.fetchCount(APPLICATION_SETTINGS) shouldBe 1
    }

    @Test
    fun `later changes update the one row on top of the version they were based on`() {
        val first = ApplicationSettings.DEFAULT.edit(ApplicationSettings.Values(10, 30), at)
        repository.update(first)
        val second = first.edit(ApplicationSettings.Values(52, 90), at.plusSeconds(60))

        repository.update(second) shouldBe ApplicationStoreResult.Success(Unit)
        repository.find() shouldBe ApplicationStoreResult.Success(second)
        dsl.fetchCount(APPLICATION_SETTINGS) shouldBe 1
    }

    @Test
    fun `a stale version, also a second first change, is a conflict and changes nothing`() {
        val first = ApplicationSettings.DEFAULT.edit(ApplicationSettings.Values(10, 30), at)
        repository.update(first)

        val racingFirst = ApplicationSettings.DEFAULT.edit(ApplicationSettings.Values(1, 1), at)
        repository.update(racingFirst) shouldBe ApplicationStoreResult.VersionConflict
        val skipping = ApplicationSettings(ApplicationSettings.Values(2, 2), 3, at)
        repository.update(skipping) shouldBe ApplicationStoreResult.VersionConflict

        repository.find() shouldBe ApplicationStoreResult.Success(first)
    }

    @Test
    fun `a database it cannot reach is a storage failure, not an exception`() {
        val broken = ApplicationSettingsRepository(DSL.using(SQLDialect.POSTGRES))

        broken.find() shouldBe ApplicationStoreResult.StorageFailure("find settings")
        broken.update(ApplicationSettings.DEFAULT.edit(ApplicationSettings.Values(1, 1), at)) shouldBe
            ApplicationStoreResult.StorageFailure("update settings")
    }
}
