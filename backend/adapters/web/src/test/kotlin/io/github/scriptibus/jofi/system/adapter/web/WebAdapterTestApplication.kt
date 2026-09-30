// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.web

import org.springframework.boot.autoconfigure.SpringBootApplication

/**
 * Test-only configuration root so `@WebMvcTest` slices can start in this module, which has no
 * application class of its own (the real one lives in bootstrap).
 */
@SpringBootApplication
class WebAdapterTestApplication
