// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { act, renderHook } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
// Raw source of the pre-paint script (served from public/).
import boot from "../../public/appearance-boot.js?raw";
import {
  ACCENT_STORAGE_KEY,
  ACCENTS,
  initAppearance,
  readAccent,
  readTheme,
  setAccent,
  setTheme,
  THEME_STORAGE_KEY,
  useAccent,
  useTheme,
} from "./appearance";

const root = document.documentElement;

describe("theme preference", () => {
  it("defaults to system and sets no attribute", () => {
    initAppearance();
    expect(readTheme()).toBe("system");
    expect(root.dataset.theme).toBeUndefined();
  });

  it("persists an explicit theme and applies it to <html>", () => {
    setTheme("dark");
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe("dark");
    expect(root.dataset.theme).toBe("dark");
    expect(readTheme()).toBe("dark");
  });

  it("switching back to system removes the stored value and the attribute", () => {
    setTheme("light");
    setTheme("system");
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBeNull();
    expect(root.dataset.theme).toBeUndefined();
  });

  it("ignores unknown stored values", () => {
    localStorage.setItem(THEME_STORAGE_KEY, "neon");
    expect(readTheme()).toBe("system");
  });

  it("hook state follows updates", () => {
    const { result } = renderHook(() => useTheme());
    act(() => result.current[1]("light"));
    expect(result.current[0]).toBe("light");
    expect(root.dataset.theme).toBe("light");
  });
});

describe("accent preference", () => {
  it("defaults to saffron without an attribute", () => {
    initAppearance();
    expect(readAccent()).toBe("saffron");
    expect(root.dataset.accent).toBeUndefined();
  });

  it("persists and restores a preset", () => {
    setAccent("teal");
    expect(localStorage.getItem(ACCENT_STORAGE_KEY)).toBe("teal");
    delete root.dataset.accent;
    initAppearance();
    expect(root.dataset.accent).toBe("teal");
  });

  it("hook state follows updates", () => {
    const { result } = renderHook(() => useAccent());
    act(() => result.current[1]("plum"));
    expect(result.current[0]).toBe("plum");
    expect(root.dataset.accent).toBe("plum");
  });
});

describe("blocked storage", () => {
  it("still applies the choice when localStorage throws", () => {
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new DOMException("blocked", "SecurityError");
    });
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new DOMException("blocked", "SecurityError");
    });
    expect(() => setTheme("dark")).not.toThrow();
    expect(root.dataset.theme).toBe("dark");
    expect(readTheme()).toBe("system");
  });
});

describe("boot script", () => {
  // public/appearance-boot.js runs before React; it must use the same keys and values.
  it("uses the same storage keys", () => {
    expect(boot).toContain(`"${THEME_STORAGE_KEY}"`);
    expect(boot).toContain(`"${ACCENT_STORAGE_KEY}"`);
  });

  it("knows every non-default accent", () => {
    for (const accent of ACCENTS.filter((a) => a !== "saffron")) {
      expect(boot).toContain(`"${accent}"`);
    }
  });
});
