// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.fixture.adapter.web

import io.github.scriptibus.jofi.fixture.application.port.inbound.SharedThingPort

/** Known-bad: an adapter implementing an inbound port, which only its use case may (InboundPortRulesTest). */
class InboundPortInAdapterFixture : SharedThingPort {
    override fun execute(): String = "adapter"
}
