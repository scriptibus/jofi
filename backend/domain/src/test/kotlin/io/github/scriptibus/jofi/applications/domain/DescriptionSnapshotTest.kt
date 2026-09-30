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

    private fun version(
        n: Int,
        text: String,
        at: Instant,
    ) = DescriptionSnapshot(
        SnapshotId(UUID(0, n.toLong())),
        source,
        DescriptionText(text),
        SnapshotReason.CHANGE_DETECTED,
        at,
    )

    /** What a move freezes of one source's [history]: what #84's use case stores in the same transaction. */
    private fun move(
        history: List<DescriptionSnapshot>,
        from: ApplicationStatus,
        to: ApplicationStatus,
        at: Instant,
    ): List<DescriptionSnapshot> {
        from.canMoveTo(to) shouldBe true
        val event = ApplicationStatusChanged(ApplicationId(UUID(0, 1)), from, to, Actor.User, at)
        val frozen = if (event.freezesDescriptions) DescriptionSnapshot.toFreeze(history, at) else null
        return history.map { if (it.id == frozen?.id) frozen else it }
    }

    @Test
    fun `only the first freeze counts, so OFFER to DECLINED to OFFER keeps what was applied for`() {
        val applied =
            move(
                listOf(version(1, "Kotlin", at)),
                ApplicationStatus.DISCOVERED,
                ApplicationStatus.APPLIED,
                at.plusSeconds(10),
            )
        val offered = move(applied, ApplicationStatus.APPLIED, ApplicationStatus.OFFER, at.plusSeconds(20))
        val declined = move(offered, ApplicationStatus.OFFER, ApplicationStatus.DECLINED, at.plusSeconds(30))
        val changed = declined + version(2, "Kotlin, Remote", at.plusSeconds(40))

        val reopened = move(changed, ApplicationStatus.DECLINED, ApplicationStatus.OFFER, at.plusSeconds(50))

        reopened.map { it.frozenAt } shouldBe listOf(at.plusSeconds(10), null)
        DescriptionSnapshot.toFreeze(reopened, later) shouldBe null
    }

    @Test
    fun `declined before applying, then applied after a change, freezes the changed version, not a later one`() {
        val declined =
            move(
                listOf(version(1, "Kotlin", at)),
                ApplicationStatus.DISCOVERED,
                ApplicationStatus.DECLINED,
                at.plusSeconds(10),
            )
        val changed = declined + version(2, "Kotlin, Remote", at.plusSeconds(20))
        val preparing = move(changed, ApplicationStatus.DECLINED, ApplicationStatus.PREPARING, at.plusSeconds(30))
        val afterwards = preparing + version(3, "Kotlin, Hybrid", at.plusSeconds(50))

        val applied = move(afterwards, ApplicationStatus.PREPARING, ApplicationStatus.APPLIED, at.plusSeconds(40))

        applied.map { it.frozenAt } shouldBe listOf(null, at.plusSeconds(40), null)
        DescriptionSnapshot.toFreeze(emptyList(), later) shouldBe null
    }

    @Test
    fun `a source found after applying has its discovery snapshot frozen at once`() {
        val details = ApplicationDetails("Backend Engineer", CompanyRef(UUID(0, 2)))
        val discovered = Application.create(ApplicationId(UUID(0, 1)), details, at)
        val applied = discovered.copy(status = ApplicationStatus.INTERVIEWING)
        val text = DescriptionText("Kotlin")

        DescriptionSnapshot.discovery(first, source, text, discovered, later).frozenAt shouldBe null
        DescriptionSnapshot.discovery(first, source, text, applied, later) shouldBe
            DescriptionSnapshot(first, source, text, SnapshotReason.DISCOVERY, later, later)
        DescriptionSnapshot
            .discovery(
                first,
                source,
                text,
                discovered.copy(
                    status = ApplicationStatus.DECLINED,
                    declineReason = DeclineReason(DeclineCategory.ROLE, null),
                ),
                later,
            ).frozen shouldBe false
    }

    @Test
    fun `a source's first text recorded after applying is frozen at once, before applying it is not`() {
        val details = ApplicationDetails("Backend Engineer", CompanyRef(UUID(0, 2)))
        val discovered = Application.create(ApplicationId(UUID(0, 1)), details, at)
        val applied = discovered.copy(status = ApplicationStatus.APPLIED)
        val recorded = DescriptionSnapshot(first, source, DescriptionText("Kotlin"), SnapshotReason.MANUAL, later)

        recorded.firstOf(discovered) shouldBeSameInstanceAs recorded
        recorded.firstOf(applied) shouldBe recorded.copy(frozenAt = later)
    }

    @Test
    fun `a summary counts characters as code points, as the database does`() {
        val emoji = snapshot.copy(text = DescriptionText("Team 🚀"))

        emoji.summary().length shouldBe 6
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
