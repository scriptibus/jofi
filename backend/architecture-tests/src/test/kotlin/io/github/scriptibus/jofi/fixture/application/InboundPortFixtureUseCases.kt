// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.fixture.application

import io.github.scriptibus.jofi.fixture.application.port.inbound.GoodThingPort
import io.github.scriptibus.jofi.fixture.application.port.inbound.MisnamedThingPort
import io.github.scriptibus.jofi.fixture.application.port.inbound.SharedThingPort

// Implementors of the inbound port fixtures (InboundPortRulesTest).

class GoodThingUseCase : GoodThingPort {
    override fun execute(): String = "good"
}

class SharedThingUseCase : SharedThingPort {
    override fun execute(): String = "use case"
}

class OtherThingUseCase : MisnamedThingPort {
    override fun execute(): String = "misnamed"
}
