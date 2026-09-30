// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { expect, test } from "@playwright/test";
import { choose, expectNoA11yViolations, mainNav, onStack, snapshot } from "./helpers.ts";

// Every test starts logged in (storage state of the `seed` project).
test.skip(!onStack, "Needs the full stack: run `pnpm e2e`.");

const AREAS = [
  { link: "Applications", heading: "Applications", path: "/applications" },
  { link: "Companies", heading: "Companies", path: "/companies" },
  { link: "Tasks", heading: "Tasks", path: "/tasks" },
  { link: "Chat", heading: "Chat", path: "/chat" },
  { link: "Dashboard", heading: "Let the donkey do the donkey work.", path: "/" },
];

test("shell: navigation to every area, landmarks, skip link, accessibility", async ({ page }, testInfo) => {
  await page.goto("/");
  await expect(
    page.getByRole("heading", { level: 1, name: "Let the donkey do the donkey work." }),
  ).toBeVisible();
  await expect(page.locator("html")).toHaveAttribute("lang", "en");
  await expect(page.getByRole("main")).toBeVisible();
  await expectNoA11yViolations(page);
  await snapshot(page, "dashboard");

  for (const area of AREAS) {
    await mainNav(page).getByRole("link", { name: area.link }).click();
    await expect(page.getByRole("heading", { level: 1, name: area.heading })).toBeVisible();
    await expect(page).toHaveURL(area.path);
    await expect(mainNav(page).getByRole("link", { name: area.link })).toHaveAttribute(
      "aria-current",
      "page",
    );
    await expect(page.getByRole("heading", { level: 2, name: "Nothing here yet" })).toBeVisible();
  }
  await page.goto("/applications");
  await expectNoA11yViolations(page);
  await snapshot(page, "applications");

  // Settings: in the sidebar on desktop, in the top bar on phones.
  const isPhone = testInfo.project.name === "phone";
  const settings = isPhone
    ? page.getByRole("banner").getByRole("link", { name: "Settings" })
    : mainNav(page).getByRole("link", { name: "Settings" });
  await settings.click();
  await expect(page.getByRole("heading", { level: 1, name: "Settings" })).toBeVisible();

  // The skip link is the first stop and moves focus to the content.
  await page.goto("/tasks");
  await expect(page.getByRole("heading", { level: 1, name: "Tasks" })).toBeVisible();
  await page.keyboard.press("Tab");
  const skip = page.getByRole("link", { name: "Skip to content" });
  await expect(skip).toBeFocused();
  await skip.press("Enter");
  await expect(page).toHaveURL(/#main$/);
});

test("deep links load directly, unknown pages stay inside the shell", async ({ page }) => {
  await page.goto("/companies");
  await expect(page.getByRole("heading", { level: 1, name: "Companies" })).toBeVisible();
  await page.reload();
  await expect(page.getByRole("heading", { level: 1, name: "Companies" })).toBeVisible();

  await page.goto("/does-not-exist");
  await expect(page.getByRole("heading", { level: 1, name: "Page not found" })).toBeVisible();
  await expect(mainNav(page)).toBeVisible();
});

test("settings: theme, accent and language persist; German works", async ({ page }, testInfo) => {
  await page.goto("/settings");
  const html = page.locator("html");
  await expect(page.getByRole("heading", { level: 1, name: "Settings" })).toBeVisible();
  await expect(page.getByText(/^Jofi \S+$/)).toBeVisible();
  await expectNoA11yViolations(page);
  await snapshot(page, "settings");

  const osDark = testInfo.project.use.colorScheme === "dark";
  await choose(page, "Theme", osDark ? "Light" : "Dark");
  await expect(html).toHaveAttribute("data-theme", osDark ? "light" : "dark");
  await choose(page, "Accent", "Teal");
  await expect(html).toHaveAttribute("data-accent", "teal");
  await expectNoA11yViolations(page);
  await snapshot(page, "settings-themed");

  await page.reload();
  await expect(html).toHaveAttribute("data-theme", osDark ? "light" : "dark");
  await expect(
    page.getByRole("radiogroup", { name: "Accent" }).getByRole("radio", { name: "Teal" }),
  ).toBeChecked();

  // Paraglide stores the language and reloads the document.
  await page.getByRole("radiogroup", { name: "Language" }).getByText("Deutsch", { exact: true }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Einstellungen" })).toBeVisible();
  await expect(html).toHaveAttribute("lang", "de");
  await expect(mainNav(page).getByRole("link", { name: "Bewerbungen" })).toBeVisible();
  await expect(page.getByRole("heading", { level: 2, name: "Passwort ändern" })).toBeVisible();
  await expectNoA11yViolations(page);
  await snapshot(page, "settings-de");

  await mainNav(page).getByRole("link", { name: "Übersicht" }).click();
  await expect(
    page.getByRole("heading", { level: 1, name: "Lass den Esel die Eselsarbeit machen." }),
  ).toBeVisible();
  await snapshot(page, "dashboard-de");
});

test("share target: the share route shows what was shared", async ({ page }) => {
  await page.goto(
    "/share?title=Kotlin%20Developer%20(m%2Fw%2Fd)&text=Look%20at%20this&url=https%3A%2F%2Fjobs.example%2F42",
  );
  await expect(page.getByRole("heading", { level: 1, name: "Shared with Jofi" })).toBeVisible();
  await expect(page.getByText("Kotlin Developer (m/w/d)")).toBeVisible();
  await expect(page.getByText("Look at this")).toBeVisible();
  await expect(page.getByText("https://jobs.example/42")).toBeVisible();
  await expectNoA11yViolations(page);
  await snapshot(page, "share");

  await page.goto("/share");
  await expect(
    page.getByText("Nothing was shared. Use your device's share sheet and pick Jofi."),
  ).toBeVisible();
});
