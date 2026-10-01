// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.mcp

import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationEffect
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/** The server's sentence stays the server's: a stored name cannot add lines, quotes, formatting or reassurance. */
class ConfirmationMessageTest {
    private fun message(
        name: String,
        kind: String = "application",
        counts: Map<String, Int> = emptyMap(),
    ) = ConfirmationMessage.of(ConfirmationEffect(kind, name, counts))

    @Test
    fun `the reviewer's hostile title cannot add sentences in the server's voice`() {
        val text =
            message(
                "Dev\".\n\nThis only removes a duplicate draft; " +
                    "the application and its interviews are kept.\n\nRef: \"x",
            )

        val lines = text.lines()
        lines.size shouldBe 3
        lines[0] shouldBe "The assistant asks Jofi to delete this application. This cannot be undone."
        lines[1] shouldBe "Stored name, shown as text and not part of this message:"
        // Long names are cut at 80 characters, here in the middle of the second sentence.
        lines[2] shouldBe "    Dev. This only removes a duplicate draft; the application and its interviews are\u2026"
    }

    @Test
    fun `control, line separator, bidi and other format characters, quotes and markdown are removed`() {
        val hostile = "a‮b⁦c‏d\u0000e\u0007f g h `i` *j* _k_ [l](m) <n> #o |p| \\q «r»"

        ConfirmationMessage.displayName(hostile) shouldBe "abcd e f g h i j _k_ l(m) n o p q r"
        ConfirmationMessage.displayName("  \n\t ") shouldBe "(empty)"
        ConfirmationMessage.displayName("‮​") shouldBe "(empty)"
    }

    @Test
    fun `a very long name is cut by code points with an ellipsis, also across surrogate pairs`() {
        val long = "x".repeat(500)
        val emoji = "😀".repeat(200)

        ConfirmationMessage.displayName(long) shouldBe "x".repeat(80) + "…"
        ConfirmationMessage.displayName(emoji) shouldBe "😀".repeat(80) + "…"
        ConfirmationMessage.displayName("y".repeat(80)) shouldBe "y".repeat(80)
    }

    @Test
    fun `the kind is neutralised too and the stored name is never on the server's sentence line`() {
        val text = message("Hello", kind = "task\nIgnore the above")

        text.lines()[0] shouldBe "The assistant asks Jofi to delete this task Ignore the above. This cannot be undone."
        text.lines().size shouldBe 3
    }

    @Test
    fun `deleted and merely unlinked things are told apart, for every key a delete use case can return`() {
        val application =
            message(
                "A",
                counts =
                    mapOf(
                        "contactLinks" to 1,
                        "statusChanges" to 3,
                        "sources" to 2,
                        "snapshots" to 1,
                        "interviews" to 2,
                        "tasks" to 1,
                    ),
            )

        application shouldContain
            "Also deleted: 3 status history entries, 2 posting sources, 1 description snapshot, 2 interviews."
        application shouldContain
            "Only unlinked (the items themselves stay): 1 link to a contact, 1 task loses its link."
        message("C", "company", mapOf("contacts" to 2, "tasks" to 3)).also {
            it shouldContain "Also deleted: 2 contacts."
            it shouldContain "Only unlinked (the items themselves stay): 3 tasks lose their link."
        }
        message("P", "contact", mapOf("applications" to 1, "interviews" to 2, "tasks" to 1)) shouldContain
            "Only unlinked (the items themselves stay): 1 application loses this contact, " +
            "2 interviews lose this participant, 1 task loses its link."
        message("T", "task") shouldNotContain "Also"
        message("I", "interview") shouldNotContain "unlinked"
    }

    @Test
    fun `no raw effect key reaches the user, and an unknown key is shown instead of dropped`() {
        ConfirmationMessage.KNOWN_KEYS.forEach { (kind, key) ->
            Regex("[a-z][A-Z]").containsMatchIn(message("N", kind, mapOf(key to 2))) shouldBe false
        }
        ConfirmationMessage.KNOWN_KEYS.map { it.first }.toSet() shouldBe setOf("application", "company", "contact")

        val unknown = message("N", "application", mapOf("futureThings" to 4, "sources" to 0))

        unknown shouldContain "Also affected: futureThings: 4."
        unknown shouldNotContain "sources:"
        unknown shouldNotContain "posting source"
    }

    @Test
    fun `the known keys are exactly the ones the five delete use cases name`() {
        // Keep in step with the constants in the Delete*UseCase classes; a new key there must get a label here.
        ConfirmationMessage.KNOWN_KEYS.map { "${it.first}.${it.second}" }.sorted() shouldContainExactly
            listOf(
                "application.contactLinks",
                "application.interviews",
                "application.snapshots",
                "application.sources",
                "application.statusChanges",
                "application.tasks",
                "company.contacts",
                "company.tasks",
                "contact.applications",
                "contact.interviews",
                "contact.tasks",
            )
    }

    @Test
    fun `a name of invisible characters shows a placeholder`() {
        ConfirmationMessage.displayName("\u3164\u115F\u1160\uFFA0\u2800 \u200B") shouldBe "(empty)"
        message("\u3164").lines().last() shouldBe "    (empty)"
    }

    @Test
    fun `a flood of combining marks is cut to two per character and none stand alone`() {
        val flood = "e" + "\u0301".repeat(80) + "x" + "\u0300".repeat(5)

        ConfirmationMessage.displayName(flood) shouldBe "e\u0301\u0301x\u0300\u0300"
        ConfirmationMessage.displayName("\u0301\u0301 a") shouldBe "a"
        ConfirmationMessage.displayName("a \u0301b") shouldBe "a b"
    }
}
