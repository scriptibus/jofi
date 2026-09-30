// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.net

import java.lang.ref.Cleaner

/**
 * Defence in depth for the SDK bridges, like the SDKs' own `PhantomReachable*` wrappers: an SDK
 * response that nobody closes (e.g. it completed a future whose consumer was already gone, which a
 * cancelled Anthropic stream can cause) is closed when it becomes unreachable, so its pooled
 * connection is not leased forever. Closing the response normally runs the same action at once.
 */
internal object UnclosedResponses {
    private val CLEANER: Cleaner = Cleaner.create()

    /** Registers [response] for [owner]; the result's `clean()` closes it exactly once. */
    fun register(
        owner: Any,
        response: AiResponse,
    ): Cleaner.Cleanable = CLEANER.register(owner, response::close)
}
