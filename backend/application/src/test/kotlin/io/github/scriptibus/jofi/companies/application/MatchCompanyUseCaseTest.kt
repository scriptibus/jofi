// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.application

import io.github.scriptibus.jofi.companies.application.CompanyFixtures.Companion.CLOCK
import io.github.scriptibus.jofi.companies.application.port.CompanyRepositoryPort
import io.github.scriptibus.jofi.companies.application.port.api.MatchCompanyPort.Match
import io.github.scriptibus.jofi.companies.domain.CompanyId
import io.github.scriptibus.jofi.companies.domain.CompanySearch
import io.github.scriptibus.jofi.companies.domain.CompanyStoreResult
import io.github.scriptibus.jofi.shared.domain.Actor
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.spyk
import org.junit.jupiter.api.Test

class MatchCompanyUseCaseTest {
    private val fixtures = CompanyFixtures()
    private val create = CreateCompanyUseCase(fixtures.companyPort, fixtures.changelog, fixtures.transactions, CLOCK)
    private val match = MatchCompanyUseCase(fixtures.companyPort, create)

    @Test
    fun `a name with the same key finds the existing company and creates nothing`() {
        val acme = fixtures.company("ACME Robotics GmbH")
        fixtures.company("ACME Robotics Services GmbH")

        match.execute(" ACME  Robotics ", Actor.Ai) shouldBe Match.Found(acme.id.value)
        match.execute("acme robotics", Actor.Ai) shouldBe Match.Found(acme.id.value)

        fixtures.companies.size shouldBe 2
        fixtures.entries shouldHaveSize 0
    }

    @Test
    fun `a merely similar name creates a new company as the actor, which the next import then finds`() {
        fixtures.company("ACME Robotics GmbH")

        val created = match.execute("ACME", Actor.Ai).shouldBeInstanceOf<Match.Created>()

        fixtures.companies
            .getValue(CompanyId(created.id))
            .details.name shouldBe "ACME"
        fixtures.entries.map { it.actor } shouldContainExactly listOf(Actor.Ai)
        match.execute("ACME AG", Actor.Ai) shouldBe Match.Found(created.id)
    }

    @Test
    fun `a blank or too long name is invalid and a failing store unavailable`() {
        match.execute("  ", Actor.Ai) shouldBe Match.InvalidName
        match.execute("x".repeat(201), Actor.Ai) shouldBe Match.InvalidName

        val broken = spyk<CompanyRepositoryPort>(fixtures.companyPort)
        every { broken.search(any<CompanySearch>()) } returns CompanyStoreResult.StorageFailure("search")
        MatchCompanyUseCase(broken, create).execute("ACME", Actor.Ai) shouldBe Match.Unavailable
        fixtures.failingChangelog = true
        match.execute("Initech", Actor.Ai) shouldBe Match.Unavailable

        fixtures.companies.size shouldBe 0
    }
}
