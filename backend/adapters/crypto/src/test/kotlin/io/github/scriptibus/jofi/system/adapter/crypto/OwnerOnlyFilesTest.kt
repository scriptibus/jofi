// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.crypto

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.io.path.name

class OwnerOnlyFilesTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `processes creating the same file at once agree on one winner and leave no temporary files`() {
        val target = directory.resolve("secrets/key")

        val writers =
            List(20) { index -> Callable { OwnerOnlyFiles.createIfAbsent(target, "writer $index".toByteArray()) } }

        val created =
            Executors.newVirtualThreadPerTaskExecutor().use { pool ->
                pool
                    .invokeAll(
                        writers,
                    ).map { it.get() }
            }

        created.count { it } shouldBe 1
        Files.readString(target).startsWith("writer ") shouldBe true
        Files.list(target.parent).use { files -> files.map { it.name }.toList() } shouldContainExactly listOf("key")
    }
}
