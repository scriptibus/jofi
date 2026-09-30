// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.web

import io.github.scriptibus.jofi.setup.domain.Capability
import io.github.scriptibus.jofi.setup.domain.CapabilityInput
import io.github.scriptibus.jofi.setup.domain.CapabilityName
import io.github.scriptibus.jofi.setup.domain.CapabilitySource
import io.github.scriptibus.jofi.setup.domain.ModelCapabilityProfile
import io.github.scriptibus.jofi.setup.domain.ProviderConfig
import io.github.scriptibus.jofi.setup.domain.ProviderInput
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.setup.domain.TaskAssignmentView
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import java.time.Instant
import java.util.UUID

/** Which AI provider an entry configures (the API's copy of `ProviderKind`). */
enum class AiProviderType {
    ANTHROPIC,
    OPENAI,
    GEMINI,
    MISTRAL,
    OPENAI_COMPATIBLE,
    ;

    fun toDomain(): ProviderKind = ProviderKind.valueOf(name)

    companion object {
        fun from(kind: ProviderKind): AiProviderType = valueOf(kind.name)
    }
}

/** An AI task that gets its own model (the API's copy of `AiTask`). */
enum class AiTaskType {
    SCANNER_PRE_SCORING,
    CLASSIFICATION,
    LANGUAGE_TONE_DETECTION,
    EXTRACTION,
    KNOWLEDGE_INTERVIEW,
    DOCUMENT_GENERATION,
    INTERVIEW_TRAINING,
    CHAT,
    EMBEDDING,
    SPEECH_TO_TEXT,
    TEXT_TO_SPEECH,
    ;

    fun toDomain(): AiTask = AiTask.valueOf(name)

    companion object {
        fun from(task: AiTask): AiTaskType = valueOf(task.name)
    }
}

/** A feature a model offers (the API's copy of `CapabilityName`); the context size has its own field. */
enum class ModelFeature {
    TOOL_USE,
    STREAMING,
    SPEECH_TO_TEXT,
    TEXT_TO_SPEECH,
    EMBEDDING,
    ;

    fun toDomain(): CapabilityName = CapabilityName.valueOf(name)

    companion object {
        fun from(name: CapabilityName): ModelFeature = valueOf(name.name)
    }
}

/** Where a model's capabilities come from: the provider's listing, or the user's correction. */
enum class CapabilityOrigin { DETECTED, USER }

/** Body of `POST /api/setup/providers`. [toString] never prints the key. */
data class CreateProviderRequest(
    val kind: AiProviderType,
    val displayName: String,
    /** Only for `OPENAI_COMPATIBLE`, e.g. `http://localhost:11434/v1`; no credentials in it. */
    val baseUrl: String? = null,
    /** Stored encrypted; never returned. Required for every kind but `OPENAI_COMPATIBLE`. */
    val apiKey: String? = null,
) {
    fun toInput(): ProviderInput = ProviderInput(displayName, baseUrl, apiKey)

    override fun toString(): String = "CreateProviderRequest(kind=$kind, displayName=$displayName, baseUrl=$baseUrl)"
}

/** Body of `PUT /api/setup/providers/{id}`: replaces name and base URL; a key replaces the stored one. */
data class UpdateProviderRequest(
    val displayName: String,
    val baseUrl: String? = null,
    /** Absent or blank keeps the stored key. */
    val apiKey: String? = null,
) {
    fun toInput(): ProviderInput = ProviderInput(displayName, baseUrl, apiKey)

    override fun toString(): String = "UpdateProviderRequest(displayName=$displayName, baseUrl=$baseUrl)"
}

/** A configured provider. The key itself is never returned, only whether one is stored. */
data class ProviderResponse(
    val id: UUID,
    val kind: AiProviderType,
    val displayName: String,
    val baseUrl: String?,
    val apiKeySet: Boolean,
) {
    companion object {
        fun from(config: ProviderConfig): ProviderResponse =
            ProviderResponse(
                config.id.value,
                AiProviderType.from(config.kind),
                config.displayName,
                config.baseUri?.toString(),
                config.apiKey != null,
            )
    }
}

/** What a model of a provider can do. */
data class ModelResponse(
    val model: String,
    val features: List<ModelFeature>,
    val contextWindowTokens: Int?,
    val origin: CapabilityOrigin,
    val updatedAt: Instant,
) {
    companion object {
        fun from(profile: ModelCapabilityProfile): ModelResponse =
            ModelResponse(
                profile.model.value,
                featuresOf(profile.capabilities.supported),
                profile.capabilities.contextSize?.tokens,
                if (profile.source == CapabilitySource.USER) CapabilityOrigin.USER else CapabilityOrigin.DETECTED,
                profile.updatedAt,
            )
    }
}

/** Body of `PUT /api/setup/providers/{id}/models`: what the user says [model] can do. */
data class CorrectModelCapabilitiesRequest(
    val model: String,
    val features: List<ModelFeature>,
    val contextWindowTokens: Int? = null,
) {
    fun toInput(): CapabilityInput = CapabilityInput(model, features.map { it.toDomain() }.toSet(), contextWindowTokens)
}

/** Body of `PUT /api/setup/assignments/{task}`. */
data class AssignTaskModelRequest(
    val providerId: UUID,
    val model: String,
)

/** What a task needs from its model: [features] plus a context window of at least [minContextWindowTokens]. */
data class CapabilityNeedsResponse(
    val features: List<ModelFeature>,
    val minContextWindowTokens: Int?,
) {
    companion object {
        fun from(capabilities: Collection<Capability>): CapabilityNeedsResponse =
            CapabilityNeedsResponse(
                featuresOf(capabilities),
                capabilities.filterIsInstance<Capability.ContextSize>().maxOfOrNull { it.tokens },
            )
    }
}

/**
 * A task, its model ([providerId] and [model] absent while none is assigned), what it needs, and what
 * the assigned model lacks ([missing]; empty when it fits). A lacking model is allowed, only warned about.
 */
data class TaskAssignmentResponse(
    val task: AiTaskType,
    val providerId: UUID?,
    val model: String?,
    val needs: CapabilityNeedsResponse,
    val missing: CapabilityNeedsResponse,
) {
    companion object {
        fun from(view: TaskAssignmentView): TaskAssignmentResponse =
            TaskAssignmentResponse(
                AiTaskType.from(view.task),
                view.assignment?.provider?.value,
                view.assignment?.model?.value,
                CapabilityNeedsResponse.from(view.required),
                CapabilityNeedsResponse.from(view.warnings.map { it.missing }),
            )
    }
}

private fun featuresOf(capabilities: Collection<Capability>): List<ModelFeature> =
    capabilities.mapNotNull { CapabilityName.of(it) }.sorted().map(ModelFeature::from)
