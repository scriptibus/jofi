// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

/**
 * A line diff of two description texts (ADR-0046): Myers' O((N+M)·D) shortest edit script over the lines that
 * differ once the common first and last lines are set aside. Lines keep their `\n`, so the unchanged and removed
 * segments spell the old text exactly, the unchanged and added ones the new text. The work is bounded: an edit
 * script longer than [MAX_EDITS] lines turns the differing middle into one removed and one added segment, so
 * even two unrelated texts at the 100,000-character limit cost at most about (N+M)·[MAX_EDITS] steps. The texts
 * are only compared, never interpreted.
 */
internal object LineDiff {
    /** More inserted plus deleted lines than an edited posting plausibly has; past it a line diff says little. */
    const val MAX_EDITS = 1_000

    private val BLOCK_ORDER = listOf(DiffOperation.UNCHANGED, DiffOperation.REMOVED, DiffOperation.ADDED)

    fun segments(
        from: String,
        to: String,
    ): List<DiffSegment> {
        val old = lines(from)
        val new = lines(to)
        val prefix = commonPrefix(old, new)
        val suffix = commonSuffix(old, new, prefix)
        val edits = mutableListOf<Pair<DiffOperation, String>>()
        old.subList(0, prefix).mapTo(edits) { DiffOperation.UNCHANGED to it }
        middle(old.subList(prefix, old.size - suffix), new.subList(prefix, new.size - suffix), edits)
        old.subList(old.size - suffix, old.size).mapTo(edits) { DiffOperation.UNCHANGED to it }
        return runs(edits)
    }

    /** The lines of [text], each with its `\n` (the last one without, as the text is trimmed). */
    private fun lines(text: String): List<String> {
        val lines = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val end = text.indexOf('\n', start).let { if (it < 0) text.length else it + 1 }
            lines += text.substring(start, end)
            start = end
        }
        return lines
    }

    private fun commonPrefix(
        old: List<String>,
        new: List<String>,
    ): Int {
        var count = 0
        while (count < old.size && count < new.size && old[count] == new[count]) count++
        return count
    }

    /** The common last lines, not reaching into the common [prefix]. */
    private fun commonSuffix(
        old: List<String>,
        new: List<String>,
        prefix: Int,
    ): Int {
        var count = 0
        while (count < minOf(old.size, new.size) - prefix && old[old.size - 1 - count] == new[new.size - 1 - count]) {
            count++
        }
        return count
    }

    /** Appends the edits turning [old] into [new], line by line, or wholesale past [MAX_EDITS]. */
    private fun middle(
        old: List<String>,
        new: List<String>,
        edits: MutableList<Pair<DiffOperation, String>>,
    ) {
        // Comparing numbers instead of strings keeps the inner loop cheap for long texts.
        val codes = HashMap<String, Int>()
        val oldCodes = IntArray(old.size) { codes.getOrPut(old[it]) { codes.size } }
        val newCodes = IntArray(new.size) { codes.getOrPut(new[it]) { codes.size } }
        val script = EditScript(oldCodes, newCodes).shortest()
        if (script == null) {
            old.mapTo(edits) { DiffOperation.REMOVED to it }
            new.mapTo(edits) { DiffOperation.ADDED to it }
            return
        }
        var oldLine = 0
        var newLine = 0
        script.forEach { operation ->
            val line = if (operation == DiffOperation.ADDED) new[newLine] else old[oldLine]
            if (operation != DiffOperation.ADDED) oldLine++
            if (operation != DiffOperation.REMOVED) newLine++
            edits += operation to line
        }
    }

    /**
     * Consecutive edits as segments. Within a changed block the removed lines come before the added ones (both
     * keep their order), so each change between two unchanged runs is at most one removal and one addition.
     */
    private fun runs(edits: List<Pair<DiffOperation, String>>): List<DiffSegment> {
        val segments = mutableListOf<DiffSegment>()
        var start = 0
        while (start < edits.size) {
            val unchanged = edits[start].first == DiffOperation.UNCHANGED
            var end = start
            while (end < edits.size && (edits[end].first == DiffOperation.UNCHANGED) == unchanged) end++
            val block = edits.subList(start, end)
            BLOCK_ORDER.forEach { operation ->
                val text = block.filter { it.first == operation }.joinToString("") { it.second }
                if (text.isNotEmpty()) segments += DiffSegment(operation, text)
            }
            start = end
        }
        return segments
    }
}

/**
 * Myers' greedy algorithm ("An O(ND) Difference Algorithm and Its Variations", 1986) with a trace for the way
 * back: round d finds, per diagonal k = x - y, the furthest point reachable with d inserted or deleted lines.
 * Each round's starting values are kept (only diagonals -d-1..d+1, so the trace grows with d², not d·(N+M)).
 */
private class EditScript(
    private val old: IntArray,
    private val new: IntArray,
) {
    private val offset = LineDiff.MAX_EDITS + 1
    private val trace = mutableListOf<IntArray>()

    /** The shortest script turning [old] into [new], or `null` if it needs more than [LineDiff.MAX_EDITS] edits. */
    fun shortest(): List<DiffOperation>? {
        val furthest = IntArray(2 * offset + 1)
        for (edits in 0..LineDiff.MAX_EDITS) {
            trace += furthest.copyOfRange(offset - edits - 1, offset + edits + 2)
            if (reachesEnd(furthest, edits)) return backtrack()
        }
        return null
    }

    /** One round with [edits] edits; true once a path reaches the end of both texts. */
    private fun reachesEnd(
        furthest: IntArray,
        edits: Int,
    ): Boolean {
        for (diagonal in -edits..edits step 2) {
            var x =
                if (insertsLine(furthest, offset, diagonal, edits)) {
                    furthest[offset + diagonal + 1]
                } else {
                    furthest[offset + diagonal - 1] + 1
                }
            var y = x - diagonal
            while (x < old.size && y < new.size && old[x] == new[y]) {
                x++
                y++
            }
            furthest[offset + diagonal] = x
            if (x >= old.size && y >= new.size) return true
        }
        return false
    }

    /** Walks the trace back from the end: each round contributes one edit and the unchanged lines after it. */
    private fun backtrack(): List<DiffOperation> {
        val script = mutableListOf<DiffOperation>()
        var x = old.size
        var y = new.size
        for (edits in trace.indices.reversed()) {
            val round = trace[edits]
            val shift = edits + 1
            val diagonal = x - y
            val previous = if (insertsLine(round, shift, diagonal, edits)) diagonal + 1 else diagonal - 1
            val previousX = round[shift + previous]
            val previousY = previousX - previous
            while (x > previousX && y > previousY) {
                script += DiffOperation.UNCHANGED
                x--
                y--
            }
            if (edits > 0) script += if (x == previousX) DiffOperation.ADDED else DiffOperation.REMOVED
            x = previousX
            y = previousY
        }
        return script.asReversed()
    }

    /** Whether the best path onto [diagonal] comes down from diagonal + 1 (an added line) rather than from the left. */
    private fun insertsLine(
        furthest: IntArray,
        shift: Int,
        diagonal: Int,
        edits: Int,
    ): Boolean =
        diagonal == -edits ||
            (diagonal != edits && furthest[shift + diagonal - 1] < furthest[shift + diagonal + 1])
}
