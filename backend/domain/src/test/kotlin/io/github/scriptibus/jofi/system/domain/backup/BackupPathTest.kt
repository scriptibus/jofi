// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.domain.backup

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class BackupPathTest {
    @ParameterizedTest
    @ValueSource(
        strings = [
            "manifest.json",
            "secrets/master-keyset.json",
            "database/changelog_entry.csv",
            "files/knowledge/profile.md",
            "files/knowledge/.git/refs/heads/main",
            "files/documents/cv/Lebenslauf 2026 (final).pdf",
        ],
    )
    fun `paths of the format are accepted`(name: String) {
        BackupPath.parse(name)?.value shouldBe name
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "/etc/passwd",
            "../outside",
            "files/knowledge/../../outside",
            "files/knowledge/./x",
            "files/knowledge//x",
            "files/knowledge/",
            "files/knowledge",
            "files\\knowledge\\x",
            "files/knowledge/a\\..\\..\\x",
            "files/knowledge/line\nbreak",
            "files/other/x",
            "secrets/setup-token",
            "database/Changelog.csv",
            "database/../x.csv",
            "C:/windows/x",
            "manifest.json/",
        ],
    )
    fun `anything else, and every escaping path, is refused`(name: String) {
        BackupPath.parse(name).shouldBeNull()
    }

    @Test
    fun `overlong names and segments are refused`() {
        BackupPath.parse("files/knowledge/" + "a".repeat(256)).shouldBeNull()
        BackupPath.parse("files/knowledge/" + "a/".repeat(600) + "x").shouldBeNull()
    }

    @Test
    fun `builders produce the format's paths`() {
        BackupPath.table("secret").value shouldBe "database/secret.csv"
        BackupPath.dataFile("knowledge", "a/b.md")?.value shouldBe "files/knowledge/a/b.md"
        BackupPath.dataFile("secrets", "master-keyset.json").shouldBeNull()
        BackupPath.dataFile("knowledge", "../x").shouldBeNull()
        shouldThrow<IllegalArgumentException> { BackupPath.table("x; drop table") }
    }

    @Test
    fun `data files are told apart`() {
        BackupPath.parse("files/documents/x.pdf")?.isDataFile shouldBe true
        BackupPath.KEYSET.isDataFile shouldBe false
    }
}
