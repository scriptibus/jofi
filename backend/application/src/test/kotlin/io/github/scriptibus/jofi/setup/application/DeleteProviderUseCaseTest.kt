// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.application

import io.github.scriptibus.jofi.setup.application.SetupFixtures.Companion.CLOCK
import io.github.scriptibus.jofi.setup.domain.ModelAssignment
import io.github.scriptibus.jofi.setup.domain.ModelName
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.setup.domain.SetupResult
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationEffect
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRejection
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequester
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.UUID

class DeleteProviderUseCaseTest {
    private val setup = SetupFixtures()
    private val delete =
        DeleteProviderUseCase(
            setup.providerPort,
            setup.assignmentPort,
            setup.secretPort,
            setup.confirmation,
            setup.changelog,
            setup.transactions,
            CLOCK,
        )
    private val user = ConfirmationRequester(Actor.User, "session-1")

    private fun firstStep(id: ProviderId): ConfirmationResult.Required {
        val result = delete.execute(id, user, null).shouldBeInstanceOf<SetupResult.Unconfirmed>()
        return result.outcome.shouldBeInstanceOf<ConfirmationResult.Required>()
    }

    @Test
    fun `the first call only asks, the confirmed repeat removes provider and key and is recorded`() {
        val provider = setup.provider(ProviderKind.OPENAI, "OpenAI")

        val required = firstStep(provider.id)
        required.action.operation shouldBe ProviderId.DELETE_OPERATION
        required.action.targets shouldBe listOf(provider.id.value.toString())
        required.action.effect shouldBe ConfirmationEffect("ai_provider", "OpenAI")
        setup.providers.size shouldBe 1

        delete.execute(provider.id, user, required.token) shouldBe SetupResult.Success(Unit)
        setup.providers.shouldBeEmpty()
        setup.secrets.shouldBeEmpty()
        val entry = setup.entries.single()
        entry.actor shouldBe Actor.User
        entry.change.description shouldBe "Removed AI provider"
    }

    @Test
    fun `a token works once`() {
        val provider = setup.provider()
        val token = firstStep(provider.id).token
        delete.execute(provider.id, user, token) shouldBe SetupResult.Success(Unit)

        delete.execute(provider.id, user, token) shouldBe SetupResult.NotFound
        setup.provider()
        val other = setup.providers.values.single()
        delete.execute(other.id, user, token) shouldBe
            SetupResult.Unconfirmed(ConfirmationResult.Rejected(ConfirmationRejection.UNKNOWN))
    }

    @Test
    fun `a rename between the two steps invalidates the token`() {
        val provider = setup.provider()
        val token = firstStep(provider.id).token
        setup.providers[provider.id] = provider.copy(displayName = "Something else")

        delete.execute(provider.id, user, token) shouldBe
            SetupResult.Unconfirmed(ConfirmationResult.Rejected(ConfirmationRejection.MISMATCH))
        setup.providers.size shouldBe 1
    }

    @Test
    fun `a provider with assigned tasks is refused before a token is issued`() {
        val provider = setup.provider()
        setup.assignments[AiTask.CHAT] = ModelAssignment(AiTask.CHAT, provider.id, ModelName("llama3.1"))

        delete.execute(provider.id, user, null) shouldBe SetupResult.InUse
        delete.execute(ProviderId(UUID.randomUUID()), user, null) shouldBe SetupResult.NotFound
    }

    @Test
    fun `a failing changelog keeps the provider and its key`() {
        val provider = setup.provider(ProviderKind.GEMINI, "Gemini")
        val token = firstStep(provider.id).token
        setup.failingChangelog = true

        delete.execute(provider.id, user, token) shouldBe SetupResult.StorageFailure("changelog")
        setup.providers.size shouldBe 1
        setup.secrets.size shouldBe 1
    }

    @ParameterizedTest
    @MethodSource("io.github.scriptibus.jofi.setup.application.SetupActors#othersThanTheUser")
    fun `only the user may remove a provider, even with a token`(actor: Actor) {
        val provider = setup.provider()

        delete.execute(provider.id, ConfirmationRequester(actor, "client-1"), ConfirmationToken("any")) shouldBe
            SetupResult.Forbidden
        setup.providers.size shouldBe 1
        setup.entries.shouldBeEmpty()
    }
}
