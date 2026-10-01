// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.application

import io.github.scriptibus.jofi.setup.application.SetupFixtures.Companion.CLOCK
import io.github.scriptibus.jofi.setup.application.SetupFixtures.Companion.NOW
import io.github.scriptibus.jofi.setup.domain.ModelName
import io.github.scriptibus.jofi.setup.domain.ModelPriceInput
import io.github.scriptibus.jofi.setup.domain.ModelPriceOverride
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.setup.domain.SetupField
import io.github.scriptibus.jofi.setup.domain.SetupResult
import io.github.scriptibus.jofi.setup.domain.SetupViolation
import io.github.scriptibus.jofi.setup.domain.SetupViolationKind
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.FieldChange
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.UUID

class ModelPriceUseCasesTest {
    private val setup = SetupFixtures()
    private val set =
        SetModelPriceUseCase(setup.providerPort, setup.pricePort, setup.changelog, setup.transactions, CLOCK)
    private val clear =
        ClearModelPriceUseCase(setup.providerPort, setup.pricePort, setup.changelog, setup.transactions, CLOCK)
    private val list = ListModelPricesUseCase(setup.providerPort, setup.pricePort)
    private val provider = setup.provider()
    private val model = ModelName("llama3.1:8b")

    private fun price(
        input: Long,
        output: Long,
    ) = ModelPriceInput(model.value, input, output)

    private fun stored(
        input: Long,
        output: Long,
    ) = ModelPriceOverride(provider.id, model, input, output, NOW)

    @Test
    fun `the user prices a model of an OpenAI-compatible provider and the change is logged with the actor`() {
        set.execute(provider.id, price(150_000, 600_000), Actor.User) shouldBe
            SetupResult.Success(stored(150_000, 600_000))

        setup.prices[provider.id to model] shouldBe stored(150_000, 600_000)
        val entry = setup.entries.single()
        entry.entity shouldBe provider.id.toEntityRef()
        entry.actor shouldBe Actor.User
        entry.change.description shouldBe "Set the price of model llama3.1:8b"
        entry.change.fieldChanges shouldBe
            listOf(
                FieldChange("inputMicrosPerMillion", null, "150000"),
                FieldChange("outputMicrosPerMillion", null, "600000"),
            )
    }

    @Test
    fun `a price of zero is a price`() {
        set.execute(provider.id, price(0, 0), Actor.User) shouldBe SetupResult.Success(stored(0, 0))
        setup.prices[provider.id to model] shouldBe stored(0, 0)
    }

    @Test
    fun `changing a price logs only what changed and the same price writes nothing`() {
        set.execute(provider.id, price(1, 2), Actor.User)
        setup.entries.clear()

        set.execute(provider.id, price(1, 2), Actor.User) shouldBe SetupResult.Success(stored(1, 2))
        setup.entries.shouldBeEmpty()

        set.execute(provider.id, price(1, 5), Actor.User)
        setup.entries
            .single()
            .change.fieldChanges shouldBe listOf(FieldChange("outputMicrosPerMillion", "2", "5"))
    }

    @Test
    fun `invalid input names the fields and changes nothing`() {
        set.execute(
            provider.id,
            ModelPriceInput(" ", -1, ModelPriceOverride.MAX_MICROS_PER_MILLION + 1),
            Actor.User,
        ) shouldBe
            SetupResult.Invalid(
                listOf(
                    SetupViolation(SetupField.MODEL, SetupViolationKind.REQUIRED),
                    SetupViolation(SetupField.INPUT_PRICE, SetupViolationKind.OUT_OF_RANGE),
                    SetupViolation(SetupField.OUTPUT_PRICE, SetupViolationKind.OUT_OF_RANGE),
                ),
            )

        setup.prices.shouldBeEmpty()
        setup.entries.shouldBeEmpty()
    }

    @ParameterizedTest
    @MethodSource("cloudKinds")
    fun `a cloud provider has verified prices and takes no override`(kind: ProviderKind) {
        val cloud = setup.provider(kind, "Cloud")

        set.execute(cloud.id, price(1, 1), Actor.User) shouldBe SetupResult.PriceNotAllowed
        clear.execute(cloud.id, model.value, Actor.User) shouldBe SetupResult.PriceNotAllowed
        setup.prices.shouldBeEmpty()
    }

    @Test
    fun `an unknown provider is not found`() {
        val unknown = ProviderId(UUID.randomUUID())

        set.execute(unknown, price(1, 1), Actor.User) shouldBe SetupResult.NotFound
        clear.execute(unknown, model.value, Actor.User) shouldBe SetupResult.NotFound
        list.execute(unknown) shouldBe SetupResult.NotFound
    }

    @Test
    fun `only the user prices or unprices a model`() {
        listOf(Actor.Ai, Actor.Scanner("indeed-feed"), Actor.ExternalClient("claude-desktop")).forEach { actor ->
            set.execute(provider.id, price(1, 1), actor) shouldBe SetupResult.Forbidden
            clear.execute(provider.id, model.value, actor) shouldBe SetupResult.Forbidden
        }
        setup.prices.shouldBeEmpty()
        setup.entries.shouldBeEmpty()
    }

    @Test
    fun `clearing removes the price and logs the old values`() {
        set.execute(provider.id, price(1, 2), Actor.User)
        setup.entries.clear()

        clear.execute(provider.id, "  llama3.1:8b ", Actor.User) shouldBe SetupResult.Success(Unit)

        setup.prices.shouldBeEmpty()
        val entry = setup.entries.single()
        entry.change.description shouldBe "Removed the price of model llama3.1:8b"
        entry.change.fieldChanges shouldBe
            listOf(FieldChange("inputMicrosPerMillion", "1", null), FieldChange("outputMicrosPerMillion", "2", null))
    }

    @Test
    fun `clearing a model without a price succeeds and logs nothing`() {
        clear.execute(provider.id, model.value, Actor.User) shouldBe SetupResult.Success(Unit)
        setup.entries.shouldBeEmpty()
    }

    @Test
    fun `clearing an empty model name is invalid`() {
        clear.execute(provider.id, " ", Actor.User) shouldBe
            SetupResult.Invalid(listOf(SetupViolation(SetupField.MODEL, SetupViolationKind.REQUIRED)))
    }

    @Test
    fun `a failing changelog rolls the price back`() {
        setup.failingChangelog = true

        set.execute(provider.id, price(1, 1), Actor.User) shouldBe SetupResult.StorageFailure("changelog")
        setup.prices.shouldBeEmpty()

        setup.failingChangelog = false
        set.execute(provider.id, price(1, 1), Actor.User)
        setup.failingChangelog = true
        clear.execute(provider.id, model.value, Actor.User) shouldBe SetupResult.StorageFailure("changelog")
        setup.prices.size shouldBe 1
    }

    @Test
    fun `a failing store is reported and logs nothing`() {
        setup.failingWrites = true

        set.execute(provider.id, price(1, 1), Actor.User) shouldBe SetupResult.StorageFailure("save model price")
        setup.entries.shouldBeEmpty()
    }

    @Test
    fun `the list is the prices of one provider by model name`() {
        val other = setup.provider(name = "OpenRouter")
        set.execute(provider.id, ModelPriceInput("zeta", 1, 1), Actor.User)
        set.execute(provider.id, ModelPriceInput("alpha", 2, 2), Actor.User)
        set.execute(other.id, ModelPriceInput("alpha", 3, 3), Actor.User)

        (
            list.execute(
                provider.id,
            ) as SetupResult.Success
        ).value.map { it.model.value to it.inputMicrosPerMillion } shouldBe
            listOf("alpha" to 2L, "zeta" to 1L)
    }

    @Test
    fun `the same model of two providers has two prices`() {
        val other = setup.provider(name = "OpenRouter")

        set.execute(provider.id, price(1, 1), Actor.User)
        set.execute(other.id, price(9, 9), Actor.User)

        setup.prices[provider.id to model]?.inputMicrosPerMillion shouldBe 1
        setup.prices[other.id to model]?.inputMicrosPerMillion shouldBe 9
    }

    companion object {
        @JvmStatic
        fun cloudKinds() = ProviderKind.entries.filter { !it.needsBaseUri }
    }
}
