// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { expect, type Page, test } from "@playwright/test";
import { api, expectNoA11yViolations, onStack, snapshot, uniqueName } from "./helpers.ts";

// The Timeline tab of the application detail page against the real backend (spec §6.1, §6.3, #106). Each test
// builds its own application through the API like a user would: created, moved to Applied, an interview logged
// and a task linked, then reads the timeline the server merges from all of them.
test.skip(!onStack, "Needs the full stack: run `pnpm e2e`.");

interface Created {
  id: string;
  title: string;
  version: number;
}

async function created(page: Page, path: string, data: Record<string, unknown>): Promise<Created> {
  const { request, headers } = await api(page);
  const response = await request.post(path, { data, headers });
  expect(response.status()).toBe(201);
  return (await response.json()) as Created;
}

/** An application with a status move, an interview (agreed in Berlin time) and an open task. */
async function arrange(page: Page) {
  await page.goto("/applications");
  const company = await created(page, "/api/companies", { name: uniqueName("Pied Piper") });
  const application = await created(page, "/api/applications", {
    title: uniqueName("Compression Engineer"),
    companyId: company.id,
  });
  const { request, headers } = await api(page);
  const moved = await request.put(`/api/applications/${application.id}/status`, {
    data: { basedOnVersion: application.version, status: "APPLIED" },
    headers,
  });
  expect(moved.status()).toBe(200);
  await created(page, `/api/applications/${application.id}/interviews`, {
    type: "PHONE_SCREEN",
    localStart: "2030-01-07T10:00",
    timeZone: "Europe/Berlin",
    participantIds: [],
  });
  const task = await created(page, "/api/tasks", {
    title: uniqueName("Prepare the phone screen"),
    timing: { timeZone: "Europe/Berlin", bucket: "THIS_WEEK" },
    link: { type: "APPLICATION", id: application.id },
  });
  return { application, task };
}

const entries = (page: Page, name: string) =>
  page.getByRole("list", { name, exact: true }).locator(":scope > li");

test("read the timeline: interview, task, status moves and the creation, newest first, with who did it", async ({
  page,
}) => {
  const { application, task } = await arrange(page);
  await page.goto(`/applications/${application.id}`);
  await page.getByRole("tab", { name: "Timeline" }).click();
  await expect(page).toHaveURL(/\?tab=timeline$/);

  const items = entries(page, "Timeline");
  // The interview sits at its start (2030), so it is on top; the creation is at the bottom.
  await expect(items.first()).toContainText("Interview");
  await expect(items.first()).toContainText("Phone screen");
  await expect(items.first().locator("time")).toHaveText(/^Jan 7, 2030, 10:00\sAM \(Europe\/Berlin\)$/);
  await expect(items.nth(1)).toContainText("Task");
  await expect(items.nth(1)).toContainText(task.title);
  await expect(items.nth(1)).toContainText("Open");

  const applied = items.filter({ hasText: "Status changed" }).filter({ hasText: "Applied" });
  await expect(applied).toContainText("Discovered");
  await expect(applied.getByText(/^By:/)).toBeAttached();
  await expect(applied).toContainText("User");
  await expect(items.filter({ hasText: "Created as" })).toContainText("Discovered");
  const createdEntry = items.filter({ hasText: "Details changed" });
  await expect(createdEntry).toContainText("Job title");
  await expect(createdEntry).toContainText("Company");
  await expect(items).toHaveCount(5);

  await expectNoA11yViolations(page);
  await snapshot(page, "application-timeline");
});

test.describe("in German", () => {
  test.use({ locale: "de-DE" });

  test("read the timeline in German", async ({ page }) => {
    const { application } = await arrange(page);
    await page.goto(`/applications/${application.id}?tab=timeline`);
    await expect(page.getByRole("tab", { name: "Verlauf" })).toHaveAttribute("aria-selected", "true");
    const items = entries(page, "Verlauf");
    await expect(items.first()).toContainText("Telefoninterview");
    await expect(items.first().locator("time")).toHaveText("07.01.2030, 10:00 (Europe/Berlin)");
    await expect(items.filter({ hasText: "Status geändert" }).filter({ hasText: "Beworben" })).toContainText(
      "Du",
    );
    await expect(page.getByText("Neueste zuerst.")).toBeVisible();
    await expectNoA11yViolations(page);
    await snapshot(page, "application-timeline-de");
  });
});
