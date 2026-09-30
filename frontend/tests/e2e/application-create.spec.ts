// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { expect, type Page, test } from "@playwright/test";
import { api, choose, expectNoA11yViolations, mainNav, onStack, snapshot, uniqueName } from "./helpers.ts";

// Creating an application by hand against the real backend (spec §6.1, §6.3, #173): from the table's
// header and from a company's page. Every browser project runs these in parallel on one stack, so each
// test creates its own company (a unique name early in the alphabet: the picker offers the first 200).
test.skip(!onStack, "Needs the full stack: run `pnpm e2e`.");

interface Company {
  id: string;
  name: string;
}

async function createCompany(page: Page, prefix: string): Promise<Company> {
  await page.goto("/companies");
  const { request, headers } = await api(page);
  const response = await request.post("/api/companies", { data: { name: uniqueName(prefix) }, headers });
  expect(response.status()).toBe(201);
  return (await response.json()) as Company;
}

/** The company picker: a button named by its value and label. */
const companyPicker = (page: Page, label: string) =>
  page.getByRole("button", { name: new RegExp(`${label.replace(/[()]/g, "\\$&")}$`) });

test("create an application from the table's header: it opens, and the table lists it", async ({ page }) => {
  const company = await createCompany(page, "Aardvark Analytics");
  const title = uniqueName("Platform Engineer");
  await page.goto("/applications");
  await page.getByRole("link", { name: "New application" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "New application" })).toBeVisible();

  await page.getByLabel("Job title (required)").fill(title);
  await companyPicker(page, "Company (required)").click();
  await page.getByRole("option", { name: company.name, exact: true }).click();
  await expect(companyPicker(page, "Company (required)")).toContainText(company.name);
  await page.getByLabel("Location").fill("Berlin");
  const pay = page.getByRole("group", { name: "Pay band" });
  await pay.getByLabel("Minimum").fill("65000");
  await pay.getByLabel("Maximum").fill("80000");
  await expectNoA11yViolations(page);
  await snapshot(page, "application-new");

  await page.getByRole("button", { name: "Create application" }).click();
  await expect(page.getByRole("heading", { level: 1, name: title })).toBeVisible();
  const facts = page.getByRole("region", { name: "Details" });
  await expect(facts.getByRole("link", { name: company.name })).toBeVisible();
  await expect(facts.getByText("Berlin")).toBeVisible();
  await expect(
    page.getByRole("region", { name: "Pay band" }).getByText("€65,000 – €80,000 per year"),
  ).toBeVisible();

  // Back to the table in the app (no reload): it asks the server again and lists the new one.
  await mainNav(page).getByRole("link", { name: "Applications" }).click();
  await expect(page.getByRole("link", { name: title })).toBeVisible();
});

test("create an application from a company's page: the company is preselected and lists it", async ({
  page,
}) => {
  const company = await createCompany(page, "Abacus Robotics");
  const title = uniqueName("QA Engineer");
  await page.goto(`/companies/${company.id}`);
  const applications = page.getByRole("region", { name: "Applications" });
  await expect(applications.getByText("No applications to this company yet.")).toBeVisible();
  await applications.getByRole("link", { name: "New application" }).click();

  await expect(page).toHaveURL(new RegExp(`/applications/new\\?company=${company.id}$`));
  await expect(companyPicker(page, "Company (required)")).toContainText(company.name);
  await page.getByLabel("Job title (required)").fill(title);
  await page.getByRole("button", { name: "Create application" }).click();
  await expect(page.getByRole("heading", { level: 1, name: title })).toBeVisible();

  await page.getByRole("region", { name: "Details" }).getByRole("link", { name: company.name }).click();
  await expect(page.getByRole("heading", { level: 1, name: company.name })).toBeVisible();
  await expect(
    page.getByRole("region", { name: "Applications" }).getByRole("link", { name: title }),
  ).toBeVisible();
});

test("the server's errors show next to their fields, and nothing is created", async ({ page }) => {
  const title = uniqueName("Ghost Engineer");
  // A company that does not exist (deleted meanwhile, or a stale link).
  await page.goto(`/applications/new?company=${crypto.randomUUID()}`);
  await page.getByLabel("Job title (required)").fill(title);
  await page.getByRole("button", { name: "Create application" }).click();
  await expect(page.getByText("This company does not exist (any more). Choose another one.")).toBeVisible();

  const company = await createCompany(page, "Acorn Estimates");
  await page.goto(`/applications/new?company=${company.id}`);
  await expect(companyPicker(page, "Company (required)")).toContainText(company.name);
  await page.getByLabel("Job title (required)").fill(title);
  const pay = page.getByRole("group", { name: "Pay band" });
  await choose(page, "Source", "Estimated");
  await pay.getByLabel("Basis of the estimate").fill("Salary survey");
  await page.getByRole("button", { name: "Create application" }).click();
  // An estimate needs a confidence: the server names `payBand.estimateConfidence`.
  await expect(pay.getByText("Enter a value.")).toBeVisible();
  await expect(page).toHaveURL(/\/applications\/new\?/);
  await expectNoA11yViolations(page);
  await snapshot(page, "application-new-invalid");

  const { request } = await api(page);
  const search = await request.get(`/api/applications?companyId=${company.id}`);
  expect(((await search.json()) as { total: number }).total).toBe(0);
});

test.describe("in German", () => {
  test.use({ locale: "de-DE" });

  test("create an application from a company's page in German", async ({ page }) => {
    const company = await createCompany(page, "Adler Werke");
    const title = uniqueName("Backend-Entwicklerin");
    await page.goto(`/companies/${company.id}`);
    await page
      .getByRole("region", { name: "Bewerbungen" })
      .getByRole("link", { name: "Neue Bewerbung" })
      .click();

    await expect(page.getByRole("heading", { level: 1, name: "Neue Bewerbung" })).toBeVisible();
    await expect(companyPicker(page, "Firma (Pflichtfeld)")).toContainText(company.name);
    await page.getByLabel("Stellentitel (Pflichtfeld)").fill(title);
    await page.getByLabel("Ort").fill("Köln");
    await expectNoA11yViolations(page);
    await snapshot(page, "application-new-de");
    await page.getByRole("button", { name: "Bewerbung anlegen" }).click();
    await expect(page.getByRole("heading", { level: 1, name: title })).toBeVisible();
    await expect(page.getByRole("region", { name: "Angaben" }).getByText("Köln")).toBeVisible();
  });
});
