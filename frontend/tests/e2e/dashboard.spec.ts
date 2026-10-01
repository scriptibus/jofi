// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { expect, type Page, test } from "@playwright/test";
import { api, expectNoA11yViolations, mainNav, onStack, snapshot, uniqueName } from "./helpers.ts";

// The dashboard against the real backend (spec §10.1, #114): every figure comes from the seeded stack plus what a
// test adds. All browser projects share one instance in parallel, so the tests never rely on a count, only on
// their own records (found by unique titles) and on what every instance has.
test.skip(!onStack, "Needs the full stack: run `pnpm e2e`.");

interface Created {
  id: string;
  title: string;
  version: number;
}

/** An application moved straight to Interviewing, so it counts in the pipeline and in every funnel stage. */
async function interviewingApplication(page: Page): Promise<Created> {
  const { request, headers } = await api(page);
  const company = await request.post("/api/companies", {
    data: { name: uniqueName("Dashboard Co") },
    headers,
  });
  expect(company.status()).toBe(201);
  const { id: companyId } = (await company.json()) as { id: string };
  const created = await request.post("/api/applications", {
    data: { title: uniqueName("Data Engineer"), companyId },
    headers,
  });
  expect(created.status()).toBe(201);
  const application = (await created.json()) as Created;
  const moved = await request.put(`/api/applications/${application.id}/status`, {
    data: { status: "INTERVIEWING", basedOnVersion: application.version },
    headers,
  });
  expect(moved.status()).toBe(200);
  return application;
}

/** A task due long ago, so it is among the first overdue ones (soonest first). */
async function overdueTask(page: Page): Promise<Created> {
  const { request, headers } = await api(page);
  const timeZone = await page.evaluate(() => Intl.DateTimeFormat().resolvedOptions().timeZone);
  const response = await request.post("/api/tasks", {
    data: { title: uniqueName("Chase the recruiter"), timing: { timeZone, localDue: "2001-01-08T09:00" } },
    headers,
  });
  expect(response.status()).toBe(201);
  return (await response.json()) as Created;
}

/** Done tasks leave the dashboard, so a reused stack does not pile up old overdue tasks. */
async function complete(page: Page, task: Created) {
  const { request, headers } = await api(page);
  const response = await request.post(`/api/tasks/${task.id}/complete`, {
    data: { basedOnVersion: task.version },
    headers,
  });
  expect(response.status()).toBe(200);
}

const widget = (page: Page, name: string) => page.getByRole("region", { name, exact: true });

test("dashboard: every widget shows the instance's figures and links to the matching view", async ({
  page,
}) => {
  await page.goto("/");
  await interviewingApplication(page);
  const task = await overdueTask(page);
  try {
    await page.goto("/");
    await expect(
      page.getByRole("heading", { level: 1, name: "Let the donkey do the donkey work." }),
    ).toBeVisible();

    const tasks = widget(page, "Tasks");
    await expect(tasks.getByRole("list", { name: /^Overdue \(\d+\)$/ }).getByText(task.title)).toBeVisible();

    const funnel = widget(page, "Funnel");
    await expect(funnel.getByText(/^Response rate: \d+ of \d+ applications? got an answer\.$/)).toBeVisible();
    await expect(widget(page, "Recent activity").getByRole("listitem").first()).toBeVisible();
    await expect(widget(page, "AI cost this month").getByText(/^\$[\d,.]+$/)).toBeVisible();

    const pipeline = widget(page, "Pipeline");
    await expect(pipeline.getByRole("link", { name: "Interviewing" })).toBeVisible();
    await expectNoA11yViolations(page);
    await snapshot(page, "dashboard");

    // The status links to the list filtered by it.
    await pipeline.getByRole("link", { name: "Interviewing" }).click();
    await expect(page.getByRole("heading", { level: 1, name: "Applications" })).toBeVisible();
    await expect(page).toHaveURL(/\/applications\?status=.*INTERVIEWING/);

    // The tasks widget leads to the tasks, the AI cost to the budget in Settings.
    await mainNav(page).getByRole("link", { name: "Dashboard" }).click();
    await widget(page, "Tasks").getByRole("link", { name: "All tasks" }).click();
    await expect(page.getByRole("heading", { level: 1, name: "Tasks" })).toBeVisible();
    await page.goBack();
    await widget(page, "AI cost this month").getByRole("link", { name: "AI budget in Settings" }).click();
    await expect(page).toHaveURL(/\/settings#ai-budget-heading$/);
    await expect(page.getByRole("heading", { level: 2, name: "Monthly AI budget" })).toBeVisible();
  } finally {
    await complete(page, task);
  }
});

test("dashboard: the seeded instance has no AI budget, which the cost widget says", async ({ page }) => {
  // The budget is only changed by the `ai-setup` project, which runs after the browser projects.
  await page.goto("/");
  const cost = widget(page, "AI cost this month");
  await expect(cost.getByText("No monthly budget set.")).toBeVisible();
  await expect(cost.getByRole("alert")).toHaveCount(0);
});

test.describe("in German", () => {
  test.use({ locale: "de-DE" });

  test("dashboard: widgets, numbers and links read in German", async ({ page }) => {
    await page.goto("/");
    await interviewingApplication(page);
    await page.goto("/");
    await expect(
      page.getByRole("heading", { level: 1, name: "Lass den Esel die Eselsarbeit machen." }),
    ).toBeVisible();
    for (const name of ["Aufgaben", "Pipeline", "Trichter", "Letzte Aktivität", "KI-Kosten diesen Monat"])
      await expect(widget(page, name)).toBeVisible();
    await expect(widget(page, "Pipeline").getByRole("link", { name: "Im Gespräch" })).toBeVisible();
    await expect(widget(page, "Trichter").getByText(/^Antwortquote: /)).toBeVisible();
    // German money: the amount before the sign.
    await expect(widget(page, "KI-Kosten diesen Monat").getByText(/^[\d.,]+\s\$$/)).toBeVisible();
    await expectNoA11yViolations(page);
    await snapshot(page, "dashboard-de");
  });
});
