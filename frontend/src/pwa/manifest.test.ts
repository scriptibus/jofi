// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { describe, expect, it } from "vitest";
import tokens from "../styles/tokens.css?raw";
import { themeColourMeta, themeColours, tokenValue, webAppManifest } from "./manifest";

describe("PWA manifest", () => {
  const colours = themeColours(tokens);

  it("takes its colours from the design tokens", () => {
    expect(colours.light.background).toBe(tokenValue(tokens, ":root", "bg"));
    expect(colours.dark.background).toBe(tokenValue(tokens, ':root[data-theme="dark"]', "bg"));
    expect(colours.light.background).not.toBe(colours.dark.background);
    const manifest = webAppManifest(colours);
    expect(manifest.theme_color).toBe(colours.light.background);
    expect(manifest.background_color).toBe(colours.light.background);
  });

  it("is installable: standalone, scoped to the app, with 192/512 and maskable icons", () => {
    const manifest = webAppManifest(colours);
    expect(manifest).toMatchObject({ name: "Jofi", start_url: "/", scope: "/", display: "standalone" });
    expect(manifest.icons.map((icon) => `${icon.sizes} ${icon.purpose}`)).toEqual([
      "192x192 any",
      "512x512 any",
      "512x512 maskable",
    ]);
  });

  it("registers the share target on the share route", () => {
    expect(webAppManifest(colours).share_target).toEqual({
      action: "/share",
      method: "GET",
      enctype: "application/x-www-form-urlencoded",
      params: { title: "title", text: "text", url: "url" },
    });
  });

  it("writes one theme-color meta per colour scheme", () => {
    const meta = themeColourMeta(colours);
    expect(meta).toContain(`media="(prefers-color-scheme: light)" content="${colours.light.background}"`);
    expect(meta).toContain(`media="(prefers-color-scheme: dark)" content="${colours.dark.background}"`);
  });

  it("fails loudly when a token is missing", () => {
    expect(() => tokenValue(tokens, ":root", "does-not-exist")).toThrow(/has no --does-not-exist/);
    expect(() => tokenValue(tokens, ".nope", "bg")).toThrow(/no rule/);
  });
});
