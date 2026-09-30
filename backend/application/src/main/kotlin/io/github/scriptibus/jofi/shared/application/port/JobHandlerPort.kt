// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.application.port

import io.github.scriptibus.jofi.shared.domain.job.JobOutcome
import io.github.scriptibus.jofi.shared.domain.job.JobType

/**
 * Runs the jobs of one [type] in the worker (ADR-0010). Inbound: the job adapter calls it for every
 * job of that type, the way a controller calls a use case. One implementation per job type, named
 * `*JobAdapter`, which reads the [arguments] (ids) and calls one use case. Implementations never
 * throw; an exception still counts as a retryable failure, recorded without its message.
 */
interface JobHandlerPort {
    val type: JobType

    fun run(arguments: Map<String, String>): JobOutcome
}
