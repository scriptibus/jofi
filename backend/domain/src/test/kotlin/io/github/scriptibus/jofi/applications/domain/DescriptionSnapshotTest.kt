// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class DescriptionSnapshotTest {
    private val at = Instant.parse("2026-09-30T08:00:00Z")
    private val later = at.plusSeconds(3_600)
    private val source = SourceId(UUID.fromString("00000000-0000-0000-0000-0000000000d1"))
    private val first = SnapshotId(UUID.fromString("00000000-0000-0000-0000-0000000000e1"))
    private val second = SnapshotId(UUID.fromString("00000000-0000-0000-0000-0000000000e2"))
    private val snapshot =
        DescriptionSnapshot(first, source, DescriptionText("Kotlin, Berlin"), SnapshotReason.DISCOVERY, at)

    @Test
    fun `changelog entries refer to it as a description snapshot`() {
        first.toEntityRef() shouldBe EntityRef("description_snapshot", first.value.toString())
    }

    @Test
    fun `the content hash is SHA-256 of the UTF-8 text in lower-case hex`() {
        DescriptionText("abc").contentHash shouldBe
            ContentHash("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        DescriptionText("Köln").contentHash.hex shouldBe
            "430b4fc55e4ef14911b1e8a24f18fb804f0b6dfc6c0ba3e27d1f14d5f9276aa6"
        shouldThrow<IllegalArgumentException> { ContentHash("BA7816BF") }
    }

    @Test
    fun `text is normalized, so the same posting fetched twice is the same version`() {
        DescriptionText.normalize("  Köln\r\nBerlin\rHamburg\n ") shouldBe "Köln\nBerlin\nHamburg"
        DescriptionInput("Köln\r\n", SnapshotReason.MANUAL).validate() shouldBe
            ApplicationValidation.Valid(DescriptionText("Köln"))
        shouldThrow<IllegalArgumentException> { DescriptionText("a\r\nb") }
    }

    @Test
    fun `a description needs text, within its limit, without U+0000`() {
        val longest = "x".repeat(DescriptionText.MAX_LENGTH)

        DescriptionInput(longest, SnapshotReason.CHANGE_DETECTED).validate() shouldBe
            ApplicationValidation.Valid(DescriptionText(longest))
        listOf(
            " \r\n " to ApplicationProblem.REQUIRED,
            longest + "x" to ApplicationProblem.TOO_LONG,
            "a\u0000b" to ApplicationProblem.INVALID_CHARACTER,
        ).forEach { (text, problem) ->
            DescriptionInput(text, SnapshotReason.MANUAL).validate() shouldBe
                ApplicationValidation.Invalid(listOf(ApplicationViolation(ApplicationField.DESCRIPTION, problem)))
        }
    }

    @Test
    fun `the same text again is no new version, a changed one is`() {
        snapshot.next(second, DescriptionText("Kotlin, Berlin"), SnapshotReason.CHANGE_DETECTED, later) shouldBe
            SnapshotRecording.Unchanged(snapshot)

        val added =
            snapshot
                .next(second, DescriptionText("Kotlin, Hamburg"), SnapshotReason.CHANGE_DETECTED, later)
                .shouldBeInstanceOf<SnapshotRecording.Added>()

        added.snapshot shouldBe
            DescriptionSnapshot(
                second,
                source,
                DescriptionText("Kotlin, Hamburg"),
                SnapshotReason.CHANGE_DETECTED,
                later,
            )
        added.snapshot.frozen shouldBe false
    }

    @Test
    fun `a frozen snapshot is immutable, a changed text after applying is a new version`() {
        val frozen = snapshot.freeze(later)

        frozen.frozenAt shouldBe later
        frozen.freeze(later.plusSeconds(60)) shouldBeSameInstanceAs frozen
        val changed = frozen.next(second, DescriptionText("Kotlin, Remote"), SnapshotReason.CHANGE_DETECTED, later)

        changed.shouldBeInstanceOf<SnapshotRecording.Added>().snapshot.id shouldBe second
        changed.snapshot.frozen shouldBe false
        frozen shouldBe snapshot.copy(frozenAt = later)
        shouldThrow<IllegalArgumentException> { snapshot.freeze(at.minusSeconds(1)) }
    }

    @Test
    fun `a summary leaves out the text, and nothing prints it`() {
        snapshot.summary() shouldBe
            SnapshotSummary(
                first,
                source,
                snapshot.contentHash,
                SnapshotReason.DISCOVERY,
                at,
                null,
                "Kotlin, Berlin".length,
            )
        val secret = DescriptionText("Secret posting")
        listOf(
            secret,
            snapshot.copy(text = secret),
            DescriptionInput("Secret posting", SnapshotReason.MANUAL),
            DiffSegment(DiffOperation.ADDED, "Secret posting"),
        ).forEach { it.toString() shouldNotContain "Secret" }
    }

    @Test
    fun `entering the applied stages freezes the descriptions, moving within them does not`() {
        val notApplied =
            setOf(
                ApplicationStatus.DISCOVERED,
                ApplicationStatus.SHORTLISTED,
                ApplicationStatus.PREPARING,
                ApplicationStatus.DECLINED,
            )
        ApplicationStatus.entries.forEach { from ->
            ApplicationStatus.entries.forEach { to ->
                val event = ApplicationStatusChanged(ApplicationId(UUID.randomUUID()), from, to, Actor.User, at)
                event.freezesDescriptions shouldBe (from in notApplied && to !in notApplied)
            }
        }
    }
}
