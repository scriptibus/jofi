// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application.port.api

import io.github.scriptibus.jofi.shared.domain.DomainEvent
import java.util.UUID

/**
 * Tells other contexts which of the applications context's domain events they received and what it is about, as
 * plain values, so they can react to them without depending on its domain (#95: the tasks context updates its
 * suggestions). Part of the named interface `api`. Never throws.
 */
interface DescribeApplicationEventPort {
    /** What [event] is, or `null` if it is not one of the applications context's events listed in [Kind]. */
    fun execute(event: DomainEvent): ApplicationEvent?

    /** An event of the kind [kind] about the application [application]. */
    data class ApplicationEvent(
        val kind: Kind,
        val application: UUID,
    )

    enum class Kind {
        /** `ApplicationStatusChanged`: the status moved (or a decline reason was corrected). */
        STATUS_CHANGED,

        /** `InterviewScheduled`: an interview was logged. */
        INTERVIEW_SCHEDULED,

        /** `InterviewRescheduled`: an interview now starts at another instant. */
        INTERVIEW_RESCHEDULED,
    }
}
