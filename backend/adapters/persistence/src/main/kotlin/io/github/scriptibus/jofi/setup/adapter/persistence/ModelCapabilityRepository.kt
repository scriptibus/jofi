// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.persistence

import io.github.scriptibus.jofi.setup.application.port.ModelCapabilityPort
import io.github.scriptibus.jofi.setup.domain.Capability
import io.github.scriptibus.jofi.setup.domain.CapabilityName
import io.github.scriptibus.jofi.setup.domain.CapabilitySource
import io.github.scriptibus.jofi.setup.domain.ModelCapabilities
import io.github.scriptibus.jofi.setup.domain.ModelCapabilityProfile
import io.github.scriptibus.jofi.setup.domain.ModelName
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.SetupStoreResult
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.AI_MODEL_CAPABILITY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.tables.records.AiModelCapabilityRecord
import org.jooq.DSLContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * What each provider's models can do (`ai_model_capability`): feature capabilities as a text array
 * of [CapabilityName]s, the context size in its own column. The AI gateway reads it per call.
 */
@Component
class ModelCapabilityRepository(
    private val dsl: DSLContext,
) : ModelCapabilityPort {
    override fun find(
        provider: ProviderId,
        model: ModelName,
    ): SetupStoreResult<ModelCapabilityProfile> =
        storeCall(log, "find") {
            dsl
                .fetchOne(
                    AI_MODEL_CAPABILITY,
                    AI_MODEL_CAPABILITY.PROVIDER_ID.eq(provider.value).and(AI_MODEL_CAPABILITY.MODEL.eq(model.value)),
                )?.let(::toDomain)
                .foundOrNotFound()
        }

    override fun findByProvider(provider: ProviderId): SetupStoreResult<List<ModelCapabilityProfile>> =
        storeCall(log, "findByProvider") {
            SetupStoreResult.Success(
                dsl
                    .selectFrom(AI_MODEL_CAPABILITY)
                    .where(AI_MODEL_CAPABILITY.PROVIDER_ID.eq(provider.value))
                    .orderBy(AI_MODEL_CAPABILITY.MODEL)
                    .fetch()
                    .map(::toDomain),
            )
        }

    override fun save(profile: ModelCapabilityProfile): SetupStoreResult<Unit> =
        storeCall(log, "save") {
            val record = toRecord(profile)
            dsl
                .insertInto(AI_MODEL_CAPABILITY)
                .set(record)
                .onConflict(AI_MODEL_CAPABILITY.PROVIDER_ID, AI_MODEL_CAPABILITY.MODEL)
                .doUpdate()
                .set(record)
                .execute()
            SetupStoreResult.Success(Unit)
        }

    private fun toDomain(record: AiModelCapabilityRecord): ModelCapabilityProfile {
        val features = record.capabilities.filterNotNull().map { CapabilityName.valueOf(it).capability }
        val context = record.contextWindowTokens?.let { Capability.ContextSize(it) }
        return ModelCapabilityProfile(
            provider = ProviderId(record.providerId),
            model = ModelName(record.model),
            capabilities = ModelCapabilities((features + listOfNotNull(context)).toSet()),
            source = CapabilitySource.valueOf(record.source),
            updatedAt = record.updatedAt.toInstant(),
        )
    }

    private fun toRecord(profile: ModelCapabilityProfile): AiModelCapabilityRecord =
        AiModelCapabilityRecord().apply {
            providerId = profile.provider.value
            model = profile.model.value
            capabilities =
                profile.capabilities.supported
                    .mapNotNull { CapabilityName.of(it)?.name }
                    .sorted()
                    .toTypedArray()
            contextWindowTokens = profile.capabilities.contextSize?.tokens
            source = profile.source.name
            updatedAt = profile.updatedAt.toUtc()
        }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(ModelCapabilityRepository::class.java)
    }
}
