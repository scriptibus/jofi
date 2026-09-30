// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.application.port.api

import io.github.scriptibus.jofi.shared.domain.ai.AiTask

/**
 * Whether a model is assigned to an AI task, asked by other contexts before they queue AI work the user waits for
 * (the posting import, #96), so the user hears "set up AI first" at once instead of from a failed job. Part of the
 * named interface `api` of the setup context. Only plain values cross it; it reads only and never throws. An
 * assignment says nothing about the provider being reachable: the call itself can still fail.
 */
interface CheckAiTaskAssignedPort {
    fun execute(task: AiTask): Assignment

    /** Outcome of [execute]. A sealed class, since every interface in a port package is a port. */
    @Suppress("AbstractClassCanBeInterface")
    sealed class Assignment {
        data object Assigned : Assignment()

        data object NotAssigned : Assignment()

        /** The assignments could not be read. */
        data object Unavailable : Assignment()
    }
}
