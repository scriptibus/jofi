// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.confirmation

import java.time.Instant

/** The outcome of the confirmation gate. Only [Confirmed] lets the operation run. */
sealed interface ConfirmationResult {
    /**
     * Proof that the user confirmed exactly [action]. Its constructor is internal to the domain and
     * only `PendingConfirmation.check` calls it (architecture test), so nothing but the gate can mint
     * one. Destructive and outward-facing port methods take it as a parameter, so they cannot be
     * called without having passed the gate.
     */
    class Confirmed internal constructor(
        val action: ConfirmableAction,
    ) : ConfirmationResult {
        /** Whether this proof covers running [operation] on [target]; adapters check it before acting. */
        fun covers(
            operation: String,
            target: String,
        ): Boolean = action.operation == operation && target in action.targets

        override fun toString(): String = "Confirmed(action=$action)"
    }

    /** Anything that must not execute; features pass it on to their caller unchanged. */
    sealed interface Unconfirmed : ConfirmationResult

    /** First step: nothing ran; show [action] to the user and repeat the call with [token]. */
    data class Required(
        val token: ConfirmationToken,
        val expiresAt: Instant,
        val action: ConfirmableAction,
    ) : Unconfirmed

    /** The token does not confirm this call; nothing ran and the token is spent. */
    data class Rejected(
        val reason: ConfirmationRejection,
    ) : Unconfirmed
}

/** Why a token was refused. */
enum class ConfirmationRejection {
    /** Never issued, already used, or dropped from the bounded store. */
    UNKNOWN,

    /** Issued, but its time ran out. */
    EXPIRED,

    /** Issued for another actor, session, operation, target or effect. */
    MISMATCH,
}
