// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.domain

import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class CompanyTest {
    private val id = CompanyId(UUID.fromString("00000000-0000-0000-0000-00000000000c"))
    private val created = Instant.parse("2026-09-30T08:00:00Z")
    private val later = created.plusSeconds(60)
    private val company = Company.create(id, CompanyDetails("ACME GmbH"), created)

    @Test
    fun `a new company has no profile, no preference and the initial version`() {
        company.profile.shouldBeNull()
        company.preference shouldBe CompanyPreference.None
        company.version shouldBe Company.INITIAL_VERSION
        company.createdAt shouldBe created
        company.updatedAt shouldBe created
    }

    @Test
    fun `changelog entries refer to a company by the registered entity type`() {
        id.toEntityRef() shouldBe EntityRef("company", "00000000-0000-0000-0000-00000000000c")
        CompanyId.ENTITY_TYPE shouldBe "company"
    }

    @Test
    fun `editing replaces the details, counts the version up and keeps the creation time`() {
        val details = CompanyDetails("ACME AG", industry = "Robotics")

        val edited = company.edit(details, later)

        edited.details shouldBe details
        edited.version shouldBe company.version + 1
        edited.createdAt shouldBe created
        edited.updatedAt shouldBe later
    }

    @Test
    fun `a preference change counts the version up and announces the change with its actor`() {
        val blacklisted = CompanyPreference.Blacklisted("Declined twice")

        val update = company.changePreference(blacklisted, Actor.Ai, later).shouldNotBeNull()

        update.company.preference shouldBe blacklisted
        update.company.version shouldBe company.version + 1
        update.company.updatedAt shouldBe later
        update.event shouldBe CompanyPreferenceChanged(id, CompanyPreference.None, blacklisted, Actor.Ai, later)
    }

    @Test
    fun `setting the preference the company already has changes nothing`() {
        company.changePreference(CompanyPreference.None, Actor.User, later).shouldBeNull()

        val favourite = company.changePreference(CompanyPreference.Favourite(), Actor.User, later)?.company
        favourite.shouldNotBeNull().changePreference(CompanyPreference.Favourite(), Actor.User, later).shouldBeNull()
    }

    @Test
    fun `a company is never updated before it was created and has no negative version`() {
        shouldThrow<IllegalArgumentException> { company.copy(updatedAt = created.minusSeconds(1)) }
        shouldThrow<IllegalArgumentException> { company.copy(version = -1) }
    }

    @Test
    fun `a profile is non-blank Markdown of bounded length`() {
        CompanyProfile("# ACME\nBuilds anvils.", later).markdown shouldBe "# ACME\nBuilds anvils."
        shouldThrow<IllegalArgumentException> { CompanyProfile(" ", later) }
        shouldThrow<IllegalArgumentException> { CompanyProfile("x".repeat(CompanyProfile.MAX_LENGTH + 1), later) }
    }

    @Test
    fun `a preference change event must change the preference`() {
        shouldThrow<IllegalArgumentException> {
            CompanyPreferenceChanged(id, CompanyPreference.None, CompanyPreference.None, Actor.User, later)
        }
    }

    @Test
    fun `preferences carry their kind and an optional trimmed reason`() {
        CompanyPreference.of(PreferenceKind.NONE, "ignored") shouldBe CompanyPreference.None
        CompanyPreference.of(PreferenceKind.FAVOURITE, "Great team") shouldBe CompanyPreference.Favourite("Great team")
        CompanyPreference.of(PreferenceKind.BLACKLISTED, null).kind shouldBe PreferenceKind.BLACKLISTED
        CompanyPreference.None.reason.shouldBeNull()
        shouldThrow<IllegalArgumentException> { CompanyPreference.Favourite(" ") }
        shouldThrow<IllegalArgumentException> { CompanyPreference.Blacklisted(" padded ") }
        shouldThrow<IllegalArgumentException> {
            CompanyPreference.Blacklisted("x".repeat(CompanyPreference.MAX_REASON_LENGTH + 1))
        }
    }
}
