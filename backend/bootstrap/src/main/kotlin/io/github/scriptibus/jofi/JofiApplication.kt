// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class JofiApplication

// Spreading `args` copies the array once at startup; detekt's performance rule does not apply.
@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    runApplication<JofiApplication>(*args)
}
