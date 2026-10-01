// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import io.github.scriptibus.jofi.applications.application.GetPostingImportUseCase
import io.github.scriptibus.jofi.applications.application.RetryPostingImportUseCase
import io.github.scriptibus.jofi.applications.application.StartPostingImportUseCase
import io.github.scriptibus.jofi.applications.application.StartUrlImportUseCase
import io.github.scriptibus.jofi.applications.domain.ImportId
import io.github.scriptibus.jofi.applications.domain.UrlImportOutcome
import io.github.scriptibus.jofi.shared.adapter.web.AlsoAnswers
import io.github.scriptibus.jofi.shared.adapter.web.ProblemKind
import io.github.scriptibus.jofi.shared.adapter.web.ProblemResponses
import io.github.scriptibus.jofi.shared.domain.Actor
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Importing a job posting from pasted text or a URL (spec §8.1, #96, #97): starting answers at once with a pending
 * import that a worker job turns into a `DISCOVERED` application; the client polls it. A URL already imported
 * answers at once (200) with the existing application; a new or still-pending import answers 202. Each call maps
 * its use case's failure with [ApplicationProblems.of]; `409 ai-not-configured` tells the user to set up AI first.
 */
@RestController
@RequestMapping("/api/applications/imports")
class PostingImportController(
    private val start: StartPostingImportUseCase,
    private val startUrl: StartUrlImportUseCase,
    private val get: GetPostingImportUseCase,
    private val retry: RetryPostingImportUseCase,
) {
    /** Starts importing a pasted posting; poll the answer's import until it is no longer `PENDING`. */
    @PostMapping("/text")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @ProblemResponses(ProblemKind.INVALID_INPUT, ProblemKind.CONFLICT)
    fun startPostingImport(
        @RequestBody request: StartPostingImportRequest,
    ): PostingImportResponse = PostingImportResponse.from(start.execute(request.description, Actor.User).orThrow())

    /**
     * Starts importing a posting fetched from a URL; a blocked, failed or login-walled fetch answers
     * `400 invalid-application` (`originalUrl`/`UNREACHABLE` or `NOT_ALLOWED`) so the client can offer pasting the
     * text instead. A link already imported answers 200 with the existing application; otherwise 202, as the text
     * import.
     */
    @PostMapping("/url")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @AlsoAnswers(HttpStatus.OK, "The link was imported before; the import carries its application")
    @ProblemResponses(ProblemKind.INVALID_INPUT, ProblemKind.CONFLICT)
    fun startUrlImport(
        @RequestBody request: StartUrlImportRequest,
    ): ResponseEntity<PostingImportResponse> {
        val outcome = startUrl.execute(request.url, Actor.User).orThrow()
        val status = if (outcome is UrlImportOutcome.AlreadyImported) HttpStatus.OK else HttpStatus.ACCEPTED
        return ResponseEntity.status(status).body(PostingImportResponse.from(outcome.import))
    }

    /** The import's status: pending, failed with a reason, or done with its application. */
    @GetMapping("/{importId}")
    @ProblemResponses(ProblemKind.NOT_FOUND)
    fun getPostingImport(
        @PathVariable importId: UUID,
    ): PostingImportResponse = PostingImportResponse.from(get.execute(ImportId(importId)).orThrow())

    /** Queues a failed import, or one pending for 30 minutes or more, again with the text it kept. */
    @PostMapping("/{importId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @ProblemResponses(ProblemKind.NOT_FOUND, ProblemKind.CONFLICT)
    fun retryPostingImport(
        @PathVariable importId: UUID,
    ): PostingImportResponse = PostingImportResponse.from(retry.execute(ImportId(importId), Actor.User).orThrow())
}
