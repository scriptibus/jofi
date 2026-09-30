// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.SavedViewRepositoryPort
import io.github.scriptibus.jofi.applications.domain.ApplicationProblem
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.SavedView
import io.github.scriptibus.jofi.applications.domain.SavedViewDetails
import io.github.scriptibus.jofi.applications.domain.SavedViewField
import io.github.scriptibus.jofi.applications.domain.SavedViewId
import io.github.scriptibus.jofi.applications.domain.SavedViewValidation
import io.github.scriptibus.jofi.applications.domain.SavedViewViolation
import io.github.scriptibus.jofi.shared.domain.FieldChange

// The steps the saved view use cases (#99, ADR-0050) share, on top of `ApplicationSteps`.

internal fun SavedViewValidation.toResult(): ApplicationResult<SavedViewDetails> =
    when (this) {
        is SavedViewValidation.Valid -> ApplicationResult.Success(details)
        is SavedViewValidation.Invalid -> ApplicationResult.InvalidView(violations)
    }

/** A saved view read: not found means no view with that id. */
internal fun <T> ApplicationStoreResult<T>.savedViewResult(): ApplicationResult<T> =
    if (this == ApplicationStoreResult.NotFound) ApplicationResult.SavedViewNotFound else toResult()

/** The view if the caller based its change on its current version, else [ApplicationResult.VersionConflict]. */
internal fun SavedView.basedOn(version: Long): ApplicationResult<SavedView> =
    if (this.version == version) ApplicationResult.Success(this) else ApplicationResult.VersionConflict

/**
 * [details] unless a view other than [except] is called its name, ignoring case ([SavedView.isNamed]): then
 * `InvalidView` (name, TAKEN). The database's unique constraint only catches exactly equal names that race past this.
 */
internal fun SavedViewRepositoryPort.nameFree(
    details: SavedViewDetails,
    except: SavedViewId? = null,
): ApplicationResult<SavedViewDetails> =
    list().toResult().then { views ->
        if (views.any { it.id != except && it.isNamed(details.name) }) {
            ApplicationResult.InvalidView(listOf(SavedViewViolation(SavedViewField.Name, ApplicationProblem.TAKEN)))
        } else {
            ApplicationResult.Success(details)
        }
    }

/** The view's name as a changelog field (it names a query, not a person); the filter is never recorded with values. */
internal fun nameChange(
    before: String?,
    after: String?,
): List<FieldChange> = listOfNotNull(changeOf("name", before, after))

/** [action], naming the filter as changed without its values (its search text may quote a title). */
internal fun describeView(
    action: String,
    filterChanged: Boolean,
): String = if (filterChanged) "$action; also changed: filter" else action
