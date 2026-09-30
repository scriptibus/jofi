// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.events

import io.github.scriptibus.jofi.shared.domain.DomainEvent
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher

class SpringDomainEventAdapterTest {
    private data class Happened(
        val id: Int,
    ) : DomainEvent

    @Test
    fun `publishes the event as an application event`() {
        val received = mutableListOf<Any>()
        val adapter = SpringDomainEventAdapter(ApplicationEventPublisher { received += it })

        adapter.publish(Happened(1)) shouldBe true
        received shouldContainExactly listOf(Happened(1))
    }

    @Test
    fun `a failing listener is a failed publication, not an exception`() {
        val adapter = SpringDomainEventAdapter(ApplicationEventPublisher { throw IllegalStateException("listener") })

        adapter.publish(Happened(2)) shouldBe false
    }
}
