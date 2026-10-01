// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.persistence

import io.github.scriptibus.jofi.setup.application.port.ModelPricePort
import io.github.scriptibus.jofi.setup.domain.ModelName
import io.github.scriptibus.jofi.setup.domain.ModelPriceOverride
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.SetupStoreResult
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.AI_MODEL_PRICE_OVERRIDE
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.tables.records.AiModelPriceOverrideRecord
import io.github.scriptibus.jofi.shared.adapter.persistence.violatedConstraint
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * The prices the user gave models of OpenAI-compatible providers (`ai_model_price_override`). The AI
 * gateway reads it for every call it meters; a row prices later calls only, the meter keeps what it recorded.
 */
@Component
class ModelPriceRepository(
    private val dsl: DSLContext,
) : ModelPricePort {
    override fun find(
        provider: ProviderId,
        model: ModelName,
    ): SetupStoreResult<ModelPriceOverride> =
        storeCall(log, "find") {
            dsl
                .fetchOne(
                    AI_MODEL_PRICE_OVERRIDE,
                    AI_MODEL_PRICE_OVERRIDE.PROVIDER_ID
                        .eq(provider.value)
                        .and(AI_MODEL_PRICE_OVERRIDE.MODEL.eq(model.value)),
                )?.let(::toDomain)
                .foundOrNotFound()
        }

    override fun findByProvider(provider: ProviderId): SetupStoreResult<List<ModelPriceOverride>> =
        storeCall(log, "findByProvider") {
            SetupStoreResult.Success(
                dsl
                    .selectFrom(AI_MODEL_PRICE_OVERRIDE)
                    .where(AI_MODEL_PRICE_OVERRIDE.PROVIDER_ID.eq(provider.value))
                    .orderBy(AI_MODEL_PRICE_OVERRIDE.MODEL)
                    .fetch()
                    .map(::toDomain),
            )
        }

    override fun save(price: ModelPriceOverride): SetupStoreResult<Unit> =
        storeCall(log, "save") {
            val record = toRecord(price)
            try {
                dsl
                    .insertInto(AI_MODEL_PRICE_OVERRIDE)
                    .set(record)
                    .onConflict(AI_MODEL_PRICE_OVERRIDE.PROVIDER_ID, AI_MODEL_PRICE_OVERRIDE.MODEL)
                    .doUpdate()
                    .set(record)
                    .execute()
                SetupStoreResult.Success(Unit)
            } catch (exception: DataAccessException) {
                // A provider deleted between the use case's check and this write must not be brought back.
                if (exception.violatedConstraint() == PROVIDER_FK) SetupStoreResult.NotFound else throw exception
            }
        }

    override fun clear(
        provider: ProviderId,
        model: ModelName,
    ): SetupStoreResult<Unit> =
        storeCall(log, "clear") {
            dsl
                .deleteFrom(AI_MODEL_PRICE_OVERRIDE)
                .where(
                    AI_MODEL_PRICE_OVERRIDE.PROVIDER_ID
                        .eq(provider.value)
                        .and(AI_MODEL_PRICE_OVERRIDE.MODEL.eq(model.value)),
                ).execute()
            SetupStoreResult.Success(Unit)
        }

    private fun toDomain(record: AiModelPriceOverrideRecord): ModelPriceOverride =
        ModelPriceOverride(
            provider = ProviderId(record.providerId),
            model = ModelName(record.model),
            inputMicrosPerMillion = record.inputMicrosPerMillion,
            outputMicrosPerMillion = record.outputMicrosPerMillion,
            updatedAt = record.updatedAt.toInstant(),
        )

    private fun toRecord(price: ModelPriceOverride): AiModelPriceOverrideRecord =
        AiModelPriceOverrideRecord().apply {
            providerId = price.provider.value
            model = price.model.value
            inputMicrosPerMillion = price.inputMicrosPerMillion
            outputMicrosPerMillion = price.outputMicrosPerMillion
            updatedAt = price.updatedAt.toUtc()
        }

    private companion object {
        const val PROVIDER_FK = "ai_model_price_override_provider_fk"
        val log: Logger = LoggerFactory.getLogger(ModelPriceRepository::class.java)
    }
}
