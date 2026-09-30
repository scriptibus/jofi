// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import io.github.scriptibus.jofi.fixture.adapter.web.ConfirmedDeleteFixtureController
import io.github.scriptibus.jofi.fixture.adapter.web.RequestMappingDeleteFixtureController
import io.github.scriptibus.jofi.fixture.adapter.web.UnconfirmedDeleteFixtureController
import io.github.scriptibus.jofi.fixture.adapter.web.UnconfirmedSendFixtureController
import io.github.scriptibus.jofi.fixture.application.DeleteThingWithGateUseCase
import io.github.scriptibus.jofi.fixture.application.DeleteThingWithoutGateUseCase
import io.github.scriptibus.jofi.fixture.application.ForgedConfirmationUseCase
import io.github.scriptibus.jofi.fixture.application.SendThingWithProofUseCase
import io.github.scriptibus.jofi.fixture.application.port.FixtureThingsPort
import io.github.scriptibus.jofi.shared.application.ConfirmActionUseCase
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * The two-step confirmation is enforced in code (ADR-0039): on production classes, and against
 * known-bad and known-good fixtures so no rule can silently pass.
 */
class ConfirmationRulesTest {
    private val production = JofiClasses.production

    @Test
    fun `every delete endpoint takes the confirmation header`() {
        ConfirmationRules.destructiveEndpointsTakeTheConfirmationHeader().check(production)
    }

    @Test
    fun `every destructive port call passes the gate`() {
        ConfirmationRules.destructivePortCallsPassTheGate.check(production)
    }

    @Test
    fun `only the gate mints confirmations`() {
        ConfirmationRules.onlyTheGateMintsConfirmations.check(production)
    }

    @ParameterizedTest(name = "{0} is rejected")
    @ValueSource(classes = [UnconfirmedDeleteFixtureController::class, RequestMappingDeleteFixtureController::class])
    fun `a delete endpoint without the header is rejected`(fixture: Class<*>) {
        val classes = ClassFileImporter().importClasses(fixture)

        ConfirmationRules.destructiveEndpointsTakeTheConfirmationHeader().evaluate(classes).hasViolation() shouldBe
            true
    }

    @Test
    fun `an endpoint declared outward-facing without the header is rejected`() {
        val classes = ClassFileImporter().importClasses(UnconfirmedSendFixtureController::class.java)
        val rule = ConfirmationRules::destructiveEndpointsTakeTheConfirmationHeader

        rule(setOf("POST /api/fixture/send/{id}")).evaluate(classes).hasViolation() shouldBe true
        rule(emptySet()).evaluate(classes).hasViolation() shouldBe false
    }

    @Test
    fun `a delete endpoint with the header passes`() {
        val classes = ClassFileImporter().importClasses(ConfirmedDeleteFixtureController::class.java)

        ConfirmationRules.destructiveEndpointsTakeTheConfirmationHeader().evaluate(classes).hasViolation() shouldBe
            false
    }

    @Test
    fun `a use case deleting through a port without the gate is rejected`() {
        val classes =
            ClassFileImporter().importClasses(DeleteThingWithoutGateUseCase::class.java, FixtureThingsPort::class.java)

        ConfirmationRules.destructivePortCallsPassTheGate.evaluate(classes).hasViolation() shouldBe true
    }

    @Test
    fun `a use case with the gate, or calling a port that demands the proof, passes`() {
        val classes =
            ClassFileImporter().importClasses(
                DeleteThingWithGateUseCase::class.java,
                SendThingWithProofUseCase::class.java,
                FixtureThingsPort::class.java,
                ConfirmActionUseCase::class.java,
            )

        ConfirmationRules.destructivePortCallsPassTheGate.evaluate(classes).hasViolation() shouldBe false
    }

    @Test
    fun `confirming an action outside the gate is rejected`() {
        val classes = ClassFileImporter().importClasses(ForgedConfirmationUseCase::class.java)

        ConfirmationRules.onlyTheGateMintsConfirmations.evaluate(classes).hasViolation() shouldBe true
    }
}
