// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// Guards the package-manager pin and supply-chain settings, which CI, the Dockerfile (Corepack)
// and pnpm itself all read. A drift here silently weakens the supply-chain checks.

import { describe, expect, it } from "vitest";
import pkg from "../package.json";
import lockfile from "../pnpm-lock.yaml?raw";
import workspace from "../pnpm-workspace.yaml?raw";

// Corepack verifies the downloaded tarball against this hex SHA-512 before running it.
const PINNED_PNPM = /^pnpm@(\d+\.\d+\.\d+)\+sha512\.[0-9a-f]{128}$/;

/** Items of a top-level YAML block list, e.g. `key:\n  - "a"\n  - "b"`. */
function listSetting(yaml: string, key: string): string[] {
  const lines = (yaml.split(`\n${key}:\n`)[1] ?? "").split("\n");
  const end = lines.findIndex((line) => !line.startsWith("  - "));
  return lines.slice(0, end === -1 ? lines.length : end).map((line) => line.slice(4).replaceAll('"', ""));
}

describe("package manager pin", () => {
  it("pins pnpm to an exact version with an integrity hash", () => {
    expect(pkg.packageManager).toMatch(PINNED_PNPM);
  });

  it("matches the pnpm version recorded in the lockfile", () => {
    const version = PINNED_PNPM.exec(pkg.packageManager)?.[1];
    expect(lockfile).toMatch(
      new RegExp(`packageManagerDependencies:\\n\\s+pnpm:\\n\\s+specifier: ${version}\\n`),
    );
  });
});

describe("pnpm supply-chain settings", () => {
  it("keeps the 7-day release age, strict builds and the script allowlist", () => {
    expect(workspace).toMatch(/^minimumReleaseAge: 10080\b/m);
    expect(workspace).toMatch(/^strictDepBuilds: true$/m);
    expect(workspace).toMatch(/^allowBuilds:/m);
  });

  it("only exempts exact versions from the release age, never whole packages", () => {
    const exclusions = listSetting(workspace, "minimumReleaseAgeExclude");
    expect(exclusions).not.toContain("");
    for (const entry of exclusions) {
      expect(entry).toMatch(/^@?[^@*\s]+@\d+\.\d+\.\d+$/);
    }
  });
});
