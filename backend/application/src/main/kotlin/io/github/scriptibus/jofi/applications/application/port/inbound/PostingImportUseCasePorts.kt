// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application.port.inbound

import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationInput
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.DescriptionText
import io.github.scriptibus.jofi.applications.domain.ImportId
import io.github.scriptibus.jofi.applications.domain.PostingImport
import io.github.scriptibus.jofi.applications.domain.SourceInput
import io.github.scriptibus.jofi.applications.domain.UrlImportOutcome
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.text.WebAddress

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

/**
 * Starts importing a posting fetched from a [url] (spec §8.1, #97): normalised first (tracking parameters
 * stripped); `Invalid` `SOURCE_URL`/`INVALID_URL` if it is not an absolute http(s) URL, `NOT_ALLOWED` for a site
 * Jofi never scrapes (LinkedIn, StepStone, Indeed). A URL already imported successfully answers at once with its
 * existing application ([UrlImportOutcome.AlreadyImported]); one already pending answers with that import instead
 * of starting another (double submit, #187 finding F6). Otherwise fetched through `OutboundHttpPort`, its main text
 * extracted, then the same path as [StartPostingImportPort]: `Invalid` `SOURCE_URL` with the reason a fetch failed
 * (`UNREACHABLE`, `TIMEOUT`, `TOO_LARGE`, `NOT_HTML`, `LOGIN_REQUIRED`, `NO_TEXT`; paste the text instead);
 * `AiNotConfigured` while no model is assigned to the extraction task, before anything is fetched.
 */
interface StartUrlImportPort {
    fun execute(
        url: String,
        actor: Actor,
    ): ApplicationResult<UrlImportOutcome>
}

/** The import's status, for polling: pending, failed with a reason, or done with its application. */
interface GetPostingImportPort {
    fun execute(id: ImportId): ApplicationResult<PostingImport>
}

/**
 * Queues a failed import again with its kept text, as the next attempt; also one pending for at least
 * `PostingImport.STALLED_AFTER`, whose job is gone (a restored backup) or keeps failing. `ImportNotRetryable` for
 * anything else,
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

/**
 * Fetches the posting at [address] and answers its main text (spec §8.1, #97), the step [StartUrlImportPort] runs once
 * it knows nothing is pending or imported. `AiNotConfigured` while no model is assigned to the extraction task, before
 * anything is fetched. `Invalid` `SOURCE_URL`: `NOT_ALLOWED` if the fetch ended on a site Jofi never scrapes (a
 * redirect or shortener into LinkedIn, StepStone or Indeed), `LOGIN_REQUIRED` for a login wall (401, 403, or a redirect
 * to a login page), `TIMEOUT`, `TOO_LARGE` (a body over the limit), `NOT_HTML` (the wrong content type), `NO_TEXT` (no
 * readable text) and `UNREACHABLE` for any other blocked or failed fetch. Nothing is stored.
 */
interface FetchPostingTextPort {
    fun execute(address: WebAddress): ApplicationResult<DescriptionText>
}

/**
 * What a URL import of the normalised, allowed link [address] comes to (spec §8.1, #97), the step [StartUrlImportPort]
 * runs once per link at a time: the pending import already there, the existing application, or the fetched text stored
 * as a new pending import. No database connection is held while the page is fetched. Failures as [FetchPostingTextPort].
 */
interface ResolveUrlImportPort {
    fun execute(
        address: WebAddress,
        actor: Actor,
    ): ApplicationResult<UrlImportOutcome>
}
