// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { expect, type Page, test } from "@playwright/test";
import { api, choose, expectNoA11yViolations, onStack, snapshot, uniqueName } from "./helpers.ts";

// The application detail and edit pages against the real backend (spec §6.1, §6.3, #102). Every browser
// project runs these in parallel on one stack, so each test creates its own company and application
// (unique names) and only reads the seeded application with sources (tests/stack/seed/db/0003-*.sql).
test.skip(!onStack, "Needs the full stack: run `pnpm e2e`.");

const SEEDED_APPLICATION = "00000000-0000-4000-8000-0000000e2e11";

interface Application {
  id: string;
  title: string;
  companyId: string;
  version: number;
  unread: boolean;
}

/** A company of this test's own, and an application for it with `details` (by API, as a scanner would). */
async function createApplication(page: Page, title: string, details: Record<string, unknown> = {}) {
  await page.goto("/companies");
  const { request, headers } = await api(page);
  const company = await request.post("/api/companies", { data: { name: uniqueName("Initech") }, headers });
  expect(company.status()).toBe(201);
  const { id: companyId, name: companyName } = (await company.json()) as { id: string; name: string };
  const response = await request.post("/api/applications", {
    data: { title, companyId, ...details },
    headers,
  });
  expect(response.status()).toBe(201);
  return { application: (await response.json()) as Application, companyName };
}

/** Picks an option in one of our Selects (a button named by its value and label, then a list box). */
async function pick(page: Page, label: string, option: string) {
  await page.getByRole("button", { name: new RegExp(`${label}$`) }).click();
  await page.getByRole("option", { name: option, exact: true }).click();
  await expect(page.getByRole("button", { name: new RegExp(`${label}$`) })).toContainText(option);
}

async function readApplication(page: Page, id: string): Promise<Application> {
  const { request } = await api(page);
  const response = await request.get(`/api/applications/${id}`);
  expect(response.status()).toBe(200);
  return (await response.json()) as Application;
}

async function setUnread(page: Page, id: string, unread: boolean) {
  const { request, headers } = await api(page);
  const response = await request.put(`/api/applications/${id}/unread`, { data: { unread }, headers });
  expect(response.status()).toBe(200);
}

test("open an unread application: the overview shows its details and it is marked read once", async ({
  page,
}) => {
  const title = uniqueName("Platform Engineer");
  const { application, companyName } = await createApplication(page, title, {
    location: "Berlin",
    remoteShare: 60,
    employmentType: "FULL_TIME",
    seniority: "SENIOR",
    deadline: "2026-12-15",
    howApplied: "PORTAL",
    portalNotes: "Login via **Workday**.\n\n<script>window.__xss = true</script>",
    payBand: { min: 70000, max: 85000.5, currency: "EUR", period: "YEAR", source: "POSTING" },
    languageAndTone: { postingLanguage: "de", formOfAddress: "SIE", tone: "PROFESSIONAL" },
  });
  await setUnread(page, application.id, true);
  const before = await readApplication(page, application.id);

  await page.goto(`/applications/${application.id}`);
  await expect(page.getByRole("heading", { level: 1, name: title })).toBeVisible();
  await expect(page.getByRole("tab", { name: "Overview" })).toHaveAttribute("aria-selected", "true");
  await expect(page.getByRole("tab", { name: "Timeline" })).toHaveAttribute("aria-disabled", "true");
  const facts = page.getByRole("region", { name: "Details" });
  await expect(facts.getByRole("link", { name: companyName })).toBeVisible();
  await expect(facts.getByText("60%")).toBeVisible();
  await expect(
    page.getByRole("region", { name: "Pay band" }).getByText("€70,000 – €85,000.50 per year"),
  ).toBeVisible();
  const language = page.getByRole("region", { name: "Language & tone" });
  await expect(language.getByText("German (de)")).toBeVisible();
  await expect(language.getByText("Same as the posting")).toBeVisible();
  await expect(page.getByRole("region", { name: "Scores" }).getByText("Available with scoring")).toHaveCount(
    2,
  );
  await expect(page.getByRole("region", { name: "Applying" }).locator("strong")).toHaveText("Workday");
  expect(await page.evaluate(() => "__xss" in window)).toBe(false);

  // Opening marked it read, through its own endpoint: the version stays.
  await expect(page.getByRole("button", { name: "Mark as unread" })).toBeVisible();
  await expect.poll(async () => (await readApplication(page, application.id)).unread).toBe(false);
  expect((await readApplication(page, application.id)).version).toBe(before.version);
  await expectNoA11yViolations(page);
  await snapshot(page, "application-overview");

  await page.getByRole("button", { name: "Mark as unread" }).click();
  await expect(page.getByText("Unread", { exact: true })).toBeVisible();
  await expect.poll(async () => (await readApplication(page, application.id)).unread).toBe(true);
  await page.getByRole("button", { name: "Mark as read" }).click();
  await expect(page.getByRole("button", { name: "Mark as unread" })).toBeVisible();
  await expect.poll(async () => (await readApplication(page, application.id)).unread).toBe(false);
});

test("the sources of an application link to the postings", async ({ page }) => {
  await page.goto(`/applications/${SEEDED_APPLICATION}`);
  await expect(page.getByRole("heading", { level: 1, name: "Seeded Engineer (e2e)" })).toBeVisible();
  const sources = page.getByRole("region", { name: "Sources" });
  const link = sources.getByRole("link", {
    name: "https://jobs.seeded.example/engineer?ref=e2e&team=platform",
  });
  await expect(link).toHaveAttribute("rel", "noopener noreferrer nofollow");
  await expect(link).toHaveAttribute("target", "_blank");
  await expect(sources.getByText("Found by a scanner")).toBeVisible();
  await expect(sources.getByText(/offline since Sep 20, 2026/)).toBeVisible();
  await expectNoA11yViolations(page);
});

test("edit the details: pay band, language and applying show on the overview", async ({ page }) => {
  const title = uniqueName("Data Engineer");
  const { application } = await createApplication(page, title);
  await page.goto(`/applications/${application.id}`);
  await page.getByRole("link", { name: "Edit" }).click();
  await expect(page.getByRole("heading", { level: 1, name: `Edit ${title}` })).toBeVisible();

  await page.getByLabel("Location").fill("Hamburg");
  const pay = page.getByRole("group", { name: "Pay band" });
  await pay.getByLabel("Minimum").fill("60000");
  await pay.getByLabel("Maximum").fill("50000");
  await choose(page, "Source", "Estimated");
  await pay.getByLabel("Basis of the estimate").fill("Salary survey, Hamburg");
  await page.getByRole("button", { name: "Save changes" }).click();
  // Checked before anything is sent.
  await expect(pay.getByText("The maximum cannot be below the minimum.")).toBeVisible();
  await expectNoA11yViolations(page);
  await snapshot(page, "application-edit-invalid");

  await pay.getByLabel("Maximum").fill("72000");
  const language = page.getByRole("group", { name: "Language & tone" });
  await language.getByLabel("Posting language").fill("en-GB");
  await expect(language.getByText(/^Recognised: /)).toBeVisible();
  await choose(page, "Form of address", "Neutral (e.g. English)");
  await choose(page, "Applied via", "Referral");
  await page.getByRole("button", { name: "Save changes" }).click();
  // An estimate needs a confidence: the server says so next to the field (`payBand.estimateConfidence`).
  await expect(pay.getByText("Enter a value.")).toBeVisible();
  await expect(page).toHaveURL(/\/edit$/);

  await pick(page, "Confidence", "High");
  await page.getByRole("button", { name: "Save changes" }).click();
  await expect(page.getByRole("heading", { level: 1, name: title })).toBeVisible();
  const payBand = page.getByRole("region", { name: "Pay band" });
  await expect(payBand.getByText("€60,000 – €72,000 per year")).toBeVisible();
  await expect(payBand.getByText("Estimated")).toBeVisible();
  await expect(payBand.getByText("High")).toBeVisible();
  await expect(payBand.getByText("Salary survey, Hamburg")).toBeVisible();
  const languageCard = page.getByRole("region", { name: "Language & tone" });
  await expect(languageCard.getByText("en-GB")).toBeVisible();
  await expect(languageCard.getByText("Neutral (e.g. English)")).toBeVisible();
  await expect(page.getByRole("region", { name: "Applying" }).getByText("Referral")).toBeVisible();
  await expect(page.getByRole("region", { name: "Details" }).getByText("Hamburg")).toBeVisible();
  await expectNoA11yViolations(page);
  await snapshot(page, "application-overview-edited");
});

test("a save based on an old version is refused; loading the latest version lets the user edit again", async ({
  page,
}) => {
  const { application } = await createApplication(page, uniqueName("Site Reliability Engineer"));
  await page.goto(`/applications/${application.id}/edit`);
  await expect(page.getByLabel("Job title (required)")).toHaveValue(application.title);

  // Meanwhile another tab (here: the API) renames the application.
  const renamed = `${application.title} II`;
  const { request, headers } = await api(page);
  const put = await request.put(`/api/applications/${application.id}`, {
    data: {
      details: { title: renamed, companyId: application.companyId },
      basedOnVersion: application.version,
    },
    headers,
  });
  expect(put.status()).toBe(200);

  await page.getByLabel("Location").fill("Remote");
  await page.getByRole("button", { name: "Save changes" }).click();
  const conflict = page.getByRole("alert").filter({ hasText: "Changed meanwhile" });
  await expect(conflict).toBeVisible();
  await expectNoA11yViolations(page);
  await snapshot(page, "application-edit-conflict");

  await conflict.getByRole("button", { name: "Load latest version" }).click();
  await expect(page.getByLabel("Job title (required)")).toHaveValue(renamed);
  await page.getByLabel("Location").fill("Remote");
  await page.getByRole("button", { name: "Save changes" }).click();
  await expect(page.getByRole("heading", { level: 1, name: renamed })).toBeVisible();
  await expect(
    page.getByRole("region", { name: "Details" }).getByText("Remote", { exact: true }),
  ).toBeVisible();
});

test("delete asks first, names what goes with the application, and removes it", async ({ page }) => {
  const { application } = await createApplication(page, uniqueName("QA Engineer"));
  await page.goto(`/applications/${application.id}`);
  await page.getByRole("button", { name: "Delete…" }).click();
  const dialog = page.getByRole("alertdialog", { name: "Delete this application?" });
  await expect(dialog).toContainText(`${application.title} will be deleted`);
  await expectNoA11yViolations(page);
  await snapshot(page, "application-delete-confirm");

  await dialog.getByRole("button", { name: "Cancel" }).click();
  await expect(dialog).toBeHidden();
  const { request } = await api(page);
  expect((await request.get(`/api/applications/${application.id}`)).status()).toBe(200);

  await page.getByRole("button", { name: "Delete…" }).click();
  await dialog.getByRole("button", { name: "Delete application" }).click();
  await expect(page).toHaveURL(/\/applications$/);
  expect((await request.get(`/api/applications/${application.id}`)).status()).toBe(404);
});

test.describe("in German", () => {
  test.use({ locale: "de-DE" });

  test("view, mark unread and delete an application in German", async ({ page }) => {
    const title = uniqueName("Backend-Entwicklerin");
    const { application } = await createApplication(page, title, {
      payBand: { min: 55000, max: 65000, currency: "EUR", period: "YEAR", source: "RECRUITER" },
      languageAndTone: { postingLanguage: "en", formOfAddress: "DU" },
    });
    await page.goto(`/applications/${application.id}`);
    await expect(page.getByRole("heading", { level: 1, name: title })).toBeVisible();
    await expect(page.getByRole("tab", { name: "Übersicht" })).toHaveAttribute("aria-selected", "true");
    const pay = page.getByRole("region", { name: "Gehaltsspanne" });
    await expect(pay.getByText(/^55\.000\s?–\s?65\.000\s€ pro Jahr$/)).toBeVisible();
    await expect(pay.getByText("Vom Recruiting genannt")).toBeVisible();
    await expect(
      page.getByRole("region", { name: "Sprache & Ton" }).getByText("Englisch (en)"),
    ).toBeVisible();
    await page.getByRole("button", { name: "Als ungelesen markieren" }).click();
    await expect(page.getByText("Ungelesen", { exact: true })).toBeVisible();
    await expectNoA11yViolations(page);
    await snapshot(page, "application-overview-de");

    await page.getByRole("button", { name: "Löschen …" }).click();
    const dialog = page.getByRole("alertdialog", { name: "Diese Bewerbung löschen?" });
    await expect(dialog).toContainText(`${title} wird gelöscht`);
    await expectNoA11yViolations(page);
    await snapshot(page, "application-delete-confirm-de");
    await dialog.getByRole("button", { name: "Bewerbung löschen" }).click();
    await expect(page).toHaveURL(/\/applications$/);
  });
});
