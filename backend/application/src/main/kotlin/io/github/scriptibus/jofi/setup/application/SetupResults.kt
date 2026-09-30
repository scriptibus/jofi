// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.application

import io.github.scriptibus.jofi.setup.domain.SetupResult
import io.github.scriptibus.jofi.setup.domain.SetupStoreResult
import io.github.scriptibus.jofi.setup.domain.SetupValidation
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor

// How setup use cases chain their steps: each step answers a SetupResult, and the first failure ends the chain.

/** Runs [change] only for the user; every other actor is [SetupResult.Forbidden]. */
internal inline fun <T> asUser(
    actor: Actor,
    change: () -> SetupResult<T>,
): SetupResult<T> = if (actor.mayChangeSetup()) change() else SetupResult.Forbidden

/** Continues with [next] on success; a failure passes through unchanged. */
internal inline fun <T, R> SetupResult<T>.then(next: (T) -> SetupResult<R>): SetupResult<R> =
    when (this) {
        is SetupResult.Success -> next(value)
        is SetupResult.Failure -> this
    }

/** A validated input as a setup result. */
internal fun <T> SetupValidation<T>.toSetupResult(): SetupResult<T> =
    when (this) {
        is SetupValidation.Valid -> SetupResult.Success(value)
        is SetupValidation.Invalid -> SetupResult.Invalid(violations)
    }

/** The stored value, null if there is none, or the store's failure. */
internal fun <T> SetupStoreResult<T>.orNull(): SetupResult<T?> =
    if (this == SetupStoreResult.NotFound) SetupResult.Success(null) else toSetupResult()

/** Runs [work] in one transaction that commits only on [SetupResult.Success]. */
internal fun <T> TransactionPort.whenSuccessful(work: () -> SetupResult<T>): SetupResult<T> =
    inTransaction({ it is SetupResult.Success }, work)

/** The store's answer as a setup result. */
internal fun <T> SetupStoreResult<T>.toSetupResult(): SetupResult<T> =
    if (this is SetupStoreResult.Success) SetupResult.Success(value) else asFailure()

/** A store answer other than success as a use-case failure. */
internal fun SetupStoreResult<*>.asFailure(): SetupResult.Failure =
    when (this) {
        SetupStoreResult.NotFound -> {
            SetupResult.NotFound
        }

        SetupStoreResult.InUse -> {
            SetupResult.InUse
        }

        is SetupStoreResult.StorageFailure -> {
            SetupResult.StorageFailure(operation)
        }

        is SetupStoreResult.Success, SetupStoreResult.NotConfirmed -> {
            SetupResult.StorageFailure(
                "unexpected store answer",
            )
        }
    }
