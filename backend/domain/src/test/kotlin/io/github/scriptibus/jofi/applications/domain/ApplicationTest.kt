// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ApplicationTest {
    private val at = Instant.parse("2026-09-30T08:00:00Z")
    private val later = at.plusSeconds(60)
    private val id = ApplicationId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
    private val company = CompanyRef(UUID.fromString("00000000-0000-0000-0000-00000000000c"))
    private val details = ApplicationDetails("Backend Engineer", company, portalNotes = "Secret notes")
    private val contact = ContactRef(UUID.randomUUID())

    @Test
    fun `a new application starts at version 0, read, without contacts or scores`() {
        val application = Application.create(id, details, at)

        application.version shouldBe Application.INITIAL_VERSION
        application.unread shouldBe false
        application.contacts shouldBe emptySet()
        application.wantScore shouldBe null
        application.createdAt shouldBe at
        application.updatedAt shouldBe at
        Application.create(id, details, at, unread = true).unread shouldBe true
    }

    @Test
    fun `changelog entries refer to it as an application`() {
        id.toEntityRef() shouldBe EntityRef("application", id.value.toString())
    }

    @Test
    fun `an edit is a new version, an unchanged edit is the same application`() {
        val application = Application.create(id, details, at)

        val edited = application.edit(details.copy(title = "Staff Engineer"), later)

        edited.version shouldBe 1
        edited.updatedAt shouldBe later
        edited.details.title shouldBe "Staff Engineer"
        application.edit(details, later) shouldBeSameInstanceAs application
    }

    @Test
    fun `linking contacts is a new version, the same set is not`() {
        val application = Application.create(id, details, at)

        val linked = application.linkContacts(setOf(contact), later)

        linked.contacts shouldBe setOf(contact)
        linked.version shouldBe 1
        linked.linkContacts(setOf(contact), later.plusSeconds(1)) shouldBeSameInstanceAs linked
    }

    @Test
    fun `marking it read or unread keeps the version and the update time`() {
        val application = Application.create(id, details, at, unread = true)

        val read = application.markUnread(false)

        read.unread shouldBe false
        read.version shouldBe application.version
        read.updatedAt shouldBe at
    }

    @Test
    fun `invariants guard the version, the times and the number of contacts`() {
        val application = Application.create(id, details, at)
        shouldThrow<IllegalArgumentException> { application.copy(version = -1) }
        shouldThrow<IllegalArgumentException> { application.copy(updatedAt = at.minusSeconds(1)) }
        val tooMany = (0..Application.MAX_CONTACTS).map { ContactRef(UUID.randomUUID()) }.toSet()
        shouldThrow<IllegalArgumentException> { application.copy(contacts = tooMany) }
        application.copy(contacts = tooMany.drop(1).toSet()).contacts.size shouldBe Application.MAX_CONTACTS
    }

    @Test
    fun `scores are 0 to 5 with one decimal`() {
        Score(0).tenths shouldBe 0
        Score(Score.MAX_TENTHS).toString() shouldBe "50/10"
        shouldThrow<IllegalArgumentException> { Score(-1) }
        shouldThrow<IllegalArgumentException> { Score(Score.MAX_TENTHS + 1) }
    }

    @Test
    fun `details guard their text invariants and print none of the notes`() {
        shouldThrow<IllegalArgumentException> { details.copy(title = " ") }
        shouldThrow<IllegalArgumentException> {
            details.copy(
                title = "x".repeat(ApplicationDetails.MAX_TITLE_LENGTH + 1),
            )
        }
        shouldThrow<IllegalArgumentException> { details.copy(location = "Berlin ") }
        shouldThrow<IllegalArgumentException> { details.copy(portalNotes = "a\u0000b") }
        details.copy(title = "x".repeat(ApplicationDetails.MAX_TITLE_LENGTH)).title.length shouldBe
            ApplicationDetails.MAX_TITLE_LENGTH
        Application.create(id, details, at).toString() shouldNotContain "Secret"
        details.toString() shouldNotContain "Secret"
        details.toString() shouldNotContain "Backend"
    }
}
