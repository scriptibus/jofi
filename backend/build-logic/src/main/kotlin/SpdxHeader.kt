// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

/** The SPDX header Spotless enforces on every Kotlin file (sources and Gradle scripts). */
object SpdxHeader {
    // REUSE-IgnoreStart
    const val TEXT: String =
        "// SPDX-FileCopyrightText: 2026 Jofi contributors\n" +
            "// SPDX-License-Identifier: AGPL-3.0-or-later\n"
    // REUSE-IgnoreEnd

    /**
     * Where the header ends: the first line that is not an SPDX line. Spotless's default Kotlin
     * delimiter (`package`/`import`/`@file`) would swallow the explanatory comments Gradle
     * scripts start with, and it fails on files without a package (build-logic).
     */
    const val DELIMITER: String = "(?!// SPDX-)"
}
