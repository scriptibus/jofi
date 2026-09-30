// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class CompanyInputTest {
    @Test
    fun `valid input becomes trimmed details with blank optional fields absent`() {
        val input =
            CompanyInput(
                name = "  ACME GmbH ",
                website = " https://acme.example ",
                industry = " ",
                size = CompanySize.MEDIUM,
                locations = listOf(" Berlin ", "", "berlin", "Remote"),
                careersPage = "https://jobs.example/acme?team=backend#open",
                researchNotes = "\n# Notes\nFriendly.\n",
            )

        val details = valid(input)

        details shouldBe
            CompanyDetails(
                name = "ACME GmbH",
                website = WebAddress("https://acme.example"),
                industry = null,
                size = CompanySize.MEDIUM,
                locations = listOf("Berlin", "Remote"),
                careersPage = WebAddress("https://jobs.example/acme?team=backend#open"),
                researchNotes = "# Notes\nFriendly.",
            )
    }

    @Test
    fun `every problem is reported at once, per field`() {
        val input =
            CompanyInput(
                name = " ",
                website = "not a url",
                industry = "x".repeat(CompanyDetails.MAX_INDUSTRY_LENGTH + 1),
                locations = (0..CompanyDetails.MAX_LOCATIONS).map { "City $it" },
                careersPage = "ftp://jobs.example",
                researchNotes = "x".repeat(CompanyDetails.MAX_NOTES_LENGTH + 1),
            )

        val violations = input.validate().shouldBeInstanceOf<CompanyValidation.Invalid>().violations

        violations shouldContainExactlyInAnyOrder
            listOf(
                CompanyViolation(CompanyField.NAME, ViolationKind.REQUIRED),
                CompanyViolation(CompanyField.WEBSITE, ViolationKind.INVALID_URL),
                CompanyViolation(CompanyField.INDUSTRY, ViolationKind.TOO_LONG),
                CompanyViolation(CompanyField.LOCATIONS, ViolationKind.TOO_MANY),
                CompanyViolation(CompanyField.CAREERS_PAGE, ViolationKind.INVALID_URL),
                CompanyViolation(CompanyField.RESEARCH_NOTES, ViolationKind.TOO_LONG),
            )
    }

    @Test
    fun `names and locations have a length limit`() {
        val long = "x".repeat(CompanyDetails.MAX_NAME_LENGTH + 1)

        CompanyInput(long).validate() shouldBe
            CompanyValidation.Invalid(listOf(CompanyViolation(CompanyField.NAME, ViolationKind.TOO_LONG)))
        CompanyInput("ACME", locations = listOf(long)).validate() shouldBe
            CompanyValidation.Invalid(listOf(CompanyViolation(CompanyField.LOCATIONS, ViolationKind.TOO_LONG)))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "acme.example",
            "/careers",
            "mailto:jobs@acme.example",
            "javascript:alert(1)",
            "file:///etc/passwd",
            "https://user:secret@acme.example",
            "https:///no-host",
            "https://acme.example/with space",
        ],
    )
    fun `web addresses are absolute http(s) URLs with a host and without credentials`(raw: String) {
        WebAddress.parse(raw).shouldBeNull()
        CompanyInput("ACME", website = raw).validate() shouldBe
            CompanyValidation.Invalid(listOf(CompanyViolation(CompanyField.WEBSITE, ViolationKind.INVALID_URL)))
    }

    @Test
    fun `a web address has a length limit and prints as its URL`() {
        val tooLong = "https://acme.example/" + "x".repeat(WebAddress.MAX_LENGTH)

        WebAddress.parse(tooLong).shouldBeNull()
        shouldThrow<IllegalArgumentException> { WebAddress("ftp://acme.example") }
        WebAddress.parse("HTTP://ACME.example/Jobs").toString() shouldBe "HTTP://ACME.example/Jobs"
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://bücher.example/jobs",
            "https://xn--bcher-kva.example",
            "https://my_team.example/careers",
            "http://jobs.example:8080/list?q=kotlin&page=2#top",
            "https://jobs.example/@acme",
            "https://jobs.example/stellen/köln",
        ],
    )
    fun `internationalised and underscore hosts, ports, paths and queries are fine`(raw: String) {
        WebAddress.parse(raw)?.value shouldBe raw
    }

    @Test
    fun `a web address may be exactly as long as the limit`() {
        val prefix = "https://acme.example/"
        val longest = prefix + "x".repeat(WebAddress.MAX_LENGTH - prefix.length)

        WebAddress.parse(longest)?.value shouldBe longest
        WebAddress.parse(longest + "x").shouldBeNull()
        WebAddress.parse("https://acme.example:port").shouldBeNull()
    }

    @Test
    fun `text is normalized to NFC, so the same name typed twice is one string`() {
        val decomposed = "Mu\u0308ller GmbH" // "Müller" with a combining diaeresis
        val details = valid(CompanyInput(decomposed, locations = listOf("Ko\u0308ln", "Köln")))

        details.name shouldBe "Müller GmbH"
        details.locations shouldBe listOf("Köln")
    }

    @Test
    fun `locations that differ only in case are one location, by the root locale's lower case`() {
        val locations = listOf("Berlin", "BERLIN", "İstanbul", "istanbul")

        // "İstanbul".lowercase() is "i̇stanbul" (with a combining dot), so both spellings stay.
        valid(CompanyInput("ACME", locations = locations)).locations shouldBe listOf("Berlin", "İstanbul", "istanbul")
    }

    @Test
    fun `details guard their invariants against programming errors`() {
        shouldThrow<IllegalArgumentException> { CompanyDetails(" ACME") }
        shouldThrow<IllegalArgumentException> { CompanyDetails("ACME", industry = "") }
        shouldThrow<IllegalArgumentException> { CompanyDetails("ACME", locations = listOf("Berlin", "BERLIN")) }
        shouldThrow<IllegalArgumentException> { CompanyDetails("ACME", locations = listOf(" ")) }
        shouldThrow<IllegalArgumentException> { CompanyDetails("ACME", researchNotes = " ") }
    }

    @Test
    fun `a preference input keeps a trimmed reason for flags and drops it for none`() {
        PreferenceInput(PreferenceKind.BLACKLISTED, " Declined twice ").validate() shouldBe
            CompanyValidation.Valid(CompanyPreference.Blacklisted("Declined twice"))
        PreferenceInput(PreferenceKind.FAVOURITE, " ").validate() shouldBe
            CompanyValidation.Valid(CompanyPreference.Favourite(null))
        PreferenceInput(PreferenceKind.NONE, "x".repeat(CompanyPreference.MAX_REASON_LENGTH + 1)).validate() shouldBe
            CompanyValidation.Valid(CompanyPreference.None)
    }

    @Test
    fun `a preference reason has a length limit`() {
        val long = "x".repeat(CompanyPreference.MAX_REASON_LENGTH + 1)

        PreferenceInput(PreferenceKind.FAVOURITE, long).validate() shouldBe
            CompanyValidation.Invalid(listOf(CompanyViolation(CompanyField.PREFERENCE_REASON, ViolationKind.TOO_LONG)))
    }

    @Test
    fun `an invalid result names at least one violation`() {
        shouldThrow<IllegalArgumentException> { CompanyValidation.Invalid(emptyList()) }
    }

    private fun valid(input: CompanyInput): CompanyDetails =
        input.validate().shouldBeInstanceOf<CompanyValidation.Valid<CompanyDetails>>().value

    @Test
    fun `text with U+0000, which PostgreSQL cannot store, is rejected in every field`() {
        val nul = "\u0000"
        val input =
            CompanyInput(
                name = "AC${nul}ME",
                website = "https://acme.example/$nul",
                industry = "Robo${nul}tics",
                locations = listOf("Ber${nul}lin"),
                careersPage = "https://jobs.example/?q=$nul",
                researchNotes = "# Notes$nul",
            )

        input.validate().shouldBeInstanceOf<CompanyValidation.Invalid>().violations shouldContainExactlyInAnyOrder
            listOf(
                CompanyViolation(CompanyField.NAME, ViolationKind.INVALID_CHARACTER),
                CompanyViolation(CompanyField.WEBSITE, ViolationKind.INVALID_URL),
                CompanyViolation(CompanyField.INDUSTRY, ViolationKind.INVALID_CHARACTER),
                CompanyViolation(CompanyField.LOCATIONS, ViolationKind.INVALID_CHARACTER),
                CompanyViolation(CompanyField.CAREERS_PAGE, ViolationKind.INVALID_URL),
                CompanyViolation(CompanyField.RESEARCH_NOTES, ViolationKind.INVALID_CHARACTER),
            )
        PreferenceInput(PreferenceKind.FAVOURITE, "Gr${nul}eat").validate() shouldBe
            CompanyValidation.Invalid(
                listOf(CompanyViolation(CompanyField.PREFERENCE_REASON, ViolationKind.INVALID_CHARACTER)),
            )
        shouldThrow<IllegalArgumentException> { CompanyDetails("AC${nul}ME") }
        shouldThrow<IllegalArgumentException> { CompanyPreference.Blacklisted("No$nul") }
    }
}
