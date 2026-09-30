// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.ApplicationFixtures.Companion.CLOCK
import io.github.scriptibus.jofi.applications.application.ApplicationFixtures.Companion.NOW
import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationField
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationProblem
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ApplicationViolation
import io.github.scriptibus.jofi.applications.domain.ContactRef
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.FieldChange
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

class LinkApplicationContactsUseCaseTest {
    private val fixtures = ApplicationFixtures()
    private val link =
        LinkApplicationContactsUseCase(fixtures.repository, fixtures.changelog, fixtures.transactions, CLOCK)
    private val anna = contact("00000000-0000-0000-0000-0000000000c1")
    private val ben = contact("00000000-0000-0000-0000-0000000000c2")
    private val cleo = contact("00000000-0000-0000-0000-0000000000c3")

    private fun contact(id: String): ContactRef = ContactRef(UUID.fromString(id)).also { fixtures.contacts += it }

    private fun linked(
        application: Application,
        contacts: Set<ContactRef>,
        actor: Actor = Actor.User,
    ): Application =
        link
            .execute(application.id, contacts, application.version, actor)
            .shouldBeInstanceOf<ApplicationResult.Success<Application>>()
            .value

    private fun invalid(problem: ApplicationProblem): ApplicationResult.Invalid =
        ApplicationResult.Invalid(listOf(ApplicationViolation(ApplicationField.CONTACTS, problem)))

    @Test
    fun `linking stores a new version with a changelog entry of ids only`() {
        val stored = fixtures.application()
        val client = Actor.ExternalClient("claude-desktop")

        val result = linked(stored, setOf(ben, anna), client)

        result.contacts shouldBe setOf(anna, ben)
        result.version shouldBe 1
        result.updatedAt shouldBe NOW
        fixtures.applications[stored.id] shouldBe result
        val entry = fixtures.entries.single()
        entry.entity shouldBe stored.id.toEntityRef()
        entry.actor shouldBe client
        entry.occurredAt shouldBe NOW
        entry.change.description shouldBe "Changed linked contacts"
        entry.change.fieldChanges shouldContainExactly
            listOf(FieldChange("contacts", null, "${anna.value},${ben.value}"))
    }

    @Test
    fun `replacing the set records the unlinked ids before and the newly linked ones after`() {
        val first = linked(fixtures.application(), setOf(anna, ben))

        val second = linked(first, setOf(ben, cleo))

        second.contacts shouldBe setOf(ben, cleo)
        second.version shouldBe 2
        fixtures.entries
            .last()
            .change.fieldChanges shouldContainExactly
            listOf(FieldChange("contacts", anna.value.toString(), cleo.value.toString()))
        linked(second, emptySet()).contacts.shouldBeEmpty()
        fixtures.entries
            .last()
            .change.fieldChanges shouldContainExactly
            listOf(FieldChange("contacts", "${ben.value},${cleo.value}", null))
    }

    @Test
    fun `an unchanged set stores nothing and writes no entry`() {
        val first = linked(fixtures.application(), setOf(anna))
        val entries = fixtures.entries.size

        linked(first, setOf(anna)) shouldBe first
        fixtures.entries.size shouldBe entries
    }

    @Test
    fun `the version is checked first, even for a no-op or too many contacts`() {
        val stored = fixtures.application(version = 2)
        val tooMany = (1..Application.MAX_CONTACTS + 1).map { ContactRef(UUID.randomUUID()) }.toSet()

        link.execute(stored.id, emptySet(), 1, Actor.User) shouldBe ApplicationResult.VersionConflict
        link.execute(stored.id, tooMany, 1, Actor.User) shouldBe ApplicationResult.VersionConflict
        link.execute(ApplicationId(UUID.randomUUID()), setOf(anna), 0, Actor.User) shouldBe ApplicationResult.NotFound
        fixtures.entries.shouldBeEmpty()
    }

    @Test
    fun `at most the maximum of contacts may be linked`() {
        val stored = fixtures.application()
        val atMost = (1..Application.MAX_CONTACTS).map { ContactRef(UUID.randomUUID()) }.toSet()
        fixtures.contacts += atMost

        link.execute(stored.id, atMost + anna, stored.version, Actor.User) shouldBe
            invalid(ApplicationProblem.TOO_MANY)
        linked(stored, atMost).contacts shouldBe atMost
    }

    @Test
    fun `an unknown contact is invalid on the contacts and stores nothing`() {
        val stored = fixtures.application()

        link.execute(stored.id, setOf(anna, ContactRef(UUID.randomUUID())), stored.version, Actor.User) shouldBe
            invalid(ApplicationProblem.NOT_FOUND)
        fixtures.applications[stored.id] shouldBe stored
        fixtures.entries.shouldBeEmpty()
    }

    @Test
    fun `a failing changelog or a concurrent write rolls the links back`() {
        val stored = fixtures.application()
        fixtures.failingChangelog = true

        link.execute(stored.id, setOf(anna), stored.version, Actor.User) shouldBe
            ApplicationResult.StorageFailure("changelog")
        fixtures.applications[stored.id] shouldBe stored

        fixtures.failingChangelog = false
        fixtures.concurrentVersion = 1
        link.execute(stored.id, setOf(anna), stored.version, Actor.User) shouldBe ApplicationResult.VersionConflict
        fixtures.applications[stored.id] shouldBe stored
        fixtures.entries.shouldBeEmpty()
    }
}
