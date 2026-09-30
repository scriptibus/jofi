// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import org.springframework.boot.fromApplication
import org.springframework.boot.with

/**
 * Runs the app against a throwaway PostgreSQL container: `./gradlew :bootstrap:bootTestRun`.
 * Spreading `args` copies the array once at startup, so detekt's performance rule does not apply.
 */
@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    fromApplication<JofiApplication>().with(PostgresTestConfiguration::class).run(*args)
}
