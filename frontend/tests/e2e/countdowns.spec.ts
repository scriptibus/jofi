// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { expect, type Page, test } from "@playwright/test";
import { api, expectNoA11yViolations, onStack, snapshot, uniqueName } from "./helpers.ts";

// The countdowns widget on the dashboard against the real backend (spec §10.1, #115): the server merges the
// custom countdowns with the next interview, deadlines and offer answers in the browser's zone. Every browser
// project runs these in parallel on one stack, so each test finds its own countdowns by their unique titles.
test.skip(!onStack, "Needs the full stack: run `pnpm e2e`.");

/** An ISO date `days` after today on the browser's calendar. */
const dayFromToday = (page: Page, days: number) =>
  page.evaluate((offset) => {
    const date = new Date();
    date.setDate(date.getDate() + offset);
    const pad = (value: number) => String(value).padStart(2, "0");
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
  }, days);

/** An application not applied for yet, with a deadline `days` from today: it counts down on the dashboard. */
async function applicationWithDeadline(page: Page, days: number) {
  await page.goto("/");
  const { request, headers } = await api(page);
  const company = await (
    await request.post("/api/companies", { data: { name: uniqueName("Countdown Corp") }, headers })
  ).json();
  const response = await request.post("/api/applications", {
    data: {
      title: uniqueName("Countdown Engineer"),
      companyId: company.id,
      deadline: await dayFromToday(page, days),
    },
    headers,
  });
  expect(response.status()).toBe(201);
  return (await response.json()) as { id: string; title: string };
}

const widget = (page: Page, name = "Countdowns") => page.getByRole("region", { name });
const row = (page: Page, title: string) => widget(page).getByRole("listitem").filter({ hasText: title });

test("an application's deadline counts down on the dashboard and opens the application", async ({ page }) => {
  const application = await applicationWithDeadline(page, 3);
  await page.goto("/");
  const deadline = row(page, application.title);
  await expect(deadline.getByText("Application deadline", { exact: true })).toBeVisible();
  await expect(deadline.getByText("In 3 days", { exact: true })).toBeVisible();
  await expectNoA11yViolations(page);
  await snapshot(page, "countdowns");

  await deadline.getByRole("link", { name: application.title }).click();
  await expect(page.getByRole("heading", { level: 1, name: application.title })).toBeVisible();
});

/** An interview of a new application, starting a minute from now so that it is the next one to come. */
async function applicationWithInterview(page: Page) {
  const application = await applicationWithDeadline(page, 30);
  const { request, headers } = await api(page);
  const soon = new Date(Date.now() + 60_000).toISOString().slice(0, 16);
  const response = await request.post(`/api/applications/${application.id}/interviews`, {
    data: { type: "TECHNICAL", localStart: soon, timeZone: "UTC", participantIds: [] },
    headers,
  });
  expect(response.status()).toBe(201);
  return application;
}

// Every browser project runs in parallel on one stack, so the next interview may be another project's: the link
// must open the interviews tab of whichever application the widget names.
test("the next interview counts down on the dashboard and opens its application's interviews tab", async ({
  page,
}) => {
  await applicationWithInterview(page);
  await page.goto("/");
  const interview = widget(page)
    .getByRole("listitem")
    .filter({ has: page.getByText("Next interview", { exact: true }) });
  await expect(interview).toHaveCount(1);
  await expectNoA11yViolations(page);

  await interview.getByRole("link").click();
  await expect(page).toHaveURL(/\/applications\/[^/?]+\?tab=interviews$/);
  await expect(page.getByRole("heading", { level: 2, name: "Interviews and calls" })).toBeVisible();
});

test("add a custom countdown and delete it only after confirming", async ({ page }) => {
  const title = uniqueName("End of notice period");
  await page.goto("/");
  const form = page.getByRole("form", { name: "Add a countdown" });
  await form.getByRole("button", { name: "Add countdown" }).click();
  await expect(form.getByText("Enter a value.")).toHaveCount(2);

  await form.getByLabel("Counting down to (required)").fill(title);
  // A date the server would refuse never leaves the form, and the message is the app's own (exact: no browser text).
  await form.getByLabel("Date (required)").fill("2100-01-01");
  await form.getByRole("button", { name: "Add countdown" }).click();
  await expect(form.getByText("Enter a date between 2000 and 2099.", { exact: true })).toBeVisible();
  await expect(row(page, title)).toHaveCount(0);

  await form.getByLabel("Date (required)").fill(await dayFromToday(page, 10));
  await form.getByRole("button", { name: "Add countdown" }).click();

  await expect(widget(page).getByRole("status", { name: "Countdowns" })).toHaveText(
    `Countdown “${title}” added.`,
  );
  const countdown = row(page, title);
  await expect(countdown.getByText("Your countdown", { exact: true })).toBeVisible();
  await expect(countdown.getByText("In 10 days", { exact: true })).toBeVisible();
  await expect(form.getByLabel("Counting down to (required)")).toHaveValue("");
  await expectNoA11yViolations(page);
  await snapshot(page, "countdown-added");

  // It stays after a reload: it is stored, not only shown.
  await page.reload();
  await expect(row(page, title).getByText("In 10 days", { exact: true })).toBeVisible();

  await page.getByRole("button", { name: `Delete countdown: ${title}` }).click();
  const dialog = page.getByRole("alertdialog", { name: "Delete this countdown?" });
  await expect(dialog).toContainText(`The countdown “${title}” will be deleted.`);
  await expectNoA11yViolations(page);
  await snapshot(page, "countdown-delete-confirm");
  await dialog.getByRole("button", { name: "Cancel" }).click();
  await expect(dialog).toBeHidden();
  await expect(row(page, title)).toBeVisible();

  await page.getByRole("button", { name: `Delete countdown: ${title}` }).click();
  await dialog.getByRole("button", { name: "Delete countdown" }).click();
  await expect(widget(page).getByRole("status", { name: "Countdowns" })).toHaveText(
    `Countdown “${title}” deleted.`,
  );
  await expect(row(page, title)).toHaveCount(0);
  await page.reload();
  await expect(widget(page).getByRole("heading", { level: 2, name: "Countdowns" })).toBeVisible();
  await expect(row(page, title)).toHaveCount(0);
});

test.describe("in German", () => {
  test.use({ locale: "de-DE" });

  test("add and delete a custom countdown in German", async ({ page }) => {
    const title = uniqueName("Ende der Probezeit");
    await page.goto("/");
    const form = page.getByRole("form", { name: "Countdown anlegen" });
    await form.getByLabel("Worauf du wartest (Pflichtfeld)").fill(title);
    await form.getByLabel("Datum (Pflichtfeld)").fill(await dayFromToday(page, 1));
    await form.getByRole("button", { name: "Countdown hinzufügen" }).click();

    await expect(widget(page).getByRole("status", { name: "Countdowns" })).toHaveText(
      `Countdown „${title}“ hinzugefügt.`,
    );
    const countdown = row(page, title);
    await expect(countdown.getByText("Morgen", { exact: true })).toBeVisible();
    await expect(countdown.getByText("Dein Countdown", { exact: true })).toBeVisible();
    // A German date: 01.10.2026.
    await expect(countdown.getByText(/^\d{2}\.\d{2}\.\d{4}$/)).toBeVisible();
    await expectNoA11yViolations(page);
    await snapshot(page, "countdowns-de");

    await page.getByRole("button", { name: `Countdown löschen: ${title}` }).click();
    const dialog = page.getByRole("alertdialog", { name: "Diesen Countdown löschen?" });
    await expect(dialog).toContainText(`Der Countdown „${title}“ wird gelöscht.`);
    await dialog.getByRole("button", { name: "Countdown löschen" }).click();
    await expect(widget(page).getByRole("status", { name: "Countdowns" })).toHaveText(
      `Countdown „${title}“ gelöscht.`,
    );
    await expect(row(page, title)).toHaveCount(0);
  });
});
