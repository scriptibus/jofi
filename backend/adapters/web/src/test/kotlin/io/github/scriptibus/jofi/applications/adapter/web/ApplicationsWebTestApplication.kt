// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import org.springframework.boot.autoconfigure.SpringBootApplication

/**
 * Test-only configuration root for the `applications` web slices: `@WebMvcTest` looks for one in the
 * test's package and its parents, and the real application class lives in bootstrap.
 */
@SpringBootApplication
class ApplicationsWebTestApplication
