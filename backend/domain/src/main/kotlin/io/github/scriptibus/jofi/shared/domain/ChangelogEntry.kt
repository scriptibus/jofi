// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain

import java.time.Instant

/**
 * One immutable line of the audit trail: who ([actor]) changed which entity ([entity]), when
 * ([occurredAt]), what changed ([change]) and optionally why ([reason]).
 *
 * The store keeps [occurredAt] with microsecond precision (PostgreSQL `timestamptz`).
 */
data class ChangelogEntry(
    val entity: EntityRef,
    val actor: Actor,
    val occurredAt: Instant,
    val change: ChangeSummary,
    val reason: String? = null,
) {
    init {
        require(reason == null || reason.isNotBlank()) { "A reason, when given, must not be blank" }
    }
}

/** Identifies the changed entity across bounded contexts, e.g. `application` / `42`. */
data class EntityRef(
    val type: String,
    val id: String,
) {
    init {
        require(type.isNotBlank()) { "An entity type must not be blank" }
        require(id.isNotBlank()) { "An entity id must not be blank" }
    }
}

/** A short human-readable [description] of the change plus the fields it touched. */
data class ChangeSummary(
    val description: String,
    val fieldChanges: List<FieldChange> = emptyList(),
) {
    init {
        require(description.isNotBlank()) { "A change description must not be blank" }
    }
}

/**
 * One field's value [before] and [after] the change, rendered as text. `null` means the field had
 * no value (e.g. [before] on creation, [after] on removal).
 */
data class FieldChange(
    val field: String,
    val before: String?,
    val after: String?,
) {
    init {
        require(field.isNotBlank()) { "A field name must not be blank" }
        require(before != after) { "A field change must change the value" }
    }
}
