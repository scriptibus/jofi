// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import io.github.scriptibus.jofi.applications.application.DiffDescriptionSnapshotsUseCase
import io.github.scriptibus.jofi.applications.application.GetDescriptionSnapshotUseCase
import io.github.scriptibus.jofi.applications.application.ListDescriptionSnapshotsUseCase
import io.github.scriptibus.jofi.applications.application.RecordDescriptionSnapshotUseCase
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.SnapshotId
import io.github.scriptibus.jofi.applications.domain.SourceId
import io.github.scriptibus.jofi.shared.adapter.web.ProblemKind
import io.github.scriptibus.jofi.shared.adapter.web.ProblemResponses
import io.github.scriptibus.jofi.shared.domain.Actor
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.ErrorResponseException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Where an application's job was found and the history of its description (spec §6.1, ADR-0046). Recording,
 * listing, reading and diffing versions (#86) call their use case and map each `ApplicationResult.Failure` with
 * [ApplicationProblems.of]. Adding a source is still the contract only and answers `501 Not Implemented` until
 * #96; its parameters only declare it. The sources themselves come with the application
 * (`ApplicationResponse.sources`).
 */
@RestController
@RequestMapping("/api/applications/{id}")
class ApplicationSourceController(
    private val recordSnapshot: RecordDescriptionSnapshotUseCase,
    private val listSnapshots: ListDescriptionSnapshotsUseCase,
    private val getSnapshot: GetDescriptionSnapshotUseCase,
    private val diffSnapshots: DiffDescriptionSnapshotsUseCase,
) {
    /** Adds a place the job was found, with the posting's text there as its first description version. */
    @Suppress("UnusedParameter")
    @PostMapping("/sources")
    @ResponseStatus(HttpStatus.CREATED)
    @ProblemResponses(ProblemKind.INVALID_INPUT, ProblemKind.NOT_FOUND)
    fun addApplicationSource(
        @PathVariable id: UUID,
        @RequestBody request: AddApplicationSourceRequest,
    ): ApplicationSourceResponse = throw notImplemented()

    /**
     * Records the posting's current text as a new version of the source's description; the same text again
     * adds nothing (`added` false) and answers the newest version.
     */
    @PostMapping("/sources/{sourceId}/snapshots")
    @ProblemResponses(ProblemKind.INVALID_INPUT, ProblemKind.NOT_FOUND)
    fun recordDescriptionSnapshot(
        @PathVariable id: UUID,
        @PathVariable sourceId: UUID,
        @RequestBody request: RecordDescriptionSnapshotRequest,
    ): DescriptionSnapshotRecordedResponse =
        DescriptionSnapshotRecordedResponse.from(
            recordSnapshot.execute(ApplicationId(id), SourceId(sourceId), request.toInput(), Actor.User).orThrow(),
        )

    /** The versions of the source's description, oldest first, without their texts. */
    @GetMapping("/sources/{sourceId}/snapshots")
    @ProblemResponses(ProblemKind.NOT_FOUND)
    fun listDescriptionSnapshots(
        @PathVariable id: UUID,
        @PathVariable sourceId: UUID,
    ): DescriptionSnapshotListResponse =
        DescriptionSnapshotListResponse(
            listSnapshots
                .execute(ApplicationId(id), SourceId(sourceId))
                .orThrow()
                .map(DescriptionSnapshotSummaryResponse::from),
        )

    /** One version of a description with its full text. */
    @GetMapping("/snapshots/{snapshotId}")
    @ProblemResponses(ProblemKind.NOT_FOUND)
    fun getDescriptionSnapshot(
        @PathVariable id: UUID,
        @PathVariable snapshotId: UUID,
    ): DescriptionSnapshotResponse =
        DescriptionSnapshotResponse.from(getSnapshot.execute(ApplicationId(id), SnapshotId(snapshotId)).orThrow())

    /**
     * What changed from one version of the application's descriptions to another (of any of its sources), line
     * by line: segments that are in both, only in `from` (`REMOVED`) or only in `to` (`ADDED`).
     */
    @GetMapping("/description-diff")
    @ProblemResponses(ProblemKind.NOT_FOUND)
    fun diffDescriptionSnapshots(
        @PathVariable id: UUID,
        @RequestParam from: UUID,
        @RequestParam to: UUID,
    ): DescriptionDiffResponse =
        DescriptionDiffResponse.from(
            diffSnapshots.execute(ApplicationId(id), SnapshotId(from), SnapshotId(to)).orThrow(),
        )

    private fun notImplemented(): ErrorResponseException {
        val problem =
            ProblemDetail.forStatusAndDetail(HttpStatus.NOT_IMPLEMENTED, "Adding sources is not available yet")
        return ErrorResponseException(HttpStatus.NOT_IMPLEMENTED, problem, null)
    }
}
