// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.application.port

import io.github.scriptibus.jofi.shared.domain.DomainEvent

/**
 * Announces domain events to the other contexts (ADR-0005). Use cases publish inside the mutation's
 * transaction, after the store accepted the change; listeners react after the commit (Spring Modulith's
 * `@ApplicationModuleListener`), so a change that is rolled back announces nothing. Never throws.
 */
interface DomainEventPort {
    /** Publishes [event]; false if that failed, and the caller then rolls back. */
    fun publish(event: DomainEvent): Boolean
}
