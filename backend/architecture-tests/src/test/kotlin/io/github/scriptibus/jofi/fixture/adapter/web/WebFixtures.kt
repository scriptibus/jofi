// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.fixture.adapter.web

import io.github.scriptibus.jofi.setup.application.port.AiProviderPort
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables

/** Known-bad: a web adapter using the generated jOOQ code. Test fixture only, never production. */
class JooqInWebAdapterFixture {
    val tables: Class<*> = Tables::class.java
}

/** Known-bad: an adapter outside `setup.adapter.ai` calling the provider-facing AI port directly. */
class AiProviderPortInWebAdapterFixture(
    val provider: AiProviderPort,
)
