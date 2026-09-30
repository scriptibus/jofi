// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// Cryptography (ADR-0017): argon2id password hashing, the one-time setup token and the Tink AES-GCM
// master keyset that encrypts stored secrets. A protected path (AGENTS.md section 8).

plugins {
    id("jofi.spring-conventions")
}

dependencies {
    implementation(project(":application"))
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.security.crypto)
    // Argon2PasswordEncoder computes argon2id with Bouncy Castle.
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.tink)
}
