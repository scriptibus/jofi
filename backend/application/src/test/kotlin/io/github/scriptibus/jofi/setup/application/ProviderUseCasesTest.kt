// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.application

import io.github.scriptibus.jofi.setup.application.SetupFixtures.Companion.CLOCK
import io.github.scriptibus.jofi.setup.application.SetupFixtures.Companion.NOW
import io.github.scriptibus.jofi.setup.domain.ProviderConfig
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.ProviderInput
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.setup.domain.SetupField
import io.github.scriptibus.jofi.setup.domain.SetupResult
import io.github.scriptibus.jofi.setup.domain.SetupViolation
import io.github.scriptibus.jofi.setup.domain.SetupViolationKind
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.FieldChange
import io.github.scriptibus.jofi.shared.domain.secret.SecretId
import io.github.scriptibus.jofi.shared.domain.secret.SecretValue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.net.URI
import java.util.UUID

class ProviderUseCasesTest {
    private val setup = SetupFixtures()
    private val create =
        CreateProviderUseCase(setup.providerPort, setup.secretPort, setup.changelog, setup.transactions, CLOCK)
    private val update =
        UpdateProviderUseCase(setup.providerPort, setup.secretPort, setup.changelog, setup.transactions, CLOCK)
    private val list = ListProvidersUseCase(setup.providerPort)

    private fun created(result: SetupResult<ProviderConfig>): ProviderConfig =
        result.shouldBeInstanceOf<SetupResult.Success<ProviderConfig>>().value

    @Test
    fun `a cloud provider's key goes only into the secret store, and the user's change is recorded`() {
        val config =
            created(create.execute(ProviderKind.ANTHROPIC, ProviderInput(" Claude ", null, " sk-ant-1 "), Actor.User))

        config.displayName shouldBe "Claude"
        val keyId = config.apiKey.shouldBeInstanceOf<SecretId>()
        setup.secrets[keyId] shouldBe SecretValue("sk-ant-1")
        setup.providers[config.id] shouldBe config
        list.execute() shouldBe SetupResult.Success(listOf(config))
        val entry = setup.entries.single()
        entry.actor shouldBe Actor.User
        entry.entity shouldBe config.id.toEntityRef()
        entry.occurredAt shouldBe NOW
        entry.change.fieldChanges shouldBe
            listOf(
                FieldChange("displayName", null, "Claude"),
                FieldChange("kind", null, "ANTHROPIC"),
                FieldChange("apiKey", null, "set"),
            )
        entry.toString() shouldNotContain "sk-ant-1"
    }

    @Test
    fun `an OpenAI-compatible endpoint needs a base URL and may go without a key`() {
        val config =
            created(
                create.execute(
                    ProviderKind.OPENAI_COMPATIBLE,
                    ProviderInput("Ollama", "http://localhost:11434/v1", ""),
                    Actor.User,
                ),
            )

        config.baseUri shouldBe URI("http://localhost:11434/v1")
        config.apiKey shouldBe null
        setup.secrets.shouldBeEmpty()
    }

    @Test
    fun `invalid input stores nothing and names every problem`() {
        val result = create.execute(ProviderKind.OPENAI, ProviderInput(" ", "http://localhost:1234", null), Actor.User)

        result shouldBe
            SetupResult.Invalid(
                listOf(
                    SetupViolation(SetupField.DISPLAY_NAME, SetupViolationKind.REQUIRED),
                    SetupViolation(SetupField.BASE_URL, SetupViolationKind.NOT_ALLOWED),
                    SetupViolation(SetupField.API_KEY, SetupViolationKind.REQUIRED),
                ),
            )
        setup.providers.shouldBeEmpty()
        setup.entries.shouldBeEmpty()
    }

    @Test
    fun `a failing changelog rolls back provider and key`() {
        setup.failingChangelog = true

        create.execute(ProviderKind.OPENAI, ProviderInput("OpenAI", null, "sk-1"), Actor.User) shouldBe
            SetupResult.StorageFailure("changelog")
        setup.providers.shouldBeEmpty()
        setup.secrets.shouldBeEmpty()
    }

    @ParameterizedTest
    @MethodSource("io.github.scriptibus.jofi.setup.application.SetupActors#othersThanTheUser")
    fun `only the user may add or change a provider`(actor: Actor) {
        val existing = setup.provider()

        create.execute(ProviderKind.OPENAI, ProviderInput("OpenAI", null, "sk-1"), actor) shouldBe SetupResult.Forbidden
        update.execute(existing.id, ProviderInput("Evil", "http://169.254.169.254/v1", null), actor) shouldBe
            SetupResult.Forbidden
        setup.providers.values.toList() shouldBe listOf(existing)
        setup.entries.shouldBeEmpty()
    }

    @Test
    fun `an update without a key keeps the stored one and records only what changed`() {
        val existing = setup.provider(ProviderKind.OPENAI, "OpenAI")

        val updated = created(update.execute(existing.id, ProviderInput("OpenAI (work)", null, null), Actor.User))

        updated shouldBe existing.copy(displayName = "OpenAI (work)")
        setup.secrets[checkNotNull(existing.apiKey)] shouldBe SecretValue("sk-stored")
        setup.entries
            .single()
            .change.fieldChanges shouldBe
            listOf(FieldChange("displayName", "OpenAI", "OpenAI (work)"))
    }

    @Test
    fun `a new key replaces the stored one under the same secret id, without its value in the changelog`() {
        val existing = setup.provider(ProviderKind.MISTRAL, "Mistral")

        created(update.execute(existing.id, ProviderInput("Mistral", null, "sk-new"), Actor.User)) shouldBe existing

        setup.secrets[checkNotNull(existing.apiKey)] shouldBe SecretValue("sk-new")
        val entry = setup.entries.single()
        entry.change.description shouldBe "Changed AI provider and replaced its API key"
        entry.change.fieldChanges.shouldBeEmpty()
    }

    @Test
    fun `a new base URL is stored and recorded`() {
        val existing = setup.provider()

        val updated =
            created(update.execute(existing.id, ProviderInput("Ollama", "http://gpu-box:11434/v1", null), Actor.User))

        updated.baseUri shouldBe URI("http://gpu-box:11434/v1")
        setup.entries
            .single()
            .change.fieldChanges shouldBe
            listOf(FieldChange("baseUrl", "http://localhost:11434/v1", "http://gpu-box:11434/v1"))
    }

    @Test
    fun `an unchanged provider writes nothing, an unknown one is not found`() {
        val existing = setup.provider()

        update.execute(existing.id, ProviderInput("Ollama", "http://localhost:11434/v1", null), Actor.User) shouldBe
            SetupResult.Success(existing)
        setup.entries.shouldBeEmpty()
        update.execute(ProviderId(UUID.randomUUID()), ProviderInput("X", null, null), Actor.User) shouldBe
            SetupResult.NotFound
    }
}

/** Every actor that must not change the provider setup (#20 security review). */
object SetupActors {
    @JvmStatic
    fun othersThanTheUser(): List<Actor> =
        listOf(Actor.Ai, Actor.ExternalClient("Claude Desktop"), Actor.Scanner("BA"), Actor.System("job"))
}
