// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { expect, type Page, test } from "@playwright/test";
import { api, choose, expectNoA11yViolations, onStack, snapshot, uniqueName } from "./helpers.ts";

// The applications list against the real backend (spec §6.3, #100). Every browser project runs these in
// parallel on one stack next to other tests' applications, so each test creates its own companies and
// applications (unique names) and filters by its own company before it counts or orders anything.
test.skip(!onStack, "Needs the full stack: run `pnpm e2e`.");

interface Created {
  id: string;
  name: string;
  title: string;
  version: number;
}

const isPhone = () => test.info().project.name === "phone";

async function createCompany(page: Page, prefix: string): Promise<Created> {
  const { request, headers } = await api(page);
  const response = await request.post("/api/companies", { data: { name: uniqueName(prefix) }, headers });
  expect(response.status()).toBe(201);
  return (await response.json()) as Created;
}

interface Seed {
  title: string;
  status?: string;
  unread?: boolean;
  deadline?: string;
  languageAndTone?: Record<string, string>;
}

/** An application by API, moved to `status` and marked unread as a scanner would leave it. */
async function createApplication(page: Page, companyId: string, seed: Seed): Promise<Created> {
  const { request, headers } = await api(page);
  const { status, unread, ...details } = seed;
  const response = await request.post("/api/applications", { data: { companyId, ...details }, headers });
  expect(response.status()).toBe(201);
  const created = (await response.json()) as Created;
  if (status) {
    const moved = await request.put(`/api/applications/${created.id}/status`, {
      data: { basedOnVersion: created.version, status },
      headers,
    });
    expect(moved.status()).toBe(200);
  }
  if (unread) await setUnread(page, created.id, true);
  return created;
}

async function setUnread(page: Page, id: string, unread: boolean) {
  const { request, headers } = await api(page);
  const response = await request.put(`/api/applications/${id}/unread`, { data: { unread }, headers });
  expect(response.status()).toBe(200);
}

/** Picks an option in one of our Selects (a button named by its value and label, then a list box). */
async function pick(page: Page, label: string, option: string) {
  await page.getByRole("button", { name: new RegExp(`. ${label}$`) }).click();
  await page.getByRole("option", { name: option, exact: true }).click();
  await expect(page.getByRole("button", { name: new RegExp(`. ${label}$`) })).toContainText(option);
}

/** Toggles options of a multiple choice, then closes its list. */
async function pickSeveral(page: Page, label: string, options: string[]) {
  await page.getByRole("button", { name: new RegExp(`. ${label}$`) }).click();
  const list = page.getByRole("listbox", { name: label });
  for (const option of options) await list.getByRole("option", { name: option, exact: true }).click();
  await page.keyboard.press("Escape");
  await expect(list).toBeHidden();
}

/** The titles in the order shown (table rows on desktop, cards on phones). */
function titles(page: Page, pattern: RegExp) {
  return page.getByRole("link", { name: pattern });
}

/** Sorts by a column: its heading on desktop, the sort picker on phones. */
async function sortBy(page: Page, column: string, direction: "ascending" | "descending") {
  if (isPhone()) {
    await pick(page, "Sort by", `${column}, ${direction}`);
    return;
  }
  const heading = page.getByRole("columnheader", { name: column });
  await heading.getByRole("button", { name: column }).click();
  await expect(heading).toHaveAttribute("aria-sort", direction);
}

test("filter by company and status, sort by deadline, keep it all in the URL", async ({ page }) => {
  await page.goto("/applications");
  const tyrell = await createCompany(page, "Tyrell");
  const wallace = await createCompany(page, "Wallace");
  const tag = uniqueName("#");
  await createApplication(page, tyrell.id, {
    title: `Platform Engineer ${tag}`,
    status: "APPLIED",
    deadline: "2026-11-20",
    languageAndTone: { postingLanguage: "de" },
  });
  await createApplication(page, tyrell.id, {
    title: `Data Engineer ${tag}`,
    status: "OFFER",
    deadline: "2026-10-05",
    languageAndTone: { applicationLanguage: "en" },
  });
  await createApplication(page, tyrell.id, { title: `Support Engineer ${tag}` });
  await createApplication(page, wallace.id, { title: `Replicant Analyst ${tag}`, status: "APPLIED" });
  const ours = /^(Platform|Data|Support) Engineer/;

  await page.reload();
  await expect(page.getByRole("heading", { level: 1, name: "Applications" })).toBeVisible();
  await pick(page, "Company", tyrell.name);
  await expect(page.getByText("3 applications match")).toBeVisible();
  await expect(page.getByRole("link", { name: /Replicant Analyst/ })).toHaveCount(0);

  await sortBy(page, "Deadline", "ascending");
  await expect(titles(page, ours)).toHaveText([/^Data/, /^Platform/, /^Support/]);
  await sortBy(page, "Deadline", "descending");
  // Without a deadline comes last both ways.
  await expect(titles(page, ours)).toHaveText([/^Platform/, /^Data/, /^Support/]);

  await pickSeveral(page, "Status", ["Applied", "Offer"]);
  await expect(page.getByText("2 applications match")).toBeVisible();
  const platform = page.getByRole(isPhone() ? "article" : "row").filter({ hasText: "Platform Engineer" });
  await expect(platform.getByText("Applied", { exact: true })).toBeVisible();
  if (!isPhone()) await expect(platform.getByText("German")).toBeVisible();
  await expectNoA11yViolations(page);
  await snapshot(page, "applications-filtered");

  // Filters and order live in the URL: a reload keeps them.
  await page.reload();
  await expect(page.getByText("2 applications match")).toBeVisible();
  await expect(titles(page, ours)).toHaveText([/^Platform/, /^Data/]);
  await expect(page.getByRole("button", { name: /Applied, Offer.*Status$/ })).toBeVisible();

  await pick(page, "Application language", "English");
  await expect(page.getByText("1 application matches")).toBeVisible();
  await pick(page, "Last updated", "In the last 7 days");
  await expect(titles(page, ours)).toHaveText([/^Data/]);
});

test("unread applications stand out and are marked read and unread", async ({ page }) => {
  await page.goto("/applications");
  const company = await createCompany(page, "Weyland");
  const tag = uniqueName("#");
  const scanned = await createApplication(page, company.id, {
    title: `Scanned Engineer ${tag}`,
    unread: true,
  });
  await createApplication(page, company.id, { title: `Known Engineer ${tag}` });

  await page.goto(`/applications?company=${company.id}`);
  await expect(page.getByText("2 applications match")).toBeVisible();
  const card = page.getByRole(isPhone() ? "article" : "row").filter({ hasText: "Scanned Engineer" });
  await expect(card.getByRole("img", { name: "Unread" })).toBeVisible();

  await choose(page, "Show", "Unread only");
  await expect(page.getByText("1 application matches")).toBeVisible();
  await expect(page.getByRole("link", { name: /Known Engineer/ })).toHaveCount(0);
  await expectNoA11yViolations(page);
  await snapshot(page, "applications-unread");

  await choose(page, "Show", "All");
  await card.getByRole("button", { name: `Mark ${scanned.title} as read` }).click();
  await expect(card.getByRole("img", { name: "Unread" })).toHaveCount(0);
  await card.getByRole("button", { name: `Mark ${scanned.title} as unread` }).click();
  await expect(card.getByRole("img", { name: "Unread" })).toBeVisible();

  // Opening the application marks it read (the detail page, #102); the list shows that after a reload.
  await setUnread(page, scanned.id, false);
  await page.reload();
  await expect(page.getByText("2 applications match")).toBeVisible();
  await expect(card.getByRole("img", { name: "Unread" })).toHaveCount(0);
});

test("more than 50 applications come in pages", async ({ page }) => {
  test.skip(test.info().project.name !== "desktop-light", "Seeds 51 applications: one project is enough.");
  await page.goto("/applications");
  const company = await createCompany(page, "Soylent");
  const tag = uniqueName("#");
  for (let index = 0; index < 51; index++)
    await createApplication(page, company.id, { title: `Role ${String(index).padStart(2, "0")} ${tag}` });

  await page.goto(`/applications?company=${company.id}&sort=TITLE`);
  await expect(page.getByText("51 applications match")).toBeVisible();
  await expect(page.getByText("Page 1 of 2")).toBeVisible();
  await expect(titles(page, /^Role \d\d/)).toHaveCount(50);
  await page.getByRole("button", { name: "Next page" }).click();
  await expect(page.getByText("Page 2 of 2")).toBeVisible();
  await expect(titles(page, /^Role \d\d/)).toHaveText([/^Role 50/]);
  await expect(page).toHaveURL(/page=1/);
  await page.goBack();
  await expect(page.getByText("Page 1 of 2")).toBeVisible();
});

test.describe("in German", () => {
  test.use({ locale: "de-DE" });

  test("filter by status in German, and reset when nothing matches", async ({ page }) => {
    await page.goto("/applications");
    const company = await createCompany(page, "Cyberdyne");
    const tag = uniqueName("#");
    await createApplication(page, company.id, {
      title: `Backend-Entwicklerin ${tag}`,
      status: "APPLIED",
      deadline: "2026-12-01",
    });

    await page.goto(`/applications?company=${company.id}`);
    await expect(page.getByRole("heading", { level: 1, name: "Bewerbungen" })).toBeVisible();
    await expect(page.getByText("1 Bewerbung passt")).toBeVisible();
    await expect(page.getByText("Beworben", { exact: true }).first()).toBeVisible();
    if (!isPhone())
      await expect(page.getByRole("columnheader", { name: "Geändert" })).toHaveAttribute(
        "aria-sort",
        "descending",
      );
    await expectNoA11yViolations(page);
    await snapshot(page, "applications-de");

    await pickSeveral(page, "Status", ["Angebot"]);
    await expect(page.getByText("Keine Bewerbung passt zu diesen Filtern.")).toBeVisible();
    await page.getByRole("button", { name: "Filter zurücksetzen" }).first().click();
    await expect(page).toHaveURL(/\/applications$/);
    await expect(page.getByRole("button", { name: /^Jeder Status Status$/ })).toBeVisible();
    await expect(page.getByRole("button", { name: /^Alle Firmen Firma$/ })).toBeVisible();
  });
});
