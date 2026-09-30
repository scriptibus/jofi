// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.api.DescribeApplicationEventPort
import io.github.scriptibus.jofi.applications.application.port.api.DescribeApplicationEventPort.ApplicationEvent
import io.github.scriptibus.jofi.applications.application.port.api.DescribeApplicationEventPort.Kind
import io.github.scriptibus.jofi.applications.domain.ApplicationStatusChanged
import io.github.scriptibus.jofi.applications.domain.InterviewRescheduled
import io.github.scriptibus.jofi.applications.domain.InterviewScheduled
import io.github.scriptibus.jofi.shared.domain.DomainEvent

/** Names the applications context's events for other contexts (#95), as plain values; any other event is `null`. */
class DescribeApplicationEventUseCase : DescribeApplicationEventPort {
    override fun execute(event: DomainEvent): ApplicationEvent? =
        when (event) {
            is ApplicationStatusChanged -> ApplicationEvent(Kind.STATUS_CHANGED, event.application.value)
            is InterviewScheduled -> ApplicationEvent(Kind.INTERVIEW_SCHEDULED, event.application.value)
            is InterviewRescheduled -> ApplicationEvent(Kind.INTERVIEW_RESCHEDULED, event.application.value)
            else -> null
        }
}
