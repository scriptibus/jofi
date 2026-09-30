// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { expect, type Page, test } from "@playwright/test";
import { api, expectNoA11yViolations, onStack, snapshot, uniqueName } from "./helpers.ts";

// Suggested tasks (spec §10.2, #111) against the real backend: logging an interview makes the worker run the
// suggestion rules (ADR-0049), which suggest preparing for it the day before. Every browser project runs these in
// parallel on one stack and all suggestions share one list, so each test finds its own by its applications' unique
// titles.
test.skip(!onStack, "Needs the full stack: run `pnpm e2e`.");

// The worker picks queued jobs up every few seconds (JobRunr's poll interval), so a suggestion takes a moment.
test.setTimeout(120_000);
const SUGGESTION_WAIT = { timeout: 90_000, intervals: [1_000, 2_000, 5_000] };

interface Task {
  id: string;
  status: string;
  link: { type: string; id: string } | null;
}

/** A clock time five days ahead, e.g. `2026-10-06T10:00:00`: still to come, and its day before too. */
function inFiveDays(): string {
  const day = new Date(Date.now() + 5 * 86_400_000).toISOString().slice(0, 10);
  return `${day}T10:00:00`;
}

/**
 * Two applications with an interview each, logged through the API like a user does, and the id of the
 * preparation suggestion the worker made for each.
 */
async function applicationsWithInterviews(page: Page, prefix: string) {
  await page.goto("/tasks");
  const { request, headers } = await api(page);
  const zone = await page.evaluate(() => Intl.DateTimeFormat().resolvedOptions().timeZone);
  const company = await (
    await request.post("/api/companies", { data: { name: uniqueName("Suggestion Works") }, headers })
  ).json();
  const applications: { id: string; title: string; suggestion: string }[] = [];
  for (const role of ["Accepted", "Dismissed"]) {
    const title = uniqueName(`${prefix} ${role}`);
    const created = await request.post("/api/applications", {
      data: { title, companyId: company.id },
      headers,
    });
    expect(created.status()).toBe(201);
    const { id } = await created.json();
    const interview = await request.post(`/api/applications/${id}/interviews`, {
      data: { type: "TECHNICAL", localStart: inFiveDays(), timeZone: zone, participantIds: [] },
      headers,
    });
    expect(interview.status()).toBe(201);
    applications.push({ id, title, suggestion: "" });
  }

  const suggestionFor = async (applicationId: string) => {
    const { tasks } = (await (await request.get("/api/tasks/suggestions")).json()) as { tasks: Task[] };
    return tasks.find((task) => task.link?.id === applicationId)?.id ?? "";
  };
  for (const application of applications) {
    await expect.poll(() => suggestionFor(application.id), SUGGESTION_WAIT).not.toBe("");
    application.suggestion = await suggestionFor(application.id);
  }
  const [accepted, dismissed] = applications;
  if (!accepted || !dismissed) throw new Error("expected two applications");
  return { accepted, dismissed };
}

const statusOf = async (page: Page, id: string) => {
  const { request } = await api(page);
  return ((await (await request.get(`/api/tasks/${id}`)).json()) as Task).status;
};

/** The page's announcement of what just happened. */
const said = (page: Page, text: string) => page.getByRole("status").filter({ hasText: text });

test("accept one suggestion and dismiss another, each with one click", async ({ page }) => {
  const { accepted, dismissed } = await applicationsWithInterviews(page, "Suggested Engineer");
  const acceptedTitle = `Prepare for the interview: ${accepted.title}`;
  const dismissedTitle = `Prepare for the interview: ${dismissed.title}`;

  await page.goto("/tasks");
  const section = page.getByRole("region", { name: "Suggested tasks" });
  const row = section.getByRole("listitem").filter({ hasText: acceptedTitle });
  await expect(row.getByRole("link", { name: `Application: ${accepted.title}` })).toBeVisible();
  await expect(row.getByText(/^Due /)).toBeVisible();
  await expectNoA11yViolations(page);
  await snapshot(page, "task-suggestions");

  await section.getByRole("button", { name: `Accept suggestion: ${acceptedTitle}` }).click();
  await expect(said(page, `“${acceptedTitle}” added to your tasks.`)).toBeVisible();
  await expect(section.getByText(acceptedTitle, { exact: true })).toHaveCount(0);
  await expect(page.getByRole("checkbox", { name: acceptedTitle })).not.toBeChecked();
  await expect.poll(() => statusOf(page, accepted.suggestion)).toBe("OPEN");

  await section.getByRole("button", { name: `Dismiss suggestion: ${dismissedTitle}` }).click();
  await expect(said(page, `Suggestion “${dismissedTitle}” dismissed.`)).toBeVisible();
  await expect(section.getByText(dismissedTitle, { exact: true })).toHaveCount(0);
  await expect(page.getByRole("checkbox", { name: dismissedTitle })).toHaveCount(0);
  await expect.poll(() => statusOf(page, dismissed.suggestion)).toBe("DISMISSED");
  await expectNoA11yViolations(page);
  await snapshot(page, "task-suggestions-decided");

  // Both stay decided: neither comes back as a suggestion.
  await page.reload();
  await expect(page.getByRole("checkbox", { name: acceptedTitle })).toBeVisible();
  await expect(section.getByText(acceptedTitle, { exact: true })).toHaveCount(0);
  await expect(section.getByText(dismissedTitle, { exact: true })).toHaveCount(0);
});

test.describe("in German", () => {
  test.use({ locale: "de-DE" });

  test("accept one suggestion and dismiss another in German", async ({ page }) => {
    const { accepted, dismissed } = await applicationsWithInterviews(page, "Vorgeschlagene Stelle");
    const acceptedTitle = `Auf das Vorstellungsgespräch vorbereiten: ${accepted.title}`;
    const dismissedTitle = `Auf das Vorstellungsgespräch vorbereiten: ${dismissed.title}`;

    await page.goto("/tasks");
    const section = page.getByRole("region", { name: "Vorgeschlagene Aufgaben" });
    await expect(section.getByText(acceptedTitle, { exact: true })).toBeVisible();
    await expectNoA11yViolations(page);
    await snapshot(page, "task-suggestions-de");

    await section.getByRole("button", { name: `Annehmen: Vorschlag ${acceptedTitle}` }).click();
    await expect(said(page, `„${acceptedTitle}“ zu deinen Aufgaben hinzugefügt.`)).toBeVisible();
    await expect(page.getByRole("checkbox", { name: acceptedTitle })).not.toBeChecked();

    await section.getByRole("button", { name: `Verwerfen: Vorschlag ${dismissedTitle}` }).click();
    await expect(said(page, `Vorschlag „${dismissedTitle}“ verworfen.`)).toBeVisible();
    await expect(section.getByText(dismissedTitle, { exact: true })).toHaveCount(0);
    await expect.poll(() => statusOf(page, accepted.suggestion)).toBe("OPEN");
    await expect.poll(() => statusOf(page, dismissed.suggestion)).toBe("DISMISSED");
    await expectNoA11yViolations(page);
  });
});
