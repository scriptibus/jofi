// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.config

import io.github.scriptibus.jofi.system.adapter.web.SpaFallbackResourceResolver
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Serves the SPA from `classpath:/static/` (the frontend build, see the Dockerfile) and answers its
 * client-side routes with `index.html`, so deep links and the share target (`/share?url=…`) work on
 * the first load, before the service worker exists. Replaces Spring Boot's default catch-all static
 * resource handler, which steps aside when the pattern is already mapped.
 */
@Configuration(proxyBeanMethods = false)
class SpaConfiguration : WebMvcConfigurer {
    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        registry
            .addResourceHandler("/**")
            .addResourceLocations("classpath:/static/")
            .resourceChain(true)
            .addResolver(SpaFallbackResourceResolver())
    }
}
