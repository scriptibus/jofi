// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.web

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class SpaFallbackResourceResolverTest {
    @TempDir
    lateinit var root: Path

    private fun resolve(path: String): Resource? {
        root.resolve("index.html").writeText("<!doctype html>")
        root
            .resolve("assets")
            .createDirectories()
            .resolve("app.js")
            .writeText("app")
        val resolver = TestableResolver()
        return resolver.resolve(path, FileSystemResource("$root/"))
    }

    @Test
    fun `existing files are served as they are`() {
        resolve("assets/app.js")?.filename shouldBe "app.js"
    }

    @Test
    fun `client routes get index html`() {
        resolve("applications")?.filename shouldBe "index.html"
        resolve("settings/password")?.filename shouldBe "index.html"
        resolve("share")?.filename shouldBe "index.html"
    }

    @Test
    fun `API, actuator and missing assets stay not found`() {
        resolve("api/does-not-exist") shouldBe null
        resolve("api") shouldBe null
        resolve("API/x") shouldBe null
        resolve("Api") shouldBe null
        resolve("ACTUATOR/env") shouldBe null
        resolve("actuator/env") shouldBe null
        resolve("assets/missing.js") shouldBe null
        resolve("manifest.json") shouldBe null
    }

    @Test
    fun `route detection ignores a leading slash and looks at the last segment only`() {
        SpaFallbackResourceResolver.isClientRoute("/tasks") shouldBe true
        SpaFallbackResourceResolver.isClientRoute("v1.2/tasks") shouldBe true
        SpaFallbackResourceResolver.isClientRoute("/api/auth/session") shouldBe false
        SpaFallbackResourceResolver.isClientRoute("apiary") shouldBe true
        resolve("apiary") shouldNotBe null
    }

    private class TestableResolver : SpaFallbackResourceResolver() {
        fun resolve(
            path: String,
            location: Resource,
        ): Resource? = getResource(path, location)
    }
}
