// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.architecture

import io.github.scriptibus.jofi.JofiApplication
import io.kotest.matchers.collections.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

/**
 * Spring Modulith view: each bounded context (direct sub-package of the base package) is an
 * application module. Modulith works on packages, so a context spanning several Gradle modules
 * (domain, application, adapters, bootstrap) is still one application module here.
 */
class ModulithTest {
    private val modules = ApplicationModules.of(JofiApplication::class.java)

    @Test
    fun `bounded contexts are detected as application modules`() {
        modules.map { it.identifier.toString() } shouldContain "system"
    }

    @Test
    fun `application modules respect their boundaries`() {
        modules.verify()
    }
}
