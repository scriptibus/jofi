// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { expect, type Page, test } from "@playwright/test";
import { api, choose, expectNoA11yViolations, onStack, snapshot, uniqueName } from "./helpers.ts";

// The companies pages against the real backend (spec §5, #108). Every browser project runs these in
// parallel on one stack, so each test works on companies of its own (unique names) and only reads the
// seeded one (tests/stack/seed/db/0002-company-with-application.sql).
test.skip(!onStack, "Needs the full stack: run `pnpm e2e`.");

const SEEDED_ID = "00000000-0000-4000-8000-0000000e2e10";
const SEEDED_NAME = "Seeded Holdings (e2e)";

interface Company {
  id: string;
  name: string;
  version: number;
}

async function createCompany(page: Page, name: string): Promise<Company> {
  await page.goto("/companies");
  const { request, headers } = await api(page);
  const response = await request.post("/api/companies", { data: { name }, headers });
  expect(response.status()).toBe(201);
  return (await response.json()) as Company;
}

test("create a company, find it with a typo, and see its details", async ({ page }) => {
  const name = uniqueName("Quokka Robotics");
  await page.goto("/companies");
  await expect(page.getByRole("heading", { level: 1, name: "Companies" })).toBeVisible();
  await page.getByRole("link", { name: "New company" }).click();

  await expect(page.getByRole("heading", { level: 1, name: "New company" })).toBeVisible();
  await page.getByLabel("Name (required)").fill(name);
  await page.getByLabel("Website").fill("quokka.example");
  await page.getByLabel("Industry").fill("Robotics");
  await choose(page, "Size (employees)", "50–249");
  await page.getByLabel("Locations").fill("Berlin\nRemote");
  await page.getByLabel("Research notes").fill("**Friendly** team.\n\n<script>window.__xss = true</script>");
  await page.getByRole("button", { name: "Create company" }).click();
  // Checked before anything is sent.
  await expect(page.getByText("Enter a full web address starting with https:// or http://.")).toBeVisible();
  await expectNoA11yViolations(page);
  await snapshot(page, "company-new-invalid");

  await page.getByLabel("Website").fill("https://quokka.example");
  await page.getByRole("button", { name: "Create company" }).click();
  await expect(page.getByRole("heading", { level: 1, name })).toBeVisible();
  const details = page.getByRole("region", { name: "Details" });
  await expect(details.getByRole("link", { name: "https://quokka.example" })).toHaveAttribute(
    "rel",
    "noopener noreferrer nofollow",
  );
  await expect(details.getByText("Berlin · Remote")).toBeVisible();
  const notes = page.getByRole("region", { name: "Research notes" });
  await expect(notes.locator("strong")).toHaveText("Friendly");
  expect(await page.evaluate(() => "__xss" in window)).toBe(false);
  await expectNoA11yViolations(page);
  await snapshot(page, "company-detail");

  await page.getByRole("link", { name: "All companies" }).click();
  // Fuzzy: a typo in the name still finds it.
  await page.getByRole("searchbox", { name: "Search companies" }).fill(name.replace("Quokka", "Qokka"));
  await expect(page.getByRole("link", { name })).toBeVisible();
  await expect(page).toHaveURL(/q=Qokka/);
  await expectNoA11yViolations(page);
  await snapshot(page, "companies-search");
});

test("flag a company as blacklisted with a reason, then filter by it", async ({ page }) => {
  const company = await createCompany(page, uniqueName("Globex Chemicals"));
  await page.goto(`/companies/${company.id}`);
  await page.getByRole("button", { name: "Change flag…" }).click();
  const dialog = page.getByRole("dialog", { name: "Flag this company" });
  await choose(page, "Flag", "Blacklisted");
  await dialog.getByLabel("Reason").fill("Unpaid trial work");
  await expectNoA11yViolations(page);
  await snapshot(page, "company-flag-dialog");
  await dialog.getByRole("button", { name: "Save flag" }).click();
  await expect(dialog).toBeHidden();

  const flag = page.getByRole("region", { name: "Flag" });
  await expect(flag.getByText("Blacklisted")).toBeVisible();
  await expect(flag.getByText("Unpaid trial work")).toBeVisible();

  await page.goto(`/companies?q=${encodeURIComponent(company.name)}`);
  await choose(page, "Show", "Blacklisted");
  await expect(page).toHaveURL(/preference=BLACKLISTED/);
  await expect(page.getByRole("link", { name: company.name })).toBeVisible();
  await choose(page, "Show", "Favourite");
  await expect(page.getByRole("link", { name: company.name })).toBeHidden();
  await expectNoA11yViolations(page);
});

test("a save based on an old version is refused; loading the latest version lets the user edit again", async ({
  page,
}) => {
  const company = await createCompany(page, uniqueName("Hooli"));
  await page.goto(`/companies/${company.id}/edit`);
  await expect(page.getByLabel("Name (required)")).toHaveValue(company.name);

  // Meanwhile another tab (here: the API) renames it.
  const renamed = `${company.name} XYZ`;
  const { request, headers } = await api(page);
  const put = await request.put(`/api/companies/${company.id}`, {
    data: { details: { name: renamed }, basedOnVersion: company.version },
    headers,
  });
  expect(put.status()).toBe(200);

  await page.getByLabel("Industry").fill("Compression");
  await page.getByRole("button", { name: "Save changes" }).click();
  const conflict = page.getByRole("alert").filter({ hasText: "Changed meanwhile" });
  await expect(conflict).toBeVisible();
  await expectNoA11yViolations(page);
  await snapshot(page, "company-edit-conflict");

  await conflict.getByRole("button", { name: "Load latest version" }).click();
  await expect(page.getByLabel("Name (required)")).toHaveValue(renamed);
  await page.getByLabel("Industry").fill("Compression");
  await page.getByRole("button", { name: "Save changes" }).click();
  await expect(page.getByRole("heading", { level: 1, name: renamed })).toBeVisible();
  await expect(page.getByRole("region", { name: "Details" }).getByText("Compression")).toBeVisible();
});

test("delete asks first, names the contacts that go with it, and removes both", async ({ page }) => {
  const company = await createCompany(page, uniqueName("Initech"));
  const { request, headers } = await api(page);
  const contact = await request.post("/api/contacts", {
    data: { name: "Bill Lumbergh", role: "Manager", companyId: company.id },
    headers,
  });
  expect(contact.status()).toBe(201);

  await page.goto(`/companies/${company.id}`);
  await expect(page.getByRole("region", { name: "Contacts" }).getByText("Bill Lumbergh")).toBeVisible();
  await page.getByRole("button", { name: "Delete…" }).click();
  const dialog = page.getByRole("alertdialog", { name: "Delete this company?" });
  await expect(dialog).toContainText(`${company.name} will be deleted together with its 1 contact.`);
  await expectNoA11yViolations(page);
  await snapshot(page, "company-delete-confirm");

  await dialog.getByRole("button", { name: "Cancel" }).click();
  await expect(dialog).toBeHidden();
  expect((await request.get(`/api/companies/${company.id}`)).status()).toBe(200);

  await page.getByRole("button", { name: "Delete…" }).click();
  await dialog.getByRole("button", { name: "Delete company" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Companies" })).toBeVisible();
  expect((await request.get(`/api/companies/${company.id}`)).status()).toBe(404);
  const contactId = ((await contact.json()) as { id: string }).id;
  expect((await request.get(`/api/contacts/${contactId}`)).status()).toBe(404);
});

test("a company with applications cannot be deleted; its AI profile renders inert", async ({ page }) => {
  await page.goto(`/companies/${SEEDED_ID}`);
  await expect(page.getByRole("heading", { level: 1, name: SEEDED_NAME })).toBeVisible();

  // The seeded profile tries a script, a javascript: link and a remote image.
  const profile = page.getByRole("region", { name: "AI profile" });
  await expect(profile.getByText("Seeded profile text.")).toBeVisible();
  await expect(profile.getByText("Click me")).toBeVisible();
  await expect(profile.getByRole("link")).toHaveCount(0);
  await expect(page.locator("main img")).toHaveCount(0);
  expect(await page.evaluate(() => "__seedXss" in window)).toBe(false);

  await page.getByRole("button", { name: "Delete…" }).click();
  await expect(page.getByRole("alert").filter({ hasText: "still has applications" })).toBeVisible();
  await expect(page.getByRole("alertdialog")).toHaveCount(0);
  await expectNoA11yViolations(page);
  await snapshot(page, "company-has-applications");
});

test.describe("in German", () => {
  test.use({ locale: "de-DE" });

  test("create and flag a company in German", async ({ page }) => {
    const name = uniqueName("Wombat Werke");
    await page.goto("/companies/new");
    await expect(page.getByRole("heading", { level: 1, name: "Neue Firma" })).toBeVisible();
    await page.getByLabel("Name (Pflichtfeld)").fill(name);
    await choose(page, "Größe (Mitarbeitende)", "5.000+");
    await page.getByRole("button", { name: "Firma anlegen" }).click();
    await expect(page.getByRole("heading", { level: 1, name })).toBeVisible();

    await page.getByRole("button", { name: "Markierung ändern …" }).click();
    const dialog = page.getByRole("dialog", { name: "Firma markieren" });
    await choose(page, "Markierung", "Favorit");
    await dialog.getByLabel("Grund").fill("Tolles Team");
    await dialog.getByRole("button", { name: "Markierung speichern" }).click();
    await expect(dialog).toBeHidden();
    await expect(page.getByRole("region", { name: "Markierung" }).getByText("Tolles Team")).toBeVisible();
    await expectNoA11yViolations(page);
    await snapshot(page, "company-detail-de");

    await page.getByRole("link", { name: "Alle Firmen" }).click();
    await page.getByRole("searchbox", { name: "Firmen suchen" }).fill(name);
    await expect(page.getByRole("link", { name })).toBeVisible();
    await expect(page.getByRole("status").filter({ hasText: /Firma passt|Firmen passen/ })).toBeVisible();
    await expectNoA11yViolations(page);
    await snapshot(page, "companies-de");
  });
});
