// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.ApplicationFixtures.Companion.CLOCK
import io.github.scriptibus.jofi.applications.application.ApplicationFixtures.Companion.NOW
import io.github.scriptibus.jofi.applications.application.port.ApplicationActivityRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.ApplicationSettingsRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.api.FindGhostedCandidatesPort
import io.github.scriptibus.jofi.applications.domain.ApplicationField
import io.github.scriptibus.jofi.applications.domain.ApplicationProblem
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ApplicationSettings
import io.github.scriptibus.jofi.applications.domain.ApplicationSettingsInput
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.ApplicationViolation
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ChangelogEntry
import io.github.scriptibus.jofi.shared.domain.ChangelogLimit
import io.github.scriptibus.jofi.shared.domain.ChangelogResult
import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.github.scriptibus.jofi.shared.domain.FieldChange
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** Reading and changing the application settings, and the Ghosted candidates they decide (#85). */
class ApplicationSettingsUseCasesTest {
    private var stored: ApplicationSettings? = null
    private var failingStore = false
    private val entries = mutableListOf<ChangelogEntry>()
    private var failingChangelog = false
    private val cutoffs = mutableListOf<Instant>()
    private val statusesAsked = mutableListOf<Set<ApplicationStatus>>()
    private var failingActivity = false

    private val settings =
        object : ApplicationSettingsRepositoryPort {
            override fun find(): ApplicationStoreResult<ApplicationSettings> =
                if (failingStore) {
                    ApplicationStoreResult.StorageFailure("find settings")
                } else {
                    ApplicationStoreResult.Success(stored ?: ApplicationSettings.DEFAULT)
                }

            override fun update(settings: ApplicationSettings): ApplicationStoreResult<Unit> {
                val version = stored?.version ?: 0
                if (version != settings.version - 1) return ApplicationStoreResult.VersionConflict
                stored = settings
                return ApplicationStoreResult.Success(Unit)
            }
        }

    private val changelog =
        object : ChangelogPort {
            override fun append(entry: ChangelogEntry): ChangelogResult<Unit> {
                if (failingChangelog) return ChangelogResult.StorageFailure("append")
                entries += entry
                return ChangelogResult.Success(Unit)
            }

            override fun listByEntity(
                entity: EntityRef,
                limit: ChangelogLimit,
            ) = ChangelogResult.Success(entries.toList())

            override fun listRecent(limit: ChangelogLimit) = ChangelogResult.Success(entries.toList())
        }

    private val transactions =
        object : TransactionPort {
            override fun <T> inTransaction(
                commitIf: (T) -> Boolean,
                work: () -> T,
            ): T {
                val before = stored
                val result = work()
                if (!commitIf(result)) stored = before
                return result
            }
        }

    private val activity =
        object : ApplicationActivityRepositoryPort {
            override fun silentSince(
                cutoff: Instant,
                statuses: Set<ApplicationStatus>,
            ): ApplicationStoreResult<List<FindGhostedCandidatesPort.Candidate>> {
                cutoffs += cutoff
                statusesAsked += statuses
                return if (failingActivity) {
                    ApplicationStoreResult.StorageFailure("silentSince")
                } else {
                    ApplicationStoreResult.Success(listOf(CANDIDATE))
                }
            }
        }

    private val get = GetApplicationSettingsUseCase(settings)
    private val update = UpdateApplicationSettingsUseCase(settings, changelog, transactions, CLOCK)
    private val candidates = FindGhostedCandidatesUseCase(settings, activity)

    @Test
    fun `the defaults apply until the user changes them`() {
        get.execute() shouldBe ApplicationResult.Success(ApplicationSettings.DEFAULT)
    }

    @Test
    fun `a change stores version 1 and records the changed values with the actor`() {
        val changed = update.execute(ApplicationSettingsInput(10, 14), 0, Actor.User)

        val expected = ApplicationSettings(ApplicationSettings.Values(10, 14), 1, NOW)
        changed shouldBe ApplicationResult.Success(expected)
        get.execute() shouldBe ApplicationResult.Success(expected)
        val entry = entries.single()
        entry.entity shouldBe ApplicationSettings.ENTITY_REF
        entry.actor shouldBe Actor.User
        entry.occurredAt shouldBe NOW
        entry.change.description shouldBe "Changed application settings"
        entry.change.fieldChanges shouldContainExactly listOf(FieldChange("ghostedAfterWeeks", "14", "10"))

        update.execute(ApplicationSettingsInput(10, 30), 1, Actor.User)
        entries.last().change.fieldChanges shouldContainExactly listOf(FieldChange("followUpAfterDays", "14", "30"))
    }

    @Test
    fun `unchanged values store and record nothing, but a stale version is a conflict first`() {
        update.execute(ApplicationSettingsInput(14, 14), 0, Actor.User) shouldBe
            ApplicationResult.Success(ApplicationSettings.DEFAULT)
        stored shouldBe null
        entries.shouldBeEmpty()

        update.execute(ApplicationSettingsInput(14, 14), 3, Actor.User) shouldBe ApplicationResult.VersionConflict
        update.execute(ApplicationSettingsInput(0, 0), 3, Actor.User) shouldBe ApplicationResult.VersionConflict
    }

    @Test
    fun `values out of their bounds are invalid and store nothing`() {
        update.execute(ApplicationSettingsInput(53, 0), 0, Actor.User) shouldBe
            ApplicationResult.Invalid(
                listOf(
                    ApplicationViolation(ApplicationField.GHOSTED_AFTER_WEEKS, ApplicationProblem.OUT_OF_RANGE),
                    ApplicationViolation(ApplicationField.FOLLOW_UP_AFTER_DAYS, ApplicationProblem.OUT_OF_RANGE),
                ),
            )
        stored shouldBe null
    }

    @Test
    fun `a failing changelog or store changes nothing`() {
        failingChangelog = true
        update.execute(ApplicationSettingsInput(10, 14), 0, Actor.User) shouldBe
            ApplicationResult.StorageFailure("changelog")
        stored shouldBe null

        failingChangelog = false
        failingStore = true
        update.execute(ApplicationSettingsInput(10, 14), 0, Actor.User) shouldBe
            ApplicationResult.StorageFailure("find settings")
        get.execute() shouldBe ApplicationResult.StorageFailure("find settings")
    }

    @Test
    fun `candidates are silent for the configured number of weeks of seven days`() {
        candidates.execute(NOW) shouldBe FindGhostedCandidatesPort.Candidates.Found(listOf(CANDIDATE))
        update.execute(ApplicationSettingsInput(1, 14), 0, Actor.User)
        candidates.execute(NOW)

        cutoffs shouldContainExactly listOf(NOW.minusSeconds(14 * WEEK), NOW.minusSeconds(WEEK))
        statusesAsked.distinct() shouldContainExactly
            listOf(setOf(ApplicationStatus.APPLIED, ApplicationStatus.INTERVIEWING))
    }

    @Test
    fun `candidates are unavailable when the settings or the activity cannot be read`() {
        failingActivity = true
        candidates.execute(NOW) shouldBe FindGhostedCandidatesPort.Candidates.Unavailable
        failingStore = true
        candidates.execute(NOW) shouldBe FindGhostedCandidatesPort.Candidates.Unavailable
        cutoffs shouldHaveSize 1
    }

    private companion object {
        const val WEEK = 7L * 24 * 60 * 60
        val CANDIDATE =
            FindGhostedCandidatesPort.Candidate(
                UUID.fromString("00000000-0000-0000-0000-0000000000b1"),
                "Backend Engineer",
                Instant.parse("2026-05-01T00:00:00Z"),
            )
    }
}
