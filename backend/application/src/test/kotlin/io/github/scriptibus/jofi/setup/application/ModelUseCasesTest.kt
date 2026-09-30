// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.application

import io.github.scriptibus.jofi.setup.application.SetupFixtures.Companion.CLOCK
import io.github.scriptibus.jofi.setup.application.SetupFixtures.Companion.NOW
import io.github.scriptibus.jofi.setup.application.SetupFixtures.Companion.TOOLS_AND_STREAMING
import io.github.scriptibus.jofi.setup.domain.Capability
import io.github.scriptibus.jofi.setup.domain.CapabilityInput
import io.github.scriptibus.jofi.setup.domain.CapabilityName
import io.github.scriptibus.jofi.setup.domain.CapabilitySource
import io.github.scriptibus.jofi.setup.domain.ModelCapabilities
import io.github.scriptibus.jofi.setup.domain.ModelCapabilityProfile
import io.github.scriptibus.jofi.setup.domain.ModelName
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.SetupField
import io.github.scriptibus.jofi.setup.domain.SetupResult
import io.github.scriptibus.jofi.setup.domain.SetupViolation
import io.github.scriptibus.jofi.setup.domain.SetupViolationKind
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ai.AiResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Instant
import java.util.UUID

class ModelUseCasesTest {
    private val setup = SetupFixtures()
    private val refresh =
        RefreshProviderModelsUseCase(
            setup.providerPort,
            setup.catalog,
            setup.profilePort,
            setup.changelog,
            setup.transactions,
            CLOCK,
        )
    private val correct =
        CorrectModelCapabilitiesUseCase(
            setup.providerPort,
            setup.profilePort,
            setup.changelog,
            setup.transactions,
            CLOCK,
        )
    private val list = ListProviderModelsUseCase(setup.providerPort, setup.profilePort)
    private val provider = setup.provider()

    private fun detected(
        model: String,
        capabilities: ModelCapabilities = TOOLS_AND_STREAMING,
    ) = ModelCapabilityProfile(provider.id, ModelName(model), capabilities, CapabilitySource.DETECTED, Instant.EPOCH)

    @Test
    fun `a refresh stores the listed models as detected profiles and keeps the user's corrections`() {
        val corrected =
            ModelCapabilityProfile(
                provider.id,
                ModelName("llama3.1"),
                ModelCapabilities.NONE,
                CapabilitySource.USER,
                NOW,
            )
        setup.profiles[provider.id to corrected.model] = corrected
        setup.detected = AiResult.Success(listOf(detected("llama3.1"), detected("qwen3")))

        val models =
            refresh
                .execute(
                    provider.id,
                    Actor.User,
                ).shouldBeInstanceOf<SetupResult.Success<List<ModelCapabilityProfile>>>()

        models.value shouldBe listOf(corrected, detected("qwen3").copy(updatedAt = NOW))
        list.execute(provider.id) shouldBe SetupResult.Success(models.value)
        val entry = setup.entries.single()
        entry.actor shouldBe Actor.User
        entry.entity shouldBe provider.id.toEntityRef()
        entry.change.description shouldBe "Refreshed the model list: 2 models listed, 1 updated"
    }

    @Test
    fun `a failed connection is the provider's answer and stores nothing`() {
        setup.detected = AiResult.AuthenticationFailed

        refresh.execute(provider.id, Actor.User) shouldBe SetupResult.ProviderFailed(AiResult.AuthenticationFailed)
        setup.profiles.shouldBeEmpty()
        setup.entries.shouldBeEmpty()
    }

    @Test
    fun `models of an unknown provider are not found`() {
        val unknown = ProviderId(UUID.randomUUID())

        refresh.execute(unknown, Actor.User) shouldBe SetupResult.NotFound
        list.execute(unknown) shouldBe SetupResult.NotFound
    }

    @Test
    fun `the user's correction is stored as a USER profile and recorded`() {
        setup.profiles[provider.id to ModelName("qwen3")] = detected("qwen3")
        val input = CapabilityInput(" qwen3 ", setOf(CapabilityName.STREAMING), 32_768)

        val profile =
            correct
                .execute(
                    provider.id,
                    input,
                    Actor.User,
                ).shouldBeInstanceOf<SetupResult.Success<ModelCapabilityProfile>>()

        profile.value shouldBe
            ModelCapabilityProfile(
                provider.id,
                ModelName("qwen3"),
                ModelCapabilities(setOf(Capability.Streaming, Capability.ContextSize(32_768))),
                CapabilitySource.USER,
                NOW,
            )
        setup.profiles[provider.id to ModelName("qwen3")] shouldBe profile.value
        val change = setup.entries.single().change
        change.description shouldBe "Corrected the capabilities of model qwen3"
        change.fieldChanges.single().field shouldBe "capabilities"
    }

    @Test
    fun `a correction must name a model and a positive context window`() {
        correct.execute(provider.id, CapabilityInput(" ", emptySet(), 0), Actor.User) shouldBe
            SetupResult.Invalid(
                listOf(
                    SetupViolation(SetupField.MODEL, SetupViolationKind.REQUIRED),
                    SetupViolation(SetupField.CONTEXT_WINDOW, SetupViolationKind.OUT_OF_RANGE),
                ),
            )
        setup.profiles.shouldBeEmpty()
    }

    @ParameterizedTest
    @MethodSource("io.github.scriptibus.jofi.setup.application.SetupActors#othersThanTheUser")
    fun `only the user may refresh or correct models`(actor: Actor) {
        setup.detected = AiResult.Success(listOf(detected("qwen3")))

        refresh.execute(provider.id, actor) shouldBe SetupResult.Forbidden
        correct.execute(provider.id, CapabilityInput("qwen3", emptySet(), null), actor) shouldBe SetupResult.Forbidden
        setup.profiles.shouldBeEmpty()
        setup.entries.shouldBeEmpty()
    }
}
