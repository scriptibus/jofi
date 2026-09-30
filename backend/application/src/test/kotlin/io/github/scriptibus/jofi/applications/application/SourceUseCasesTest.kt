// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.ApplicationFixtures.Companion.ACME
import io.github.scriptibus.jofi.applications.application.ApplicationFixtures.Companion.CLOCK
import io.github.scriptibus.jofi.applications.application.ApplicationFixtures.Companion.CREATED
import io.github.scriptibus.jofi.applications.application.ApplicationFixtures.Companion.NOW
import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationField
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationInput
import io.github.scriptibus.jofi.applications.domain.ApplicationProblem
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ApplicationSource
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.ApplicationViolation
import io.github.scriptibus.jofi.applications.domain.CompanyRef
import io.github.scriptibus.jofi.applications.domain.SourceId
import io.github.scriptibus.jofi.applications.domain.SourceInput
import io.github.scriptibus.jofi.applications.domain.SourceKind
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.FieldChange
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

class SourceUseCasesTest {
    private val fixtures = PostingImportFixtures()
    private val base = fixtures.base
    private val add =
        AddApplicationSourceUseCase(base.repository, fixtures.sources, base.changelog, fixtures.transactions, CLOCK)
    private val scanner = Actor.Scanner("arbeitsagentur")

    @Test
    fun `adding a source stores it with its first description and a changelog entry for each`() {
        val application = base.application()

        val source =
            add
                .execute(
                    application.id,
                    SourceInput(SourceKind.URL, "https://jobs.example/1?ref=me", null, "Kotlin, Berlin"),
                    scanner,
                ).shouldBeInstanceOf<ApplicationResult.Success<ApplicationSource>>()
                .value

        source.kind shouldBe SourceKind.URL
        source.discoveredAt shouldBe NOW
        base.applications.getValue(application.id).sources shouldContainExactly listOf(source)
        val snapshot = base.descriptions.single()
        snapshot.source shouldBe source.id
        snapshot.text.value shouldBe "Kotlin, Berlin"
        snapshot.frozen shouldBe false
        base.entries.map { it.entity to it.actor } shouldContainExactly
            listOf(source.id.toEntityRef() to scanner, snapshot.id.toEntityRef() to scanner)
        base.entries
            .first()
            .change.fieldChanges shouldContainExactly
            listOf(FieldChange("application", null, application.id.value.toString()), FieldChange("kind", null, "URL"))
        base.entries.joinToString() shouldNotContain "jobs.example"
        base.entries.joinToString() shouldNotContain "Berlin"
    }

    @Test
    fun `a source found after applying is frozen at once, one without a text stores no snapshot`() {
        val applied = base.application().copy(status = ApplicationStatus.APPLIED)
        base.applications[applied.id] = applied

        add.execute(applied.id, SourceInput(SourceKind.MANUAL_CHAT, description = "Text"), Actor.User)
        add.execute(applied.id, SourceInput(SourceKind.SCANNER), Actor.User)

        base.descriptions.single().frozenAt shouldBe NOW
        base.applications
            .getValue(applied.id)
            .sources
            .map { it.kind } shouldContainExactly
            listOf(SourceKind.MANUAL_CHAT, SourceKind.SCANNER)
    }

    @Test
    fun `invalid input, an unknown application and a full application store nothing`() {
        val application = base.application()
        add.execute(application.id, SourceInput(SourceKind.URL), Actor.User) shouldBe
            ApplicationResult.Invalid(
                listOf(ApplicationViolation(ApplicationField.SOURCE_URL, ApplicationProblem.REQUIRED)),
            )
        add.execute(ApplicationId(UUID.randomUUID()), SourceInput(SourceKind.SCANNER), Actor.User) shouldBe
            ApplicationResult.NotFound
        val full =
            (1..Application.MAX_SOURCES).fold(application) { current, _ ->
                current.copy(
                    sources =
                        current.sources +
                            ApplicationSource(
                                SourceId(UUID.randomUUID()),
                                current.id,
                                SourceKind.SCANNER,
                                null,
                                CREATED,
                            ),
                )
            }
        base.applications[full.id] = full
        add.execute(full.id, SourceInput(SourceKind.SCANNER), Actor.User) shouldBe
            ApplicationResult.Invalid(
                listOf(ApplicationViolation(ApplicationField.SOURCES, ApplicationProblem.TOO_MANY)),
            )

        base.entries.shouldBeEmpty()
        base.descriptions.shouldBeEmpty()
    }

    @Test
    fun `a failed changelog rolls the source back`() {
        val application = base.application()
        base.failingChangelog = true

        add
            .execute(application.id, SourceInput(SourceKind.SCANNER, description = "Text"), Actor.User)
            .shouldBeInstanceOf<ApplicationResult.StorageFailure>()

        base.applications
            .getValue(application.id)
            .sources
            .shouldBeEmpty()
        base.descriptions.shouldBeEmpty()
    }

    @Test
    fun `a discovered application is created unread with its source and discovery snapshot, or not at all`() {
        val created =
            fixtures.discovered
                .execute(
                    ApplicationInput(" Data Engineer ", ACME),
                    SourceInput(SourceKind.SCANNER, description = "Posting"),
                    scanner,
                ).shouldBeInstanceOf<ApplicationResult.Success<Application>>()
                .value

        created.status shouldBe ApplicationStatus.DISCOVERED
        created.unread shouldBe true
        created.details.title shouldBe "Data Engineer"
        base.applications.getValue(created.id) shouldBe created
        base.history.single().actor shouldBe scanner
        base.entries.map { it.entity.type } shouldContainExactly
            listOf(ApplicationId.ENTITY_TYPE, SourceId.ENTITY_TYPE, "description_snapshot")

        fixtures.discovered.execute(
            ApplicationInput("Other", CompanyRef(UUID.randomUUID())),
            SourceInput(SourceKind.SCANNER),
            scanner,
        ) shouldBe
            ApplicationResult.Invalid(
                listOf(ApplicationViolation(ApplicationField.COMPANY, ApplicationProblem.NOT_FOUND)),
            )
        fixtures.discovered
            .execute(ApplicationInput("", ACME), SourceInput(SourceKind.SCANNER), scanner)
            .shouldBeInstanceOf<ApplicationResult.Invalid>()
        base.applications.keys shouldContainExactly listOf(created.id)
    }
}
