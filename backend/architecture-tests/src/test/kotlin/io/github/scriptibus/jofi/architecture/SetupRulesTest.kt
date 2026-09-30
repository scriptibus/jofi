// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import io.github.scriptibus.jofi.setup.adapter.mcp.SetupToolFixture
import io.github.scriptibus.jofi.setup.adapter.mcp.UserActingToolFixture
import io.github.scriptibus.jofi.setup.adapter.web.SetupWebFixture
import io.github.scriptibus.jofi.setup.application.CreateProviderUseCase
import io.github.scriptibus.jofi.shared.domain.Actor
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The setup rules on production classes and against known-good and known-bad fixtures. */
class SetupRulesTest {
    @Test
    fun `only the setup REST API reaches the mutating setup use cases`() {
        SetupRules.onlyTheWebAdapterChangesTheSetup.check(JofiClasses.production)
    }

    @Test
    fun `only web adapters act as the user`() {
        SetupRules.onlyWebAdaptersActAsTheUser.check(JofiClasses.production)
    }

    @Test
    fun `a tool outside the web adapter wired to a setup use case is rejected, the web adapter is not`() {
        val tool = ClassFileImporter().importClasses(SetupToolFixture::class.java, CreateProviderUseCase::class.java)
        val web = ClassFileImporter().importClasses(SetupWebFixture::class.java, CreateProviderUseCase::class.java)

        SetupRules.onlyTheWebAdapterChangesTheSetup.evaluate(tool).hasViolation() shouldBe true
        SetupRules.onlyTheWebAdapterChangesTheSetup.evaluate(web).hasViolation() shouldBe false
    }

    @Test
    fun `a tool outside the web adapter acting as the user is rejected, the web adapter is not`() {
        val tool = ClassFileImporter().importClasses(UserActingToolFixture::class.java, Actor.User::class.java)
        val web = ClassFileImporter().importClasses(SetupWebFixture::class.java, Actor.User::class.java)

        SetupRules.onlyWebAdaptersActAsTheUser.evaluate(tool).hasViolation() shouldBe true
        SetupRules.onlyWebAdaptersActAsTheUser.evaluate(web).hasViolation() shouldBe false
    }
}
