// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.persistence

import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.tables.records.ChangelogEntryRecord
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ChangeSummary
import io.github.scriptibus.jofi.shared.domain.ChangelogEntry
import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.github.scriptibus.jofi.shared.domain.FieldChange
import org.jooq.JSONB
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.ZoneOffset

/** Maps between [ChangelogEntry] and the `changelog_entry` row; field changes live in a JSONB array. */
internal object ChangelogRecordMapper {
    private val json = jacksonObjectMapper()

    fun toRecord(entry: ChangelogEntry): ChangelogEntryRecord =
        ChangelogEntryRecord().apply {
            entityType = entry.entity.type
            entityId = entry.entity.id
            actorKind = ActorColumns.kindOf(entry.actor)
            actorName = ActorColumns.nameOf(entry.actor)
            occurredAt = entry.occurredAt.atOffset(ZoneOffset.UTC)
            description = entry.change.description
            fieldChanges = JSONB.valueOf(json.writeValueAsString(entry.change.fieldChanges.map(::toJson)))
            reason = entry.reason
        }

    fun toDomain(record: ChangelogEntryRecord): ChangelogEntry =
        ChangelogEntry(
            entity = EntityRef(type = record.entityType, id = record.entityId),
            actor = ActorColumns.toActor(record.actorKind, record.actorName),
            occurredAt = record.occurredAt.toInstant(),
            change =
                ChangeSummary(
                    description = record.description,
                    fieldChanges = json.readValue<List<FieldChangeJson>>(record.fieldChanges.data()).map(::toDomain),
                ),
            reason = record.reason,
        )

    private fun toJson(change: FieldChange) = FieldChangeJson(change.field, change.before, change.after)

    private fun toDomain(change: FieldChangeJson) = FieldChange(change.field, change.before, change.after)

    /** Storage shape of one field change; decoupled from the domain type on purpose. */
    private data class FieldChangeJson(
        val field: String,
        val before: String?,
        val after: String?,
    )
}

/** The `actor_kind` / `actor_name` column pair. Kinds are stored as stable strings. */
internal object ActorColumns {
    private const val USER = "USER"
    private const val AI = "AI"
    private const val SCANNER = "SCANNER"
    private const val EXTERNAL_CLIENT = "EXTERNAL_CLIENT"
    private const val SYSTEM = "SYSTEM"

    fun kindOf(actor: Actor): String =
        when (actor) {
            Actor.User -> USER
            Actor.Ai -> AI
            is Actor.Scanner -> SCANNER
            is Actor.ExternalClient -> EXTERNAL_CLIENT
            is Actor.System -> SYSTEM
        }

    fun nameOf(actor: Actor): String? =
        when (actor) {
            Actor.User, Actor.Ai -> null
            is Actor.Scanner -> actor.name
            is Actor.ExternalClient -> actor.name
            is Actor.System -> actor.name
        }

    fun toActor(
        kind: String,
        name: String?,
    ): Actor =
        when (kind) {
            USER -> Actor.User
            AI -> Actor.Ai
            SCANNER -> Actor.Scanner(requireNotNull(name))
            EXTERNAL_CLIENT -> Actor.ExternalClient(requireNotNull(name))
            SYSTEM -> Actor.System(requireNotNull(name))
            else -> error("Unknown actor kind in changelog_entry: $kind")
        }
}
