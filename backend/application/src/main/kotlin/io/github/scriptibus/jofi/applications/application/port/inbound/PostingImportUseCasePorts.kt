// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application.port.inbound

import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationInput
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ImportId
import io.github.scriptibus.jofi.applications.domain.PostingImport
import io.github.scriptibus.jofi.applications.domain.SourceInput
import io.github.scriptibus.jofi.shared.domain.Actor

// Inbound ports of the posting import (spec §8.1, #96): the user pastes a posting, a worker job reads its fields with
// AI and creates a `DISCOVERED` application. The posting is untrusted data, never instructions, and never goes into a
// changelog entry or a log line.

/**
 * Creates a `DISCOVERED` application found outside the user's own typing (an import, later scanners and the chat), in
 * one transaction: the application, marked unread, with its first history entry, its first source and that source's
 * first description snapshot (`SnapshotReason.DISCOVERY`), and a changelog entry for each.
 */
interface AddDiscoveredApplicationPort {
    fun execute(
        input: ApplicationInput,
        source: SourceInput,
        actor: Actor,
    ): ApplicationResult<Application>
}

/**
 * Starts importing a pasted posting [text] (at most `DescriptionText.MAX_LENGTH` characters, `Invalid` DESCRIPTION
 * otherwise): stores it as a pending import with its changelog entry and queues the worker job. `AiNotConfigured` if
 * no model is assigned to the extraction task; nothing is stored then. If the job cannot be queued, the import is
 * answered as failed (`NOT_QUEUED`) and can be retried.
 */
interface StartPostingImportPort {
    fun execute(
        text: String,
        actor: Actor,
    ): ApplicationResult<PostingImport>
}

/** The import's status, for polling: pending, failed with a reason, or done with its application. */
interface GetPostingImportPort {
    fun execute(id: ImportId): ApplicationResult<PostingImport>
}

/**
 * Queues a failed import again with its kept text, as the next attempt: `ImportNotRetryable` unless it failed,
 * `AiNotConfigured` while no model is assigned to the extraction task.
 */
interface RetryPostingImportPort {
    fun execute(
        id: ImportId,
        actor: Actor,
    ): ApplicationResult<PostingImport>
}

/**
 * Runs a pending import (the worker job): extraction with AI, the company matched or created
 * (`MatchCompanyPort`), then the application through [AddDiscoveredApplicationPort] and the import marked done, in
 * one transaction. A failed extraction or an answer without a valid title or company marks the import failed with its
 * reason (a `Success` for the job: it did its work). An import that is not pending is answered as it is (a
 * repeated job run). `StorageFailure` when something could not be read or stored; the job retries.
 */
interface RunPostingImportPort {
    fun execute(
        id: ImportId,
        actor: Actor,
    ): ApplicationResult<PostingImport>
}
