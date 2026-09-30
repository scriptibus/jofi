// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.events

import io.github.scriptibus.jofi.shared.application.port.DomainEventPort
import io.github.scriptibus.jofi.shared.domain.DomainEvent
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

/**
 * Domain events as Spring application events. Transactional listeners receive them only on commit; a
 * synchronous listener that throws fails the publication, and the use case rolls back. Only the event
 * type is logged, never its content.
 */
@Component
class SpringDomainEventAdapter(
    private val publisher: ApplicationEventPublisher,
) : DomainEventPort {
    override fun publish(event: DomainEvent): Boolean =
        try {
            publisher.publishEvent(event)
            true
        } catch (exception: RuntimeException) {
            log.error("Publishing {} failed: {}", event.javaClass.simpleName, exception.javaClass.name)
            false
        }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(SpringDomainEventAdapter::class.java)
    }
}
