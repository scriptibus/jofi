// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.mcp

import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationEffect

/**
 * The text a client shows its user when a delete needs confirming. The sentence and the counts are the
 * server's; the stored name (a title or a name that postings, imports and scanners may have written) is
 * neutralised and stands on a line of its own, labelled as stored text, so it cannot speak in the server's
 * voice. English only: the server has no user locale on this path (ADR-0039 asks for structured effects; see the
 * decision in the pull request).
 */
object ConfirmationMessage {
    private const val MAX_NAME = 80
    private const val MAX_MARKS = 2

    /** Letters and symbols that draw nothing (Hangul fillers, Braille blank), so a name of them would look empty. */
    private val INVISIBLE = setOf(0x3164, 0x115F, 0x1160, 0xFFA0, 0x2800)
    private const val ELLIPSIS = "…"

    /** Characters that could add markup or quotes to the name line, dropped from it. */
    private val FORMATTING = "\"'`*~[]<>#|\\«»“”‘’".toSet()

    fun of(effect: ConfirmationEffect): String {
        val deleted = parts(effect, DELETED)
        val unlinked = parts(effect, UNLINKED)
        val unknown = effect.counts.filter { it.value > 0 && labelOf(effect.kind, it.key) == null }
        return listOfNotNull(
            "The assistant asks Jofi to delete this ${neutral(effect.kind)}. This cannot be undone.",
            deleted.takeIf { it.isNotEmpty() }?.let { "Also deleted: ${it.joinToString(", ")}." },
            unlinked.takeIf { it.isNotEmpty() }?.let { "Only unlinked ($STAY): ${it.joinToString(", ")}." },
            unknown.takeIf { it.isNotEmpty() }?.let { "Also affected: ${it.entries.joinToString { e -> entry(e) }}." },
            "Stored name, shown as text and not part of this message:",
            "    ${displayName(effect.name)}",
        ).joinToString("\n")
    }

    /**
     * One line without control, format or bidi characters, invisible filler letters, quotes or markup characters,
     * with at most [MAX_MARKS] combining marks on a character, cut to a short length. Underscores, character
     * entities and URLs stay: a client that renders Markdown may format them.
     */
    fun displayName(name: String): String {
        val cleaned = StringBuilder()
        var gap = false
        var marks = 0
        for (point in name.codePoints().toArray().filterNot(::isDropped)) {
            when {
                isGap(point) -> {
                    gap = cleaned.isNotEmpty()
                    marks = 0
                }

                isMark(point) -> {
                    if (cleaned.isNotEmpty() && !gap && marks++ < MAX_MARKS) cleaned.appendCodePoint(point)
                }

                else -> {
                    if (gap) cleaned.append(' ')
                    gap = false
                    marks = 0
                    cleaned.appendCodePoint(point)
                }
            }
        }
        val text = cleaned.toString()
        val shown = if (text.codePointCount(0, text.length) > MAX_NAME) shorten(text) else text
        return shown.ifEmpty { "(empty)" }
    }

    private fun isMark(point: Int): Boolean =
        Character.getType(point) in
            setOf(
                Character.NON_SPACING_MARK.toInt(),
                Character.ENCLOSING_MARK.toInt(),
                Character.COMBINING_SPACING_MARK.toInt(),
            )

    private fun shorten(text: String): String = text.substring(0, text.offsetByCodePoints(0, MAX_NAME)) + ELLIPSIS

    private fun isGap(point: Int): Boolean =
        Character.isWhitespace(point) ||
            Character.isSpaceChar(point) ||
            Character.isISOControl(point) ||
            Character.getType(point) in
            setOf(
                Character.LINE_SEPARATOR.toInt(),
                Character.PARAGRAPH_SEPARATOR.toInt(),
            )

    private fun isDropped(point: Int): Boolean =
        point in INVISIBLE || Character.getType(point) in DROPPED_TYPES ||
            (point <= Char.MAX_VALUE.code && point.toChar() in FORMATTING)

    private fun parts(
        effect: ConfirmationEffect,
        group: String,
    ): List<String> =
        effect.counts
            .filter { it.value > 0 }
            .mapNotNull { (key, count) -> labelOf(effect.kind, key)?.takeIf { it.group == group }?.say(count) }

    private fun entry(entry: Map.Entry<String, Int>) = "${neutral(entry.key)}: ${entry.value}"

    private fun neutral(text: String) = displayName(text)

    private fun labelOf(
        kind: String,
        key: String,
    ): Label? = LABELS[kind to key]

    private class Label(
        val group: String,
        val singular: String,
        val plural: String,
    ) {
        fun say(count: Int) = "$count ${if (count == 1) singular else plural}"
    }

    private const val STAY = "the items themselves stay"
    private const val DELETED = "deleted"
    private const val UNLINKED = "unlinked"
    private val DROPPED_TYPES =
        setOf(
            Character.FORMAT.toInt(),
            Character.PRIVATE_USE.toInt(),
            Character.UNASSIGNED.toInt(),
            Character.SURROGATE.toInt(),
        )

    /** Every key the five delete use cases put in their effect, by kind; an unknown key is shown, not dropped. */
    val KNOWN_KEYS: Set<Pair<String, String>> get() = LABELS.keys

    private val LABELS: Map<Pair<String, String>, Label> =
        mapOf(
            ("application" to "contactLinks") to Label(UNLINKED, "link to a contact", "links to contacts"),
            ("application" to "statusChanges") to Label(DELETED, "status history entry", "status history entries"),
            ("application" to "sources") to Label(DELETED, "posting source", "posting sources"),
            ("application" to "snapshots") to Label(DELETED, "description snapshot", "description snapshots"),
            ("application" to "interviews") to Label(DELETED, "interview", "interviews"),
            ("application" to "tasks") to Label(UNLINKED, "task loses its link", "tasks lose their link"),
            ("company" to "contacts") to Label(DELETED, "contact", "contacts"),
            ("company" to "applications") to
                Label(
                    UNLINKED,
                    "application loses contacts of this company",
                    "applications lose contacts of this company",
                ),
            ("company" to "interviews") to
                Label(
                    UNLINKED,
                    "interview loses participants of this company",
                    "interviews lose participants of this company",
                ),
            ("company" to "tasks") to Label(UNLINKED, "task loses its link", "tasks lose their link"),
            ("contact" to "applications") to
                Label(UNLINKED, "application loses this contact", "applications lose this contact"),
            ("contact" to "interviews") to
                Label(UNLINKED, "interview loses this participant", "interviews lose this participant"),
            ("contact" to "tasks") to Label(UNLINKED, "task loses its link", "tasks lose their link"),
        )
}
