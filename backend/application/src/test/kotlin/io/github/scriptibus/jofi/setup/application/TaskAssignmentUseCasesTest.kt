// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.application

import io.github.scriptibus.jofi.setup.application.SetupFixtures.Companion.CLOCK
import io.github.scriptibus.jofi.setup.application.SetupFixtures.Companion.NOW
import io.github.scriptibus.jofi.setup.application.SetupFixtures.Companion.TOOLS_AND_STREAMING
import io.github.scriptibus.jofi.setup.domain.Capability
import io.github.scriptibus.jofi.setup.domain.CapabilityCheck
import io.github.scriptibus.jofi.setup.domain.CapabilitySource
import io.github.scriptibus.jofi.setup.domain.CapabilityWarning
import io.github.scriptibus.jofi.setup.domain.ModelAssignment
import io.github.scriptibus.jofi.setup.domain.ModelCapabilities
import io.github.scriptibus.jofi.setup.domain.ModelCapabilityProfile
import io.github.scriptibus.jofi.setup.domain.ModelName
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.SetupField
import io.github.scriptibus.jofi.setup.domain.SetupResult
import io.github.scriptibus.jofi.setup.domain.SetupViolation
import io.github.scriptibus.jofi.setup.domain.SetupViolationKind
import io.github.scriptibus.jofi.setup.domain.TaskAssignmentView
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.FieldChange
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.UUID

class TaskAssignmentUseCasesTest {
    private val setup = SetupFixtures()
    private val assign =
        AssignTaskModelUseCase(
            setup.providerPort,
            setup.assignmentPort,
            setup.profilePort,
            setup.catalog,
            setup.changelog,
            setup.transactions,
            CLOCK,
        )
    private val list =
        ListTaskAssignmentsUseCase(setup.assignmentPort, setup.providerPort, setup.profilePort, setup.catalog)
    private val provider = setup.provider()
    private val small = ModelName("llama3.2:1b")

    private fun assigned(result: SetupResult<TaskAssignmentView>): TaskAssignmentView =
        result.shouldBeInstanceOf<SetupResult.Success<TaskAssignmentView>>().value

    @Test
    fun `a model lacking what the task needs is assigned with a warning per missing capability`() {
        setup.known = mapOf(small to ModelCapabilities(setOf(Capability.Streaming, Capability.ContextSize(8_192))))

        val view = assigned(assign.execute(AiTask.CHAT, provider.id, " llama3.2:1b ", Actor.User))

        view.assignment shouldBe ModelAssignment(AiTask.CHAT, provider.id, small)
        view.required shouldBe CapabilityCheck.requiredFor(AiTask.CHAT)
        view.warnings shouldContainExactlyInAnyOrder
            listOf(
                CapabilityWarning(AiTask.CHAT, Capability.ToolUse),
                CapabilityWarning(AiTask.CHAT, Capability.ContextSize(CapabilityCheck.LONG_CONTEXT_TOKENS)),
            )
        setup.assignments[AiTask.CHAT] shouldBe view.assignment
        val entry = setup.entries.single()
        entry.actor shouldBe Actor.User
        entry.entity shouldBe ModelAssignment.entityRef(AiTask.CHAT)
        entry.occurredAt shouldBe NOW
        entry.change.fieldChanges shouldBe
            listOf(
                FieldChange("provider", null, provider.id.value.toString()),
                FieldChange("model", null, "llama3.2:1b"),
            )
    }

    @Test
    fun `a stored profile wins over the catalog's table`() {
        setup.known = mapOf(small to ModelCapabilities.NONE)
        setup.profiles[provider.id to small] =
            ModelCapabilityProfile(provider.id, small, TOOLS_AND_STREAMING, CapabilitySource.USER, NOW)

        assigned(assign.execute(AiTask.CHAT, provider.id, small.value, Actor.User)).warnings.shouldBeEmpty()
    }

    @Test
    fun `an unchanged assignment writes nothing, a new model is recorded as a change`() {
        assign.execute(AiTask.EXTRACTION, provider.id, "qwen3", Actor.User)
        assign.execute(AiTask.EXTRACTION, provider.id, "qwen3", Actor.User)
        setup.entries.size shouldBe 1

        assign.execute(AiTask.EXTRACTION, provider.id, "qwen3:14b", Actor.User)

        setup.entries
            .last()
            .change.fieldChanges shouldBe listOf(FieldChange("model", "qwen3", "qwen3:14b"))
    }

    @Test
    fun `the model must be named and the provider must exist`() {
        assign.execute(AiTask.CHAT, provider.id, " ", Actor.User) shouldBe
            SetupResult.Invalid(listOf(SetupViolation(SetupField.MODEL, SetupViolationKind.REQUIRED)))
        assign.execute(AiTask.CHAT, ProviderId(UUID.randomUUID()), "gpt-5", Actor.User) shouldBe SetupResult.NotFound
        setup.assignments.shouldBeEmpty()
    }

    @Test
    fun `the list shows every task, with warnings only for assigned ones`() {
        assign.execute(AiTask.EMBEDDING, provider.id, "nomic-embed-text", Actor.User)

        val views = list.execute().shouldBeInstanceOf<SetupResult.Success<List<TaskAssignmentView>>>().value

        views.map { it.task } shouldBe AiTask.entries
        views.single { it.task == AiTask.EMBEDDING }.warnings shouldBe
            listOf(CapabilityWarning(AiTask.EMBEDDING, Capability.Embedding))
        views.single { it.task == AiTask.CHAT }.let {
            it.assignment shouldBe null
            it.warnings.shouldBeEmpty()
            it.required shouldBe CapabilityCheck.requiredFor(AiTask.CHAT)
        }
    }

    @ParameterizedTest
    @MethodSource("io.github.scriptibus.jofi.setup.application.SetupActors#othersThanTheUser")
    fun `only the user may assign models`(actor: Actor) {
        assign.execute(AiTask.CHAT, provider.id, "gpt-5", actor) shouldBe SetupResult.Forbidden
        setup.assignments.shouldBeEmpty()
    }
}
