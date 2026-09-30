// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.architecture

import com.lemonappdev.konsist.api.Konsist
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.junit.jupiter.api.Test

/** [InboundPortRules] on the production sources, and against known-good and known-bad fixtures. */
class InboundPortRulesTest {
    @Test
    fun `every inbound port has exactly its use case as implementor`() {
        InboundPortRules.violations(Konsist.scopeFromProduction()).shouldBeEmpty()
    }

    @Test
    fun `the rule catches missing, shared, misnamed and stale implementations`() {
        val fixtures = Konsist.scopeFromPackage("$FIXTURE..", sourceSetName = "test")

        val violations =
            InboundPortRules.violations(
                fixtures,
                mapOf("PendingThingPort" to "#1", "GoneThingPort" to "#2"),
            )

        violations shouldContainExactlyInAnyOrder
            listOf(
                "GoneThingPort is awaited but does not exist",
                "LonelyThingPort needs exactly one implementor, LonelyThingUseCase, but has []",
                "SharedThingPort needs exactly one implementor, SharedThingUseCase, but has " +
                    "[$FIXTURE.adapter.web.InboundPortInAdapterFixture, $FIXTURE.application.SharedThingUseCase]",
                "$FIXTURE.adapter.web.InboundPortInAdapterFixture implements SharedThingPort; " +
                    "only SharedThingUseCase in ..application may",
                "$FIXTURE.application.OtherThingUseCase implements MisnamedThingPort; " +
                    "only MisnamedThingUseCase in ..application may",
            )
    }

    @Test
    fun `an awaited port that got its implementor must leave the list`() {
        val fixtures = Konsist.scopeFromPackage("$FIXTURE..", sourceSetName = "test")

        InboundPortRules.violations(fixtures, mapOf("GoodThingPort" to "#1")) shouldContainExactlyInAnyOrder
            listOf(
                "GoodThingPort is implemented ([$FIXTURE.application.GoodThingUseCase]): " +
                    "remove it from AWAITING_USE_CASE",
                "LonelyThingPort needs exactly one implementor, LonelyThingUseCase, but has []",
                "PendingThingPort needs exactly one implementor, PendingThingUseCase, but has []",
                "SharedThingPort needs exactly one implementor, SharedThingUseCase, but has " +
                    "[$FIXTURE.adapter.web.InboundPortInAdapterFixture, $FIXTURE.application.SharedThingUseCase]",
                "$FIXTURE.adapter.web.InboundPortInAdapterFixture implements SharedThingPort; " +
                    "only SharedThingUseCase in ..application may",
                "$FIXTURE.application.OtherThingUseCase implements MisnamedThingPort; " +
                    "only MisnamedThingUseCase in ..application may",
            )
    }

    private companion object {
        const val FIXTURE = "io.github.scriptibus.jofi.fixture"
    }
}
