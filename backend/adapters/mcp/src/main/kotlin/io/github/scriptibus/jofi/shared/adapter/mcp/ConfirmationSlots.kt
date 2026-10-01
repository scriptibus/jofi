// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.mcp

/**
 * How many delete confirmations may wait for a person at once: one per MCP session and at most [limit] for the
 * whole server. A waiting tool call parks a server thread for up to the confirmation timeout, so without a bound
 * a client opening sessions could stall every other MCP call. The MCP SDK does not tell us when a session is
 * closed, so a slot is freed when the wait ends (answer, timeout or failure), not earlier. Taking a slot is
 * atomic: of several concurrent calls only one gets it, before any confirmation token is issued.
 */
class ConfirmationSlots(
    private val limit: Int,
) {
    private val waiting = mutableSetOf<String>()

    init {
        require(limit >= 1) { "At least one confirmation must be allowed to wait" }
    }

    /** A lease on one slot for [session], or null if the session already waits or the server is at its limit. */
    fun tryReserve(session: String): AutoCloseable? =
        synchronized(waiting) {
            if (waiting.size >= limit || !waiting.add(session)) {
                null
            } else {
                AutoCloseable { synchronized(waiting) { waiting.remove(session) } }
            }
        }

    companion object {
        const val DEFAULT_LIMIT = 4
    }
}
