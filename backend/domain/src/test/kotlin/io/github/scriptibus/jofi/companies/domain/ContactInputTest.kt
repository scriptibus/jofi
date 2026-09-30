// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.domain

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

class ContactInputTest {
    private val companyId = CompanyId(UUID.fromString("00000000-0000-0000-0000-00000000000c"))
    private val longLabel = "x".repeat(ContactChannel.MAX_LABEL_LENGTH + 1)

    @Test
    fun `valid input becomes trimmed NFC details without blank channels and exact duplicates`() {
        val input =
            ContactInput(
                name = "  Jörg Müller ",
                role = " ",
                company = companyId,
                channels =
                    listOf(
                        ChannelInput(ChannelKind.EMAIL, " jorg@acme.example ", " "),
                        ChannelInput(ChannelKind.PHONE, "  "),
                        ChannelInput(ChannelKind.EMAIL, "jorg@acme.example", "work"),
                        ChannelInput(ChannelKind.EMAIL, "JORG@acme.example"),
                        ChannelInput(ChannelKind.WEB, "https://www.linkedin.com/in/jorg", " LinkedIn "),
                    ),
                relationshipNotes = "\n# Met at the fair\n",
            )

        valid(input) shouldBe
            ContactDetails(
                name = "Jörg Müller",
                role = null,
                company = companyId,
                channels =
                    listOf(
                        ContactChannel(ChannelKind.EMAIL, "jorg@acme.example"),
                        ContactChannel(ChannelKind.EMAIL, "JORG@acme.example"),
                        ContactChannel(ChannelKind.WEB, "https://www.linkedin.com/in/jorg", "LinkedIn"),
                    ),
                relationshipNotes = "# Met at the fair",
            )
    }

    @Test
    fun `every problem is reported at once, channels by their position in the input`() {
        val input =
            ContactInput(
                name = " ",
                role = "x".repeat(ContactDetails.MAX_ROLE_LENGTH + 1),
                channels =
                    listOf(
                        ChannelInput(ChannelKind.PHONE, ""),
                        ChannelInput(ChannelKind.EMAIL, "erika.example"),
                        ChannelInput(ChannelKind.PHONE, "+49 30 1234567", longLabel),
                        ChannelInput(ChannelKind.WEB, "linkedin.com/in/erika"),
                        ChannelInput(ChannelKind.PHONE, "call me"),
                        ChannelInput(ChannelKind.OTHER, "x".repeat(ContactChannel.MAX_OTHER_LENGTH + 1)),
                    ),
                relationshipNotes = "x".repeat(ContactDetails.MAX_NOTES_LENGTH + 1),
            )

        input.validate().shouldBeInstanceOf<ContactValidation.Invalid>().violations shouldContainExactlyInAnyOrder
            listOf(
                ContactViolation(ContactField.NAME, ViolationKind.REQUIRED),
                ContactViolation(ContactField.ROLE, ViolationKind.TOO_LONG),
                ContactViolation(ContactField.CHANNEL_VALUE, ViolationKind.INVALID_EMAIL, 1),
                ContactViolation(ContactField.CHANNEL_LABEL, ViolationKind.TOO_LONG, 2),
                ContactViolation(ContactField.CHANNEL_VALUE, ViolationKind.INVALID_URL, 3),
                ContactViolation(ContactField.CHANNEL_VALUE, ViolationKind.INVALID_PHONE, 4),
                ContactViolation(ContactField.CHANNEL_VALUE, ViolationKind.TOO_LONG, 5),
                ContactViolation(ContactField.RELATIONSHIP_NOTES, ViolationKind.TOO_LONG),
            )
    }

    @Test
    fun `a contact has a bounded number of channels`() {
        val channels = (0..ContactDetails.MAX_CHANNELS).map { ChannelInput(ChannelKind.PHONE, "+49 30 $it") }

        ContactInput("Erika", channels = channels).validate() shouldBe
            ContactValidation.Invalid(listOf(ContactViolation(ContactField.CHANNELS, ViolationKind.TOO_MANY)))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "erika@acme.example",
            "Erika.Mustermann+jobs@acme.example",
            "jörg@bücher.example",
            "用户@例子.广告",
            "\"erika@home\"@acme.example",
            "erika@localhost",
        ],
    )
    fun `email addresses in any script are fine as long as an @ has text on both sides`(address: String) {
        ContactChannel.problemOf(ChannelKind.EMAIL, address).shouldBeNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["erika", "@acme.example", "erika@", "erika @acme.example", "erika@acme.example@"])
    fun `email addresses need an @ with text on both sides and no whitespace`(address: String) {
        ContactChannel.problemOf(ChannelKind.EMAIL, address) shouldBe ViolationKind.INVALID_EMAIL
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "+49 30 1234567",
            "030/123 45-67",
            "+49 (0)30 1234-567",
            "(555) 123.4567 ext. 89",
            "1-800-FLOWERS",
            "٠١٢٣٤٥٦٧٨٩",
            "+44 20 7946 0958",
        ],
    )
    fun `phone numbers in every national format are fine`(number: String) {
        ContactChannel.problemOf(ChannelKind.PHONE, number).shouldBeNull()
    }

    @Test
    fun `phone numbers need a digit, no control characters and a bounded length`() {
        ContactChannel.problemOf(ChannelKind.PHONE, "call me") shouldBe ViolationKind.INVALID_PHONE
        ContactChannel.problemOf(ChannelKind.PHONE, "030\u0000123") shouldBe ViolationKind.INVALID_PHONE
        ContactChannel.problemOf(ChannelKind.PHONE, "1".repeat(ContactChannel.MAX_PHONE_LENGTH + 1)) shouldBe
            ViolationKind.TOO_LONG
        ContactChannel.problemOf(ChannelKind.PHONE, "1".repeat(ContactChannel.MAX_PHONE_LENGTH)).shouldBeNull()
    }

    @Test
    fun `every kind has its length limit and needs a trimmed value`() {
        val email = "e".repeat(ContactChannel.MAX_EMAIL_LENGTH - "@acme.example".length) + "@acme.example"
        ContactChannel.problemOf(ChannelKind.EMAIL, email).shouldBeNull()
        ContactChannel.problemOf(ChannelKind.EMAIL, "e$email") shouldBe ViolationKind.TOO_LONG
        ContactChannel.problemOf(ChannelKind.OTHER, "o".repeat(ContactChannel.MAX_OTHER_LENGTH)).shouldBeNull()
        ContactChannel.problemOf(ChannelKind.WEB, "https://x.example/" + "w".repeat(WebAddress.MAX_LENGTH)) shouldBe
            ViolationKind.INVALID_URL
        ContactChannel.problemOf(ChannelKind.OTHER, " @erika") shouldBe ViolationKind.REQUIRED
        ContactChannel.problemOf(ChannelKind.OTHER, "Signal: @erika.01").shouldBeNull()
    }

    private fun valid(input: ContactInput): ContactDetails =
        input.validate().shouldBeInstanceOf<ContactValidation.Valid<ContactDetails>>().value
}
