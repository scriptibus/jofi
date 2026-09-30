// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.fixture.application.port.inbound

// Known-good and known-bad inbound ports for InboundPortRulesTest (never part of the production scope).

/** Good: implemented by exactly `GoodThingUseCase`. */
interface GoodThingPort {
    fun execute(): String
}

/** Bad: nobody implements it, and it is not awaiting a use case. */
interface LonelyThingPort {
    fun execute(): String
}

/** Bad: implemented by its use case and by an adapter. */
interface SharedThingPort {
    fun execute(): String
}

/** Bad: implemented by a use case of another name. */
interface MisnamedThingPort {
    fun execute(): String
}

/** Good while it is listed as awaiting its use case. */
interface PendingThingPort {
    fun execute(): String
}
