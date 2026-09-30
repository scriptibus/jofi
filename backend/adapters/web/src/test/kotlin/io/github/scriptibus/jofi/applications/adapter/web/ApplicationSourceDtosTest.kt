// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationSource
import io.github.scriptibus.jofi.applications.domain.DescriptionDiff
import io.github.scriptibus.jofi.applications.domain.DescriptionInput
import io.github.scriptibus.jofi.applications.domain.DescriptionSnapshot
import io.github.scriptibus.jofi.applications.domain.DescriptionText
import io.github.scriptibus.jofi.applications.domain.DiffOperation
import io.github.scriptibus.jofi.applications.domain.DiffSegment
import io.github.scriptibus.jofi.applications.domain.SnapshotId
import io.github.scriptibus.jofi.applications.domain.SnapshotReason
import io.github.scriptibus.jofi.applications.domain.SnapshotRecording
import io.github.scriptibus.jofi.applications.domain.SourceId
import io.github.scriptibus.jofi.applications.domain.SourceInput
import io.github.scriptibus.jofi.applications.domain.SourceKind
import io.github.scriptibus.jofi.shared.domain.text.WebAddress
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ApplicationSourceDtosTest {
    private val at = Instant.parse("2026-09-30T08:00:00Z")
    private val applicationUuid = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val sourceUuid = UUID.fromString("00000000-0000-0000-0000-0000000000d1")
    private val snapshotUuid = UUID.fromString("00000000-0000-0000-0000-0000000000e1")
    private val text = "# Backend Engineer\n\nSecret posting text"
    private val snapshot =
        DescriptionSnapshot(
            SnapshotId(snapshotUuid),
            SourceId(sourceUuid),
            DescriptionText(text),
            SnapshotReason.DISCOVERY,
            at,
            at.plusSeconds(60),
        )

    @Test
    fun `requests become domain input unchanged, validation is the domain's job`() {
        AddApplicationSourceRequest(PostingSourceKind.URL, " https://jobs.example/1 ", at, " Text ").toInput() shouldBe
            SourceInput(SourceKind.URL, " https://jobs.example/1 ", at, " Text ")
        AddApplicationSourceRequest(PostingSourceKind.MANUAL_CHAT).toInput() shouldBe
            SourceInput(SourceKind.MANUAL_CHAT)
        RecordDescriptionSnapshotRequest(" Text ").toInput() shouldBe DescriptionInput(" Text ", SnapshotReason.MANUAL)
    }

    @Test
    fun `a source becomes a response with its link and availability`() {
        val source =
            ApplicationSource(
                SourceId(sourceUuid),
                ApplicationId(applicationUuid),
                SourceKind.SCANNER,
                WebAddress("https://jobs.example/1"),
                at,
            )

        ApplicationSourceResponse.from(source) shouldBe
            ApplicationSourceResponse(sourceUuid, PostingSourceKind.SCANNER, "https://jobs.example/1", at, true, null)
        ApplicationSourceResponse.from(source.markOffline(at.plusSeconds(5))).offlineSince shouldBe at.plusSeconds(5)
    }

    @Test
    fun `a snapshot becomes a response with every field, a summary without its text`() {
        val summary =
            DescriptionSnapshotSummaryResponse(
                snapshotUuid,
                sourceUuid,
                snapshot.contentHash.hex,
                SnapshotCaptureReason.DISCOVERY,
                at,
                at.plusSeconds(60),
                text.length,
            )

        DescriptionSnapshotResponse.from(snapshot) shouldBe
            DescriptionSnapshotResponse(
                snapshotUuid,
                sourceUuid,
                text,
                snapshot.contentHash.hex,
                SnapshotCaptureReason.DISCOVERY,
                at,
                at.plusSeconds(60),
            )
        DescriptionSnapshotSummaryResponse.from(snapshot.summary()) shouldBe summary
        DescriptionSnapshotRecordedResponse.from(SnapshotRecording.Unchanged(snapshot)) shouldBe
            DescriptionSnapshotRecordedResponse(summary, added = false)
        DescriptionSnapshotRecordedResponse.from(SnapshotRecording.Added(snapshot)).added shouldBe true
    }

    @Test
    fun `a diff keeps its segments in order`() {
        val other = UUID.fromString("00000000-0000-0000-0000-0000000000e2")
        val diff =
            DescriptionDiff(
                SnapshotId(snapshotUuid),
                SnapshotId(other),
                listOf(DiffSegment(DiffOperation.UNCHANGED, "Kotlin "), DiffSegment(DiffOperation.ADDED, "und Java")),
            )

        DescriptionDiffResponse.from(diff) shouldBe
            DescriptionDiffResponse(
                snapshotUuid,
                other,
                listOf(
                    DiffSegmentDto(DiffSegmentOperation.UNCHANGED, "Kotlin "),
                    DiffSegmentDto(DiffSegmentOperation.ADDED, "und Java"),
                ),
            )
    }

    @Test
    fun `DTOs print no posting text and no link`() {
        listOf(
            AddApplicationSourceRequest(PostingSourceKind.URL, "https://jobs.example/?who=secret", at, "Secret text"),
            ApplicationSourceResponse(
                sourceUuid,
                PostingSourceKind.URL,
                "https://jobs.example/?who=secret",
                at,
                true,
                null,
            ),
            RecordDescriptionSnapshotRequest("Secret text"),
            DescriptionSnapshotResponse.from(snapshot),
            DiffSegmentDto(DiffSegmentOperation.REMOVED, "Secret text"),
        ).forEach { it.toString().lowercase() shouldNotContain "secret" }
    }
}
