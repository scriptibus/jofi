// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.ApplicationFixtures.Companion.CLOCK
import io.github.scriptibus.jofi.applications.application.ApplicationFixtures.Companion.NOW
import io.github.scriptibus.jofi.applications.application.port.SavedViewRepositoryPort
import io.github.scriptibus.jofi.applications.domain.ApplicationProblem
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ApplicationSearchInput
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.SavedView
import io.github.scriptibus.jofi.applications.domain.SavedViewField
import io.github.scriptibus.jofi.applications.domain.SavedViewFilter
import io.github.scriptibus.jofi.applications.domain.SavedViewId
import io.github.scriptibus.jofi.applications.domain.SavedViewInput
import io.github.scriptibus.jofi.applications.domain.SavedViewViolation
import io.github.scriptibus.jofi.applications.domain.SearchField
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.FieldChange
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationEffect
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequester
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/** Saving, reading, renaming and deleting saved views (#99, ADR-0050), each change recorded with its actor. */
class SavedViewUseCasesTest {
    private val fixtures = ApplicationFixtures()
    private val views = linkedMapOf<SavedViewId, SavedView>()
    private var failingStore = false

    /** Answers `saved_view_name_unique` as the database does: exactly equal names only. */
    private val repository =
        object : SavedViewRepositoryPort {
            override fun add(view: SavedView): ApplicationStoreResult<Unit> =
                when {
                    failingStore -> ApplicationStoreResult.StorageFailure("add view")
                    views.values.any { it.details.name == view.details.name } -> ApplicationStoreResult.ViewNameTaken
                    else -> ApplicationStoreResult.Success(Unit).also { views[view.id] = view }
                }

            override fun update(view: SavedView): ApplicationStoreResult<Unit> =
                when (views[view.id]?.version) {
                    null -> ApplicationStoreResult.NotFound
                    view.version - 1 -> ApplicationStoreResult.Success(Unit).also { views[view.id] = view }
                    else -> ApplicationStoreResult.VersionConflict
                }

            override fun findById(id: SavedViewId): ApplicationStoreResult<SavedView> =
                views[id]?.let { ApplicationStoreResult.Success(it) } ?: ApplicationStoreResult.NotFound

            override fun list(): ApplicationStoreResult<List<SavedView>> =
                if (failingStore) {
                    ApplicationStoreResult.StorageFailure("list views")
                } else {
                    ApplicationStoreResult.Success(views.values.sortedBy { it.details.name })
                }

            override fun delete(
                id: SavedViewId,
                proof: ConfirmationResult.Confirmed,
            ): ApplicationStoreResult<Unit> {
                if (!proof.covers(SavedView.DELETE_OPERATION, id.value.toString())) {
                    return ApplicationStoreResult.NotConfirmed
                }
                return if (views.remove(id) ==
                    null
                ) {
                    ApplicationStoreResult.NotFound
                } else {
                    ApplicationStoreResult.Success(Unit)
                }
            }
        }

    private val transactions =
        object : TransactionPort {
            override fun <T> inTransaction(
                commitIf: (T) -> Boolean,
                work: () -> T,
            ): T {
                val viewsBefore = views.toMap()
                val entriesBefore = fixtures.entries.toList()
                val result = work()
                if (!commitIf(result)) {
                    views.clear()
                    views.putAll(viewsBefore)
                    fixtures.entries.clear()
                    fixtures.entries.addAll(entriesBefore)
                }
                return result
            }
        }

    private val create = CreateSavedViewUseCase(repository, fixtures.changelog, transactions, CLOCK)
    private val update = UpdateSavedViewUseCase(repository, fixtures.changelog, transactions, CLOCK)
    private val get = GetSavedViewUseCase(repository)
    private val list = ListSavedViewsUseCase(repository)
    private val delete =
        DeleteSavedViewUseCase(repository, fixtures.confirmation, fixtures.changelog, transactions, CLOCK)
    private val user = ConfirmationRequester(Actor.User, "session-1")

    private val offers = ApplicationSearchInput(text = "Kotlin", statuses = setOf(ApplicationStatus.OFFER))

    private fun created(
        name: String = "Offers",
        filter: ApplicationSearchInput = offers,
    ): SavedView =
        create
            .execute(
                SavedViewInput(name, filter),
                Actor.User,
            ).shouldBeInstanceOf<ApplicationResult.Success<SavedView>>()
            .value

    @Test
    fun `saving stores the normalized view at version 0 and records its name, never the filter`() {
        val saved = created(" Offers ")

        saved.details.name shouldBe "Offers"
        saved.details.filter shouldBe SavedViewFilter(text = "Kotlin", statuses = setOf(ApplicationStatus.OFFER))
        saved.version shouldBe 0
        saved.createdAt shouldBe NOW
        get.execute(saved.id) shouldBe ApplicationResult.Success(saved)
        list.execute() shouldBe ApplicationResult.Success(listOf(saved))
        val entry = fixtures.entries.single()
        entry.entity shouldBe saved.id.toEntityRef()
        entry.actor shouldBe Actor.User
        entry.occurredAt shouldBe NOW
        entry.change.description shouldBe "Created saved view"
        entry.change.fieldChanges shouldContainExactly listOf(FieldChange("name", null, "Offers"))
        entry.toString().contains("Kotlin") shouldBe false
    }

    @Test
    fun `a name another view has, ignoring case, is taken, bad input names every violation`() {
        created("Offers")

        create.execute(SavedViewInput("OFFERS", ApplicationSearchInput()), Actor.Ai) shouldBe taken()
        create.execute(SavedViewInput(" ", ApplicationSearchInput(wantMin = BigDecimal("6"))), Actor.User) shouldBe
            ApplicationResult.InvalidView(
                listOf(
                    SavedViewViolation(SavedViewField.Name, ApplicationProblem.REQUIRED),
                    SavedViewViolation(SavedViewField.Filter(SearchField.WANT_MIN), ApplicationProblem.OUT_OF_RANGE),
                ),
            )
        views.size shouldBe 1
        fixtures.entries.size shouldBe 1
    }

    @Test
    fun `a race past the name check is taken too, found by the constraint`() {
        val racing =
            object : SavedViewRepositoryPort by repository {
                override fun list(): ApplicationStoreResult<List<SavedView>> =
                    ApplicationStoreResult.Success(emptyList())
            }
        created("Offers")

        CreateSavedViewUseCase(racing, fixtures.changelog, transactions, CLOCK)
            .execute(SavedViewInput("Offers", offers), Actor.User) shouldBe taken()
        views.size shouldBe 1
    }

    @Test
    fun `a rename keeps the filter, bumps the version and records only the name`() {
        val saved = created("Offers")

        val renamed = update.execute(saved.id, SavedViewInput("Good offers", offers), 0, Actor.Ai)

        val expected = saved.copy(details = saved.details.copy(name = "Good offers"), version = 1, updatedAt = NOW)
        renamed shouldBe ApplicationResult.Success(expected)
        views[saved.id] shouldBe expected
        val entry = fixtures.entries.last()
        entry.actor shouldBe Actor.Ai
        entry.change.description shouldBe "Edited saved view"
        entry.change.fieldChanges shouldContainExactly listOf(FieldChange("name", "Offers", "Good offers"))
    }

    @Test
    fun `a changed filter is recorded as changed, without its values, a case-only rename of itself is allowed`() {
        val saved = created("Offers")

        val changed =
            update.execute(
                saved.id,
                SavedViewInput("offers", ApplicationSearchInput(text = "Java")),
                0,
                Actor.User,
            )

        changed
            .shouldBeInstanceOf<ApplicationResult.Success<SavedView>>()
            .value.details.filter shouldBe
            SavedViewFilter(text = "Java")
        val entry = fixtures.entries.last()
        entry.change.description shouldBe "Edited saved view; also changed: filter"
        entry.change.fieldChanges shouldContainExactly listOf(FieldChange("name", "Offers", "offers"))
        entry.toString().contains("Java") shouldBe false
    }

    @Test
    fun `the version is checked first, unchanged details store and record nothing`() {
        val saved = created("Offers")
        created("Active")

        update.execute(saved.id, SavedViewInput("", offers), 3, Actor.User) shouldBe ApplicationResult.VersionConflict
        update.execute(saved.id, SavedViewInput("active", offers), 0, Actor.User) shouldBe taken()
        update.execute(saved.id, SavedViewInput("Offers", offers), 0, Actor.User) shouldBe
            ApplicationResult.Success(saved)
        update.execute(SavedViewId(UUID.randomUUID()), SavedViewInput("x", offers), 0, Actor.User) shouldBe
            ApplicationResult.SavedViewNotFound
        views[saved.id] shouldBe saved
        fixtures.entries.size shouldBe 2
    }

    @Test
    fun `an adjusted view sent back unchanged is stored as it is now, the filter recorded as changed`() {
        val saved = created("Offers")
        views[saved.id] = saved.copy(adjusted = true)

        val stored = update.execute(saved.id, SavedViewInput("Offers", offers), 0, Actor.User)

        stored shouldBe ApplicationResult.Success(saved.copy(version = 1, updatedAt = NOW, adjusted = false))
        fixtures.entries
            .last()
            .change.description shouldBe "Edited saved view; also changed: filter"
        fixtures.entries
            .last()
            .change.fieldChanges
            .shouldBeEmpty()
    }

    @Test
    fun `deleting asks first with the view's name, then deletes and records it`() {
        val saved = created("Offers")

        val first = delete.execute(saved.id, user, null).shouldBeInstanceOf<ApplicationResult.Unconfirmed>()
        val required = first.outcome.shouldBeInstanceOf<ConfirmationResult.Required>()
        required.action.operation shouldBe SavedView.DELETE_OPERATION
        required.action.targets shouldBe listOf(saved.id.value.toString())
        required.action.effect shouldBe ConfirmationEffect("saved_view", "Offers")
        views.size shouldBe 1

        delete.execute(saved.id, user, required.token) shouldBe ApplicationResult.Success(Unit)

        views.size shouldBe 0
        val entry = fixtures.entries.last()
        entry.change.description shouldBe "Deleted saved view"
        entry.change.fieldChanges shouldContainExactly listOf(FieldChange("name", "Offers", null))
        entry.actor shouldBe Actor.User
        get.execute(saved.id) shouldBe ApplicationResult.SavedViewNotFound
    }

    @Test
    fun `a rename between the steps voids the token, an unknown view is not found`() {
        val saved = created("Offers")
        val first = delete.execute(saved.id, user, null).shouldBeInstanceOf<ApplicationResult.Unconfirmed>()
        val token = first.outcome.shouldBeInstanceOf<ConfirmationResult.Required>().token
        update.execute(saved.id, SavedViewInput("Renamed", offers), 0, Actor.User)

        delete
            .execute(saved.id, user, token)
            .shouldBeInstanceOf<ApplicationResult.Unconfirmed>()
            .outcome
            .shouldBeInstanceOf<ConfirmationResult.Rejected>()
        views.size shouldBe 1
        delete.execute(SavedViewId(UUID.randomUUID()), user, ConfirmationToken("forged")) shouldBe
            ApplicationResult.SavedViewNotFound
    }

    @Test
    fun `a failing changelog or store changes nothing`() {
        fixtures.failingChangelog = true
        create.execute(SavedViewInput("Offers", offers), Actor.User) shouldBe
            ApplicationResult.StorageFailure("changelog")
        views.size shouldBe 0

        fixtures.failingChangelog = false
        val saved = created("Offers")
        fixtures.failingChangelog = true
        update.execute(saved.id, SavedViewInput("Renamed", offers), 0, Actor.User) shouldBe
            ApplicationResult.StorageFailure("changelog")
        views[saved.id] shouldBe saved
        val token =
            delete
                .execute(saved.id, user, null)
                .shouldBeInstanceOf<ApplicationResult.Unconfirmed>()
                .outcome
                .shouldBeInstanceOf<ConfirmationResult.Required>()
                .token
        delete.execute(saved.id, user, token) shouldBe ApplicationResult.StorageFailure("changelog")
        views.size shouldBe 1

        failingStore = true
        list.execute() shouldBe ApplicationResult.StorageFailure("list views")
        create.execute(SavedViewInput("Other", offers), Actor.User) shouldBe
            ApplicationResult.StorageFailure("list views")
    }

    private fun taken(): ApplicationResult<SavedView> =
        ApplicationResult.InvalidView(listOf(SavedViewViolation(SavedViewField.Name, ApplicationProblem.TAKEN)))
}
