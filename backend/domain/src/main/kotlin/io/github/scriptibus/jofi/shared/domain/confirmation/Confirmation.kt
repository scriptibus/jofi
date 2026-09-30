// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.confirmation

import io.github.scriptibus.jofi.shared.domain.Actor

/**
 * Who asks for a destructive or outward-facing action, and through which session or client
 * connection. A confirmation only counts when it comes back from the same actor and session.
 * [session] is a credential (a session id or client token id), so [toString] leaves it out.
 */
data class ConfirmationRequester(
    val actor: Actor,
    val session: String,
) {
    init {
        require(session.isNotBlank()) { "A confirmation needs the session it is bound to" }
    }

    override fun toString(): String = "ConfirmationRequester(actor=$actor)"
}

/**
 * What executing would change, as structured data the client renders in the user's language (the
 * server sends no prose): the [kind] of thing (`application`), its display [name] and [counts] of
 * what goes with it (`documents` -> 3). The feature derives it from the same read its mutation acts
 * on, inside one transaction (ADR-0039).
 */
data class ConfirmationEffect(
    val kind: String,
    val name: String,
    val counts: Map<String, Int> = emptyMap(),
) {
    init {
        require(kind.isNotBlank()) { "A confirmation effect needs the kind of thing it affects" }
        require(counts.keys.none { it.isBlank() } && counts.values.all { it >= 0 }) {
            "Effect counts need names and must not be negative"
        }
    }
}

/**
 * Exactly what would be executed: the [operation] (`<context>.<verb>`, e.g. `applications.delete`),
 * its targets and the [effect]. Targets are concrete ids the server resolved, never filters or
 * queries; they are kept sorted and without duplicates, so the same set always binds the same way.
 * If anything differs at the second step, for instance because the target changed in between, the
 * confirmation no longer matches.
 */
class ConfirmableAction(
    val operation: String,
    targets: Collection<String>,
    val effect: ConfirmationEffect,
) {
    val targets: List<String> = targets.toSortedSet().toList()

    init {
        require(operation.isNotBlank()) { "A confirmable action needs an operation" }
        require(this.targets.isNotEmpty() && this.targets.none { it.isBlank() }) {
            "A confirmable action needs its targets"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is ConfirmableAction && operation == other.operation && targets == other.targets && effect == other.effect

    override fun hashCode(): Int = listOf(operation, targets, effect).hashCode()

    override fun toString(): String = "ConfirmableAction(operation=$operation, targets=$targets, effect=$effect)"
}

/** The unguessable, single-use proof of the first step. Never logged, so [toString] hides it. */
@JvmInline
value class ConfirmationToken(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "A confirmation token must not be blank" }
    }

    override fun toString(): String = "ConfirmationToken(redacted)"
}

/** One call of a destructive operation; [token] is absent on the first step. */
data class ConfirmationRequest(
    val requester: ConfirmationRequester,
    val action: ConfirmableAction,
    val token: ConfirmationToken?,
)
