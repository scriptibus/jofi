// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.persistence

import io.github.scriptibus.jofi.setup.application.port.ProviderConfigPort
import io.github.scriptibus.jofi.setup.domain.ProviderConfig
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.setup.domain.SetupStoreResult
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.AI_PROVIDER_CONFIG
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.tables.records.AiProviderConfigRecord
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.shared.domain.secret.SecretId
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI

/**
 * The configured AI providers (`ai_provider_config`). Holds the key's secret id, never the key.
 * Read by the AI gateway and the transport's allowlist; the setup use cases write it.
 */
@Component
class ProviderConfigRepository(
    private val dsl: DSLContext,
) : ProviderConfigPort {
    override fun findAll(): SetupStoreResult<List<ProviderConfig>> =
        storeCall(log, "findAll") {
            SetupStoreResult.Success(
                dsl
                    .selectFrom(AI_PROVIDER_CONFIG)
                    .orderBy(AI_PROVIDER_CONFIG.ID)
                    .fetch()
                    .map(::toDomain),
            )
        }

    override fun findById(id: ProviderId): SetupStoreResult<ProviderConfig> =
        storeCall(log, "findById") {
            dsl.fetchOne(AI_PROVIDER_CONFIG, AI_PROVIDER_CONFIG.ID.eq(id.value))?.let(::toDomain).foundOrNotFound()
        }

    override fun save(config: ProviderConfig): SetupStoreResult<Unit> =
        storeCall(log, "save") {
            val record = toRecord(config)
            dsl
                .insertInto(AI_PROVIDER_CONFIG)
                .set(record)
                .onConflict(AI_PROVIDER_CONFIG.ID)
                .doUpdate()
                .set(record)
                .execute()
            SetupStoreResult.Success(Unit)
        }

    override fun delete(
        id: ProviderId,
        proof: ConfirmationResult.Confirmed,
    ): SetupStoreResult<Unit> {
        if (!proof.covers(ProviderId.DELETE_OPERATION, id.value.toString())) return SetupStoreResult.NotConfirmed
        return storeCall(log, "delete") {
            try {
                val deleted = dsl.deleteFrom(AI_PROVIDER_CONFIG).where(AI_PROVIDER_CONFIG.ID.eq(id.value)).execute()
                if (deleted == 0) SetupStoreResult.NotFound else SetupStoreResult.Success(Unit)
            } catch (exception: DataAccessException) {
                if (exception.isStillReferenced()) SetupStoreResult.InUse else throw exception
            }
        }
    }

    private fun toDomain(record: AiProviderConfigRecord): ProviderConfig =
        ProviderConfig(
            id = ProviderId(record.id),
            displayName = record.displayName,
            kind = ProviderKind.valueOf(record.kind),
            apiKey = record.apiKeySecretId?.let(::SecretId),
            baseUri = record.baseUrl?.let(::URI),
        )

    private fun toRecord(config: ProviderConfig): AiProviderConfigRecord =
        AiProviderConfigRecord().apply {
            id = config.id.value
            displayName = config.displayName
            kind = config.kind.name
            apiKeySecretId = config.apiKey?.value
            baseUrl = config.baseUri?.toString()
        }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(ProviderConfigRepository::class.java)
    }
}
