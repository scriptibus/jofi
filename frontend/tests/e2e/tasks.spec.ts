// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { expect, type Page, test } from "@playwright/test";
import { api, choose, expectNoA11yViolations, mainNav, onStack, snapshot, uniqueName } from "./helpers.ts";

// The tasks page against the real backend (spec §10.2, #110): the grouping runs on the server in the browser's
// zone. Every browser project runs these in parallel on one stack and all tasks share one list, so each test
// finds its own tasks by their unique titles and never relies on a group's count.
test.skip(!onStack, "Needs the full stack: run `pnpm e2e`.");

interface Task {
  id: string;
  title: string;
}

const browserZone = (page: Page) => page.evaluate(() => Intl.DateTimeFormat().resolvedOptions().timeZone);

async function createTask(page: Page, prefix: string, timing: Record<string, string>): Promise<Task> {
  await page.goto("/tasks");
  const { request, headers } = await api(page);
  const data = { title: uniqueName(prefix), timing: { timeZone: await browserZone(page), ...timing } };
  const response = await request.post("/api/tasks", { data, headers });
  expect(response.status()).toBe(201);
  return (await response.json()) as Task;
}

/** The page's announcement of what just happened. */
const said = (page: Page, text: string) => page.getByRole("status").filter({ hasText: text });

/** A group's section, whatever its count: "Today (3)". */
const group = (page: Page, name: string) =>
  page.getByRole("region", { name: new RegExp(`^${name} \\(\\d+\\)$`) });

test("quick add a task for today: it shows in Today, the overdue one is marked", async ({ page }) => {
  const overdue = await createTask(page, "Send the thank-you note", { localDue: "2020-01-06T09:00" });
  const title = uniqueName("Book the train");
  await page.goto("/tasks");
  const quick = page.getByRole("region", { name: "Quick add" });
  await expect(quick.getByRole("radio", { name: "This week" })).toBeChecked();
  await quick.getByLabel("Task (required)").fill(title);
  await choose(page, "When", "Today");
  await quick.getByRole("button", { name: "Add task" }).click();

  await expect(said(page, `“${title}” added.`)).toBeVisible();
  await expect(group(page, "Today").getByRole("checkbox", { name: title })).toBeVisible();
  await expect(quick.getByLabel("Task (required)")).toHaveValue("");

  const late = group(page, "Overdue").getByRole("listitem").filter({ hasText: overdue.title });
  await expect(late.getByText("Overdue", { exact: true })).toBeVisible();
  await expectNoA11yViolations(page);
  await snapshot(page, "tasks");
});

test("add a task at an exact time, linked to an application; its chip opens the application", async ({
  page,
}) => {
  await page.goto("/tasks");
  const { request, headers } = await api(page);
  const company = await (
    await request.post("/api/companies", { data: { name: uniqueName("Aardvark Tasks") }, headers })
  ).json();
  const application = await (
    await request.post("/api/applications", {
      data: { title: uniqueName("Platform Engineer"), companyId: company.id },
      headers,
    })
  ).json();
  const title = uniqueName("Prepare the interview");
  const nextYear = new Date().getFullYear() + 1;

  await page.getByRole("link", { name: "New task with all details" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "New task" })).toBeVisible();
  await page.getByLabel("Task (required)").fill(title);
  await choose(page, "When", "At a set time");
  await page.getByLabel("Date and time (required)").fill(`${nextYear}-03-10T10:00`);
  await expect(page.getByText(`Time in ${await browserZone(page)}.`)).toBeVisible();
  await choose(page, "About", "Application");
  await page.getByRole("button", { name: /Application$/ }).click();
  await page.getByRole("option", { name: `${application.title} · ${company.name}` }).click();
  await page.getByLabel("Notes").fill("Read the **posting** again");
  await expectNoA11yViolations(page);
  await snapshot(page, "task-new");
  await page.getByRole("button", { name: "Create task" }).click();

  await expect(page).toHaveURL(/\/tasks$/);
  const row = group(page, "Later").getByRole("listitem").filter({ hasText: title });
  // ICU puts a narrow no-break space before "AM".
  await expect(row.getByText(new RegExp(`^Due Mar 10, ${nextYear}, 10:00\\sAM$`))).toBeVisible();
  await row.getByRole("button", { name: "Notes" }).click();
  await expect(row.getByText("posting", { exact: true })).toBeVisible();
  await row.getByRole("link", { name: `Application: ${application.title}` }).click();
  await expect(page.getByRole("heading", { level: 1, name: application.title })).toBeVisible();
});

test("complete a task and undo it; a completed task leaves the list", async ({ page }) => {
  const task = await createTask(page, "Call Anna", { bucket: "TODAY" });
  await page.goto("/tasks");
  const box = page.getByRole("checkbox", { name: task.title });
  await expect(box).not.toBeChecked();
  await page.getByText(task.title, { exact: true }).click();
  await expect(box).toBeChecked();
  await expect(said(page, `“${task.title}” is done.`)).toBeVisible();
  await snapshot(page, "task-done");

  await page.getByRole("button", { name: "Undo" }).click();
  await expect(said(page, `“${task.title}” is open again.`)).toBeVisible();
  await expect(box).not.toBeChecked();
  await page.reload();
  await expect(box).not.toBeChecked();

  await page.getByText(task.title, { exact: true }).click();
  await expect(said(page, `“${task.title}” is done.`)).toBeVisible();
  await page.reload();
  await expect(page.getByRole("heading", { level: 1, name: "Tasks" })).toBeVisible();
  await expect(box).toHaveCount(0);
});

test("a completed task is found under Done and reopened from there", async ({ page }) => {
  const task = await createTask(page, "Call Erika", { bucket: "TODAY" });
  await page.goto("/tasks");
  await page.getByText(task.title, { exact: true }).click();
  await expect(said(page, `“${task.title}” is done.`)).toBeVisible();
  await page.reload();
  await expect(page.getByRole("checkbox", { name: task.title })).toHaveCount(0);

  await page.getByRole("tab", { name: "Done" }).click();
  const done = page.getByRole("region", { name: "Done tasks" });
  const row = done.getByRole("listitem").filter({ hasText: task.title });
  await expect(row.getByText(/^Completed /)).toBeVisible();
  await expectNoA11yViolations(page);
  await snapshot(page, "tasks-done");

  await row.getByRole("button", { name: `Reopen task: ${task.title}` }).click();
  await expect(said(page, `“${task.title}” is open again.`)).toBeVisible();
  await expect(row).toHaveCount(0);
  await page.getByRole("tab", { name: "Open" }).click();
  await expect(group(page, "Today").getByRole("checkbox", { name: task.title })).not.toBeChecked();
});

test("a task completed elsewhere, as the AI could, is listed under Done and can be reopened", async ({
  page,
}) => {
  const task = await createTask(page, "Cancel the newsletter", { bucket: "THIS_MONTH" });
  const { request, headers } = await api(page);
  const completed = await request.post(`/api/tasks/${task.id}/complete`, {
    data: { basedOnVersion: 0 },
    headers,
  });
  expect(completed.status()).toBe(200);

  await page.goto("/tasks");
  await expect(page.getByRole("checkbox", { name: task.title })).toHaveCount(0);
  await page.getByRole("tab", { name: "Done" }).click();
  const row = page
    .getByRole("region", { name: "Done tasks" })
    .getByRole("listitem")
    .filter({ hasText: task.title });
  await expect(row).toBeVisible();
  await row.getByRole("button", { name: `Reopen task: ${task.title}` }).click();
  await expect(said(page, `“${task.title}” is open again.`)).toBeVisible();

  const reopened = await request.get(`/api/tasks/${task.id}`);
  expect(await reopened.json()).toMatchObject({ status: "OPEN", completedAt: null, version: 2 });
});

test("edit a task: the new title and bucket show in the list", async ({ page }) => {
  const task = await createTask(page, "Research ACME", { bucket: "SOMEDAY" });
  const title = uniqueName("Research ACME properly");
  await page.goto("/tasks");
  await page.getByRole("link", { name: `Edit task: ${task.title}` }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Edit task" })).toBeVisible();
  await expect(
    page.getByRole("radiogroup", { name: "When" }).getByRole("radio", { name: "Someday" }),
  ).toBeChecked();
  await page.getByLabel("Task (required)").fill(title);
  await choose(page, "When", "Next week");
  await expectNoA11yViolations(page);
  await snapshot(page, "task-edit");
  await page.getByRole("button", { name: "Save changes" }).click();

  await expect(page).toHaveURL(/\/tasks$/);
  await expect(group(page, "Next week").getByRole("checkbox", { name: title })).toBeVisible();
  await expect(page.getByRole("checkbox", { name: task.title, exact: true })).toHaveCount(0);
});

test("delete a task only after confirming", async ({ page }) => {
  const task = await createTask(page, "Cancel the gym", { bucket: "THIS_MONTH" });
  await page.goto("/tasks");
  await page.getByRole("button", { name: `Delete task: ${task.title}` }).click();
  const dialog = page.getByRole("alertdialog", { name: "Delete this task?" });
  await expect(dialog).toContainText(`“${task.title}” will be deleted.`);
  await expectNoA11yViolations(page);
  await snapshot(page, "task-delete-confirm");
  await dialog.getByRole("button", { name: "Cancel" }).click();
  await expect(dialog).toBeHidden();
  const { request } = await api(page);
  expect((await request.get(`/api/tasks/${task.id}`)).status()).toBe(200);

  await page.getByRole("button", { name: `Delete task: ${task.title}` }).click();
  await dialog.getByRole("button", { name: "Delete task" }).click();
  await expect(said(page, `“${task.title}” deleted.`)).toBeVisible();
  await expect(page.getByRole("checkbox", { name: task.title })).toHaveCount(0);
  expect((await request.get(`/api/tasks/${task.id}`)).status()).toBe(404);
});

test.describe("in German", () => {
  test.use({ locale: "de-DE" });

  test("add a task for today in German", async ({ page }) => {
    const title = uniqueName("Bewerbung abschicken");
    await page.goto("/");
    await mainNav(page).getByRole("link", { name: "Aufgaben" }).click();
    await expect(page.getByRole("heading", { level: 1, name: "Aufgaben" })).toBeVisible();
    const quick = page.getByRole("region", { name: "Schnell hinzufügen" });
    await quick.getByLabel("Aufgabe (Pflichtfeld)").fill(title);
    await choose(page, "Wann", "Heute");
    await quick.getByRole("button", { name: "Aufgabe hinzufügen" }).click();
    await expect(group(page, "Heute").getByRole("checkbox", { name: title })).toBeVisible();
    await expectNoA11yViolations(page);
    await snapshot(page, "tasks-de");
  });

  test("find a done task and reopen it in German", async ({ page }) => {
    const task = await createTask(page, "Anruf bei Erika", { bucket: "TODAY" });
    const { request, headers } = await api(page);
    await request.post(`/api/tasks/${task.id}/complete`, { data: { basedOnVersion: 0 }, headers });

    await page.goto("/tasks");
    await page.getByRole("tab", { name: "Erledigt" }).click();
    const done = page.getByRole("region", { name: "Erledigte Aufgaben" });
    const row = done.getByRole("listitem").filter({ hasText: task.title });
    await expect(row.getByText(/^Erledigt am /)).toBeVisible();
    await expectNoA11yViolations(page);
    await snapshot(page, "tasks-done-de");
    await row.getByRole("button", { name: `Aufgabe wieder öffnen: ${task.title}` }).click();
    await expect(said(page, `„${task.title}“ ist wieder offen.`)).toBeVisible();
  });
});
