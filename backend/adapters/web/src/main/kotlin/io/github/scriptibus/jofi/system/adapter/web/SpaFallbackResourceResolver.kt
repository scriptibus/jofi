// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.web

import org.springframework.core.io.Resource
import org.springframework.web.servlet.resource.PathResourceResolver

/**
 * Serves the SPA's `index.html` for its client-side routes (`/applications`, `/share?url=…`), so a
 * reload or a deep link opens the app instead of a 404. Existing files are served as they are. A
 * path under `api/` or `actuator/`, or one whose last segment has a file extension (a missing
 * asset), stays a 404: the API keeps its problem details and a broken asset never turns into HTML.
 */
open class SpaFallbackResourceResolver : PathResourceResolver() {
    override fun getResource(
        resourcePath: String,
        location: Resource,
    ): Resource? =
        super.getResource(resourcePath, location)
            ?: if (isClientRoute(resourcePath)) super.getResource(INDEX, location) else null

    companion object {
        const val INDEX = "index.html"
        private val SERVER_PREFIXES = listOf("api/", "actuator/")

        /** Whether [resourcePath] (relative, without a leading slash) is a route of the SPA. */
        fun isClientRoute(resourcePath: String): Boolean {
            val path = resourcePath.trimStart('/')
            val isServerPath = SERVER_PREFIXES.any { path.startsWith(it) } || path == "api" || path == "actuator"
            return !isServerPath && !path.substringAfterLast('/').contains('.')
        }
    }
}
