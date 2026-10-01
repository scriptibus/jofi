// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// macOS and Windows file systems ignore case, Linux CI does not. Two tracked paths that differ only in
// case can't both be checked out there, and two modules that differ only in case (`savedViews.ts` next to
// `SavedViews.tsx`) make `import "./SavedViews"` resolve to the wrong file, so `tsc` fails only locally
// (issue #202). `forceConsistentCasingInFileNames` does not catch the second case.

import { execFileSync } from "node:child_process";
import { describe, expect, it } from "vitest";

const MODULE_EXTENSION = /\.(?:[cm]?[jt]sx?)$/;

/** Groups of paths that are one file, or one import specifier, on a case-insensitive file system. */
function caseCollisions(paths: readonly string[]): string[][] {
  const groups = new Map<string, Set<string>>();
  const add = (key: string, path: string) => {
    const group = groups.get(key) ?? new Set<string>();
    group.add(path);
    groups.set(key, group);
  };
  for (const path of paths) {
    add(`path:${path.toLowerCase()}`, path);
    if (MODULE_EXTENSION.test(path)) {
      add(`module:${path.replace(MODULE_EXTENSION, "").toLowerCase()}`, path);
    }
  }
  return [...groups.values()].filter((group) => group.size > 1).map((group) => [...group].sort());
}

function trackedPaths(): string[] {
  // The whole repository, not only frontend/: Vitest runs in frontend/, git finds the top from there.
  const root = execFileSync("git", ["rev-parse", "--show-toplevel"], { encoding: "utf8" }).trim();
  return execFileSync("git", ["ls-files", "-z"], { cwd: root, encoding: "utf8" }).split("\0").filter(Boolean);
}

describe("caseCollisions", () => {
  it("finds a module and a component whose names differ only in case", () => {
    expect(caseCollisions(["a/savedViews.ts", "a/SavedViews.tsx", "a/savedViewModel.ts"])).toEqual([
      ["a/SavedViews.tsx", "a/savedViews.ts"],
    ]);
  });

  it("finds paths that differ only in case", () => {
    expect(caseCollisions(["docs/README.md", "docs/readme.md"])).toEqual([
      ["docs/README.md", "docs/readme.md"],
    ]);
  });

  it("allows the same name with different non-module extensions and different names", () => {
    expect(
      caseCollisions(["a/logo.svg", "a/Logo.png", "a/board.ts", "a/board.test.ts", "a/Board.tsx2"]),
    ).toEqual([]);
  });
});

describe("tracked files", () => {
  it("never differ only in case", () => {
    expect(caseCollisions(trackedPaths())).toEqual([]);
  });
});
