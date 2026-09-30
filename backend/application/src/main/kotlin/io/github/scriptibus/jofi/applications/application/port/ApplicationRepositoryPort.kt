// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application.port

import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationPage
import io.github.scriptibus.jofi.applications.domain.ApplicationSearch
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.StatusChange
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult

/**
 * Stores applications (tables `application`, `application_contact` and `application_status_change`;
 * implemented with the use cases in #82 and #84). The use case that changes an application appends its
 * changelog entry in the same transaction (`TransactionPort`). Implementations never throw and never log
 * row data. Foreign-key violations are mapped **by constraint name** (ADR-0041): `application_company_fk` to
 * [ApplicationStoreResult.CompanyNotFound], `application_contact_contact_fk` to
 * [ApplicationStoreResult.ContactNotFound]; anything else is a `StorageFailure`.
 */
interface ApplicationRepositoryPort {
    /** Stores a new application with its contact links and the first entry of its status history ([initial]). */
    fun add(
        application: Application,
        initial: StatusChange,
    ): ApplicationStoreResult<Unit>

    /**
     * Stores [application]'s details: writes only the detail columns, `version` and `updated_at`, never
     * the status, the decline reason, `unread`, the scores or the contact links, and only if the stored
     * version is exactly one below [application]'s (see [Application.edit]);
     * [ApplicationStoreResult.VersionConflict] otherwise. So a concurrent read/unread toggle, score update or
     * status change is never overwritten by a detail edit.
     */
    fun updateDetails(application: Application): ApplicationStoreResult<Unit>

    /**
     * Stores [application]'s contact links: writes `version` and `updated_at` and rewrites
     * `application_contact` only when the stored set differs, under the same version check as
     * [updateDetails] (see [Application.linkContacts]). Never touches the detail columns, the status,
     * `unread` or the scores.
     */
    fun replaceContacts(application: Application): ApplicationStoreResult<Unit>

    /**
     * Stores a status change (see [Application.changeStatus]): writes only `status`, the decline reason
     * (`decline_category`, `decline_reason`), `version` and `updated_at`, under the same version check as
     * [updateDetails], and appends [change] to `application_status_change`, both or neither. Never touches
     * the details, `unread`, the scores or the contact links.
     */
    fun changeStatus(
        application: Application,
        change: StatusChange,
    ): ApplicationStoreResult<Unit>

    /** The application's status history, oldest entry first (the order of appending). */
    fun statusHistory(id: ApplicationId): ApplicationStoreResult<List<StatusChange>>

    /** Sets only the unread flag, without a version check or a new version ([Application.markUnread]). */
    fun setUnread(
        id: ApplicationId,
        unread: Boolean,
    ): ApplicationStoreResult<Unit>

    fun findById(id: ApplicationId): ApplicationStoreResult<Application>

    fun search(search: ApplicationSearch): ApplicationStoreResult<ApplicationPage<Application>>

    /**
     * Deletes the application and, by `ON DELETE CASCADE`, its contact links and status history. [proof] is what the
     * confirmation gate returned (ADR-0039): the adapter answers [ApplicationStoreResult.NotConfirmed]
     * unless `proof.covers(Application.DELETE_OPERATION, id.value.toString())`.
     */
    fun delete(
        id: ApplicationId,
        proof: ConfirmationResult.Confirmed,
    ): ApplicationStoreResult<Unit>
}
