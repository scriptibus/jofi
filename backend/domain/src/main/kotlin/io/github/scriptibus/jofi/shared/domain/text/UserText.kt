// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.text

import java.text.Normalizer

// Text rules every context applies to what the user, the AI or an external client typed (ADR-0041).
// The companies context predates this file and keeps its own copies until it is next touched.

/** The text in Unicode NFC, so the same word typed on two systems is the same string. */
fun String.normalizedText(): String = Normalizer.normalize(this, Normalizer.Form.NFC)

/** The text normalized and trimmed, or `null` if nothing is left (a blank optional field is absent). */
fun String?.trimmedOrNull(): String? = this?.normalizedText()?.trim()?.takeIf(String::isNotEmpty)

/**
 * Whether the text contains a character the database cannot store: PostgreSQL `text` rejects U+0000,
 * so a domain that accepted it would produce entities that fail to store.
 */
fun String.hasUnstorableCharacter(): Boolean = '\u0000' in this

/** What is wrong with required or present optional [value] as stored text of at most [maxLength] characters. */
fun textProblem(
    value: String,
    maxLength: Int,
): TextProblem? =
    when {
        value.isBlank() || value != value.trim() -> TextProblem.BLANK_OR_UNTRIMMED
        value.hasUnstorableCharacter() -> TextProblem.UNSTORABLE_CHARACTER
        value.length > maxLength -> TextProblem.TOO_LONG
        else -> null
    }

/** Why a text cannot be stored as it is, in the order [textProblem] checks. */
enum class TextProblem { BLANK_OR_UNTRIMMED, UNSTORABLE_CHARACTER, TOO_LONG }
