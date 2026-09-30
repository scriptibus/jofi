// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain

/**
 * Something that happened in one context that others may react to (ADR-0005), published through
 * `DomainEventPort`. Events carry ids and states, never personal data of third parties (contacts).
 */
interface DomainEvent
