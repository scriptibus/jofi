// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.application.port

/**
 * Caps how many callers run a kind of expensive work at once, such as fetching a page for a URL import (#224):
 * each running call may buffer a response and take a thread for as long as the fetch timeout. A call over the cap is
 * turned away at once instead of queueing, so a burst cannot pile up threads or memory. The cap is per process, which
 * is the whole app: only the `app` container serves requests (ADR-0039).
 */
interface ConcurrencyLimitPort {
    /**
     * Runs [work] if fewer than the cap of calls are running, and gives its permit back however it ends (result,
     * failure result or exception). Otherwise answers [onFull] without waiting and runs nothing.
     */
    fun <T> runIfFree(
        onFull: () -> T,
        work: () -> T,
    ): T
}
