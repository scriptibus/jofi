// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationField
import io.github.scriptibus.jofi.applications.domain.ApplicationProblem
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.ApplicationValidation
import io.github.scriptibus.jofi.applications.domain.ApplicationViolation
import io.github.scriptibus.jofi.applications.domain.SourceId
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

// How the application use cases chain their steps (as `CompanySteps`): an ApplicationResult per step,
// the first failure ends the chain.

/** "Now" at the precision of `timestamptz` (ADR-0041). */
internal fun Clock.storedNow(): Instant = instant().truncatedTo(ChronoUnit.MICROS)

/** Continues with [next] on success; a failure passes through unchanged. */
internal inline fun <T, R> ApplicationResult<T>.then(next: (T) -> ApplicationResult<R>): ApplicationResult<R> =
    when (this) {
        is ApplicationResult.Success -> next(value)
        is ApplicationResult.Failure -> this
    }

/** Runs [work] in one transaction that commits only on [ApplicationResult.Success]. */
internal fun <T> TransactionPort.inApplicationTransaction(work: () -> ApplicationResult<T>): ApplicationResult<T> =
    inTransaction({ it is ApplicationResult.Success }, work)

internal fun <T> ApplicationValidation<T>.toResult(): ApplicationResult<T> =
    when (this) {
        is ApplicationValidation.Valid -> ApplicationResult.Success(value)
        is ApplicationValidation.Invalid -> ApplicationResult.Invalid(violations)
    }

internal fun <T> ApplicationStoreResult<T>.toResult(): ApplicationResult<T> =
    when (this) {
        is ApplicationStoreResult.Success -> {
            ApplicationResult.Success(value)
        }

        ApplicationStoreResult.NotFound -> {
            ApplicationResult.NotFound
        }

        ApplicationStoreResult.VersionConflict -> {
            ApplicationResult.VersionConflict
        }

        // `application_company_fk`: the input named a company that does not exist (any more).
        ApplicationStoreResult.CompanyNotFound -> {
            notFound(ApplicationField.COMPANY)
        }

        // `application_contact_contact_fk`: a linked contact does not exist (any more).
        ApplicationStoreResult.ContactNotFound -> {
            notFound(ApplicationField.CONTACTS)
        }

        // Only the source port answers it (#96): the application has as many sources as it may.
        ApplicationStoreResult.SourceLimitReached -> {
            ApplicationResult.Invalid(
                listOf(ApplicationViolation(ApplicationField.SOURCES, ApplicationProblem.TOO_MANY)),
            )
        }

        // Only a proof for another target gets here, a bug of the use case; nothing was deleted.
        ApplicationStoreResult.NotConfirmed -> {
            ApplicationResult.StorageFailure("delete without matching proof")
        }

        is ApplicationStoreResult.StorageFailure -> {
            ApplicationResult.StorageFailure(operation)
        }
    }

private fun notFound(field: ApplicationField): ApplicationResult.Invalid =
    ApplicationResult.Invalid(listOf(ApplicationViolation(field, ApplicationProblem.NOT_FOUND)))

/** The application if the caller based its change on its current version, else [ApplicationResult.VersionConflict]. */
internal fun Application.basedOn(version: Long): ApplicationResult<Application> =
    if (this.version == version) ApplicationResult.Success(this) else ApplicationResult.VersionConflict

/** [this] if [done] holds, else a storage failure of [operation] (the transaction then rolls back). */
internal fun <T> T.applicationIf(
    done: Boolean,
    operation: String,
): ApplicationResult<T> = if (done) ApplicationResult.Success(this) else ApplicationResult.StorageFailure(operation)

/** The application if [source] is one of its sources, else [ApplicationResult.SourceNotFound]. */
internal fun Application.withSource(source: SourceId): ApplicationResult<Application> =
    if (sources.any { it.id == source }) ApplicationResult.Success(this) else ApplicationResult.SourceNotFound

/** A snapshot read for an application that exists: not found means the application has no such snapshot. */
internal fun <T> ApplicationStoreResult<T>.snapshotResult(): ApplicationResult<T> =
    if (this == ApplicationStoreResult.NotFound) ApplicationResult.SnapshotNotFound else toResult()
