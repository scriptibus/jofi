// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// License gate (docs/spec/04-tech-stack-proposal.md, 4.6a): every installed package
// must carry a license compatible with Jofi's AGPL-3.0. Anything outside the allowlist
// fails until a human reviews it and records it in license-exceptions.json.
//
// Run: node scripts/check-licenses.ts   (Node 24 strips the types natively)

import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";

const ALLOWED = new Set([
  "MIT",
  "Apache-2.0",
  "BSD-2-Clause",
  "BSD-3-Clause",
  "ISC",
  "MPL-2.0",
  "OFL-1.1",
  "0BSD",
  "CC0-1.0",
  "BlueOak-1.0.0",
  "LGPL-2.1",
  "LGPL-2.1-only",
  "LGPL-2.1-or-later",
  "LGPL-3.0",
  "LGPL-3.0-only",
  "LGPL-3.0-or-later",
  "GPL-3.0",
  "GPL-3.0-only",
  "GPL-3.0-or-later",
  "AGPL-3.0",
  "AGPL-3.0-only",
  "AGPL-3.0-or-later",
]);

interface PackageEntry {
  name: string;
  versions: string[];
}

interface Exceptions {
  /** Reviewed license expressions, allowed for every package: expression -> reason. */
  licenses: Record<string, string>;
  /** Reviewed single packages (`prefix-*` matches per-platform binaries). */
  packages: Record<string, { license: string; reason: string }>;
}

const exceptions = JSON.parse(
  readFileSync(new URL("./license-exceptions.json", import.meta.url), "utf8"),
) as Exceptions;

const reviewedLicenses = new Set(Object.keys(exceptions.licenses));

/** Minimal SPDX expression check: OR needs one allowed side, AND needs both. */
function isAllowed(expression: string): boolean {
  const expr = expression
    .trim()
    .replace(/^\((.*)\)$/, "$1")
    .trim();
  if (ALLOWED.has(expr) || reviewedLicenses.has(expr)) return true;
  if (/\sOR\s/.test(expr)) return expr.split(/\s+OR\s+/).some(isAllowed);
  if (/\sAND\s/.test(expr)) return expr.split(/\s+AND\s+/).every(isAllowed);
  return false;
}

function findPackageException(name: string): string | undefined {
  return Object.keys(exceptions.packages).find((key) =>
    key.endsWith("*") ? name.startsWith(key.slice(0, -1)) : name === key,
  );
}

const output = execFileSync("pnpm", ["licenses", "list", "--json"], {
  encoding: "utf8",
  maxBuffer: 64 * 1024 * 1024,
});
const byLicense = JSON.parse(output) as Record<string, PackageEntry[]>;

const failures: string[] = [];
let checked = 0;

for (const [license, packages] of Object.entries(byLicense)) {
  for (const pkg of packages) {
    checked++;
    if (isAllowed(license)) continue;
    const key = findPackageException(pkg.name);
    if (key && exceptions.packages[key]?.license === license) continue;
    failures.push(`  ${pkg.name}@${pkg.versions.join(",")}: ${license}`);
  }
}

if (failures.length > 0) {
  console.error(`License check failed: ${failures.length} package(s) outside the AGPL-compatible allowlist.`);
  console.error(failures.join("\n"));
  console.error(
    "Review each one; if acceptable, record it in scripts/license-exceptions.json with a reason.",
  );
  process.exit(1);
}

console.log(`License check passed: ${checked} packages checked.`);
