// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

import io.github.scriptibus.jofi.applications.domain.DiffOperation.ADDED
import io.github.scriptibus.jofi.applications.domain.DiffOperation.REMOVED
import io.github.scriptibus.jofi.applications.domain.DiffOperation.UNCHANGED
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class DescriptionDiffTest {
    private val at = Instant.parse("2026-09-30T08:00:00Z")

    private fun snapshot(
        n: Long,
        source: Long,
        text: String,
    ) = DescriptionSnapshot(
        SnapshotId(UUID(0, n)),
        SourceId(UUID(1, source)),
        DescriptionText(text),
        SnapshotReason.MANUAL,
        at,
    )

    private fun diff(
        from: String,
        to: String,
    ): List<Pair<DiffOperation, String>> = LineDiff.segments(from, to).map { it.operation to it.text }

    /** The old text from the unchanged and removed segments, the new one from the unchanged and added ones. */
    private fun List<DiffSegment>.spell(skipped: DiffOperation): String =
        filter { it.operation != skipped }.joinToString("") { it.text }

    @Test
    fun `the diff names both versions and turns one text into the other line by line, across sources`() {
        val old = snapshot(1, source = 1, "# Backend Engineer\nKotlin\nBerlin\nApply by mail")
        val new = snapshot(2, source = 2, "# Backend Engineer\nKotlin, Spring\nBerlin\nRemote\nApply by mail")

        val diff = DescriptionDiff.between(old, new)

        diff.from shouldBe old.id
        diff.to shouldBe new.id
        diff.segments.map { it.operation to it.text } shouldContainExactly
            listOf(
                UNCHANGED to "# Backend Engineer\n",
                REMOVED to "Kotlin\n",
                ADDED to "Kotlin, Spring\n",
                UNCHANGED to "Berlin\n",
                ADDED to "Remote\n",
                UNCHANGED to "Apply by mail",
            )
    }

    @Test
    fun `equal texts are one unchanged segment, unrelated ones a removal and an addition`() {
        diff("a\nb", "a\nb") shouldContainExactly listOf(UNCHANGED to "a\nb")
        diff("a\nb", "c\nd") shouldContainExactly listOf(REMOVED to "a\nb", ADDED to "c\nd")
        diff("a", "a\nb") shouldContainExactly listOf(REMOVED to "a", ADDED to "a\nb")
        diff("a\nb", "b") shouldContainExactly listOf(REMOVED to "a\n", UNCHANGED to "b")
    }

    @Test
    fun `random edits give a shortest edit script that spells both texts`() {
        val random = Random(86)
        repeat(300) {
            val old = List(random.nextInt(1, 25)) { "line ${random.nextInt(6)}" }
            val new = old.toMutableList().apply { edit(random) }.ifEmpty { listOf("only") }
            val oldText = old.joinToString("\n")
            val newText = new.joinToString("\n")

            val segments = LineDiff.segments(oldText, newText)

            segments.spell(ADDED) shouldBe oldText
            segments.spell(REMOVED) shouldBe newText
            segments.zipWithNext().none { (a, b) -> a.operation == b.operation } shouldBe true
            changedLines(segments) shouldBe old.size + new.size - 2 * longestCommon(lines(oldText), lines(newText))
        }
    }

    @Test
    fun `beyond the edit budget the differing middle is replaced wholesale, and quickly`() {
        val count = LineDiff.MAX_EDITS
        val old = (0 until count).joinToString("\n", "head\n", "\ntail") { "old $it" }
        val new = (0 until count).joinToString("\n", "head\n", "\ntail") { "new $it" }
        val longest = "x".repeat(DescriptionText.MAX_LENGTH / 2)
        val many = List(DescriptionText.MAX_LENGTH / 2) { "${it % 7}" }.joinToString("\n")

        diff(old, new).map { it.first } shouldContainExactly listOf(UNCHANGED, REMOVED, ADDED, UNCHANGED)
        measureTime {
            LineDiff.segments(many, many.reversed()).spell(ADDED) shouldBe many
            LineDiff.segments(longest, longest.reversed() + "y").spell(REMOVED) shouldBe longest + "y"
        } shouldBeLessThan 10.seconds
    }

    private infix fun kotlin.time.Duration.shouldBeLessThan(limit: kotlin.time.Duration) {
        (this < limit) shouldBe true
    }

    private fun MutableList<String>.edit(random: Random) {
        repeat(random.nextInt(0, 6)) {
            when (random.nextInt(3)) {
                0 -> if (isNotEmpty()) removeAt(random.nextInt(size))
                1 -> add(random.nextInt(size + 1), "new ${random.nextInt(6)}")
                else -> if (isNotEmpty()) set(random.nextInt(size), "changed ${random.nextInt(6)}")
            }
        }
    }

    /** Lines with their `\n`, as the diff compares them. */
    private fun lines(text: String): List<String> = Regex("[^\n]*\n|[^\n]+$").findAll(text).map { it.value }.toList()

    private fun changedLines(segments: List<DiffSegment>): Int =
        segments.filter { it.operation != UNCHANGED }.sumOf { lines(it.text).size }

    /** The textbook dynamic program, to check the diff is a shortest one. */
    private fun longestCommon(
        a: List<String>,
        b: List<String>,
    ): Int {
        val table = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) {
            for (j in b.indices.reversed()) {
                table[i][j] = if (a[i] == b[j]) table[i + 1][j + 1] + 1 else maxOf(table[i + 1][j], table[i][j + 1])
            }
        }
        return table[0][0]
    }
}
