// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.ai

/**
 * Who reads the text of a list entry (ADR-0056). The user sees their own notes as they are; an AI (every MCP client)
 * sees them only after the "never send to AI" values were taken out, and before an excerpt is cut, so an excerpt can
 * never end inside a flagged value and show its first characters.
 */
enum class NotesAudience { USER, AI }
