// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.application

import io.github.scriptibus.jofi.companies.application.port.spi.ApplicationCountsPort
import io.github.scriptibus.jofi.companies.domain.Company
import io.github.scriptibus.jofi.companies.domain.CompanyResult
import io.github.scriptibus.jofi.companies.domain.CompanyStoreResult
import io.github.scriptibus.jofi.companies.domain.CompanyValidation
import io.github.scriptibus.jofi.companies.domain.CompanyView
import io.github.scriptibus.jofi.shared.application.port.TransactionPort

// How the company use cases chain their steps: each answers a CompanyResult, the first failure ends the chain.

/** Continues with [next] on success; a failure passes through unchanged. */
internal inline fun <T, R> CompanyResult<T>.then(next: (T) -> CompanyResult<R>): CompanyResult<R> =
    when (this) {
        is CompanyResult.Success -> next(value)
        is CompanyResult.Failure -> this
    }

/** Runs [work] in one transaction that commits only on [CompanyResult.Success]. */
internal fun <T> TransactionPort.whenSuccessful(work: () -> CompanyResult<T>): CompanyResult<T> =
    inTransaction({ it is CompanyResult.Success }, work)

internal fun <T> CompanyValidation<T>.toResult(): CompanyResult<T> =
    when (this) {
        is CompanyValidation.Valid -> CompanyResult.Success(value)
        is CompanyValidation.Invalid -> CompanyResult.Invalid(violations)
    }

internal fun <T> CompanyStoreResult<T>.toResult(): CompanyResult<T> =
    when (this) {
        is CompanyStoreResult.Success -> CompanyResult.Success(value)

        CompanyStoreResult.NotFound -> CompanyResult.NotFound

        CompanyStoreResult.VersionConflict -> CompanyResult.VersionConflict

        CompanyStoreResult.HasApplications -> CompanyResult.HasApplications

        // Only a proof for another target gets here, a bug of the use case; nothing was deleted.
        CompanyStoreResult.NotConfirmed -> CompanyResult.StorageFailure("delete without matching proof")

        is CompanyStoreResult.StorageFailure -> CompanyResult.StorageFailure(operation)
    }

/** The company if the caller based its change on its current version, else [CompanyResult.VersionConflict]. */
internal fun Company.basedOn(version: Long): CompanyResult<Company> =
    if (this.version == version) CompanyResult.Success(this) else CompanyResult.VersionConflict

/** [this] if [done] holds, else a storage failure of [operation] (the transaction then rolls back). */
internal fun <T> T.onlyIf(
    done: Boolean,
    operation: String,
): CompanyResult<T> = if (done) CompanyResult.Success(this) else CompanyResult.StorageFailure(operation)

/** [companies] with their application counts, which the applications context provides. */
internal fun ApplicationCountsPort.viewsOf(companies: List<Company>): CompanyResult<List<CompanyView>> {
    if (companies.isEmpty()) return CompanyResult.Success(emptyList())
    return when (val counts = countByCompany(companies.map { it.id.value }.toSet())) {
        is ApplicationCountsPort.Counts.Counted -> {
            CompanyResult.Success(
                companies.map {
                    CompanyView(it, counts.of(it.id.value))
                },
            )
        }

        ApplicationCountsPort.Counts.Unavailable -> {
            CompanyResult.StorageFailure("count applications")
        }
    }
}

internal fun ApplicationCountsPort.viewOf(company: Company): CompanyResult<CompanyView> =
    viewsOf(listOf(company)).then { CompanyResult.Success(it.single()) }
