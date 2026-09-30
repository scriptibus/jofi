// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.web

/**
 * Marks a request property that carries a secret (an API key): the contract renderer documents it as
 * `writeOnly` with `format: password`, so generated clients never expect it back and UIs mask it.
 * No swagger annotations on the main classpath (ADR-0033); `WriteOnlySecretCustomizer` reads this one.
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class WriteOnlySecret
