// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { expect, type Locator, type Page, test } from "@playwright/test";
import { api, expectNoA11yViolations, onStack, snapshot, uniqueName } from "./helpers.ts";

// Interviews and calls on the application detail page against the real backend (spec §6.1, ADR-0048, #107).
// Each test builds its own company, contact and application through the API, then logs, edits and deletes an
// interview through the UI and reads it on the timeline the server merges.
test.skip(!onStack, "Needs the full stack: run `pnpm e2e`.");

interface Created {
  id: string;
  name?: string;
  title?: string;
}

async function post(page: Page, path: string, data: Record<string, unknown>): Promise<Created> {
  const { request, headers } = await api(page);
  const response = await request.post(path, { data, headers });
  expect(response.status()).toBe(201);
  return (await response.json()) as Created;
}

/** A company with one contact and an application at it. */
async function arrange(page: Page) {
  await page.goto("/applications");
  const company = await post(page, "/api/companies", { name: uniqueName("Hooli") });
  const contact = await post(page, "/api/contacts", {
    name: uniqueName("Gavin Belson"),
    companyId: company.id,
    role: "CEO",
  });
  const application = await post(page, "/api/applications", {
    title: uniqueName("Platform Engineer"),
    companyId: company.id,
  });
  return { contact: { id: contact.id, name: contact.name ?? "" }, application };
}

/** Picks `option` in one of our Selects, found by its label at the end of the button's name. */
async function choose(page: Page, form: Locator, label: string, option: string) {
  await form.getByRole("button", { name: new RegExp(`${label}$`) }).click();
  await page.getByRole("option", { name: option, exact: true }).click();
}

/** Types the start into the date and time segments, starting at the first one (`keys` in the locale's order). */
async function typeStart(page: Page, form: Locator, keys: string) {
  await form.getByRole("spinbutton").first().click();
  await page.keyboard.type(keys);
}

async function addParticipant(page: Page, form: Locator, buttonLabel: string, pick: string) {
  await form.getByRole("button", { name: buttonLabel }).click();
  const picker = page.getByRole("dialog");
  await picker.getByRole("button", { name: pick }).click();
  await expect(picker).toBeHidden();
}

test("log, edit and delete an interview, and see it on the timeline", async ({ page }) => {
  const { application, contact } = await arrange(page);
  await page.goto(`/applications/${application.id}`);
  await page.getByRole("tab", { name: "Interviews" }).click();
  await expect(page).toHaveURL(/\?tab=interviews$/);
  const section = page.getByRole("region", { name: "Interviews and calls" });
  await expect(section.getByText(/No interviews or calls logged yet/)).toBeVisible();

  await section.getByRole("button", { name: "Log an interview or call" }).click();
  const form = section.getByRole("region", { name: "Log an interview or call" });
  await choose(page, form, "Type", "Technical interview");
  await typeStart(page, form, "01072030" + "1000A");
  await choose(page, form, "Time zone", "Europe/Berlin");
  await addParticipant(page, form, "Add a participant", `Add ${contact.name}`);
  await expect(form.getByRole("button", { name: `Remove ${contact.name}` })).toBeVisible();
  await form.getByRole("textbox", { name: "Preparation notes" }).fill("Revise **system design**");
  await expectNoA11yViolations(page);
  await snapshot(page, "application-interview-form");
  await form.getByRole("button", { name: "Log it" }).click();

  await expect(section.getByRole("status")).toHaveText("The interview was logged.");
  const card = section.getByRole("article").filter({ hasText: "Technical interview" });
  await expect(card.locator("time")).toHaveText(/^Jan 7, 2030, 10:00\sAM \(Europe\/Berlin\)$/);
  await expect(card).toContainText(contact.name);
  await expect(card.getByText("system design")).toBeVisible();
  await expectNoA11yViolations(page);
  await snapshot(page, "application-interviews");

  await page.getByRole("tab", { name: "Timeline" }).click();
  const timeline = page.getByRole("list", { name: "Timeline", exact: true }).locator(":scope > li");
  await expect(timeline.first()).toContainText("Interview");
  await expect(timeline.first()).toContainText("Technical interview");
  await expect(timeline.first().locator("time")).toHaveText(/^Jan 7, 2030, 10:00\sAM \(Europe\/Berlin\)$/);

  await page.getByRole("tab", { name: "Interviews" }).click();
  await section.getByRole("button", { name: /^Edit Technical interview on Jan 7, 2030/ }).click();
  const edit = section.getByRole("region", { name: "Edit: Technical interview" });
  await choose(page, edit, "Outcome", "Passed");
  await edit.getByRole("textbox", { name: "Notes afterwards" }).fill("Next round on site");
  await edit.getByRole("button", { name: "Save changes" }).click();
  await expect(section.getByRole("status")).toHaveText("The interview was saved.");
  await expect(card).toContainText("Passed");
  await expect(card).toContainText("Next round on site");

  const remove = section.getByRole("button", { name: /^Delete Technical interview on Jan 7, 2030/ });
  await remove.click();
  const question = page.getByRole("alertdialog", { name: "Delete this interview?" });
  await expect(question).toContainText(
    /Technical interview on Jan 7, 2030, 10:00\sAM \(Europe\/Berlin\) will be deleted/,
  );
  await question.getByRole("button", { name: "Cancel" }).click();
  await expect(question).toBeHidden();
  await expect(card).toBeVisible();

  await remove.click();
  await page.getByRole("alertdialog").getByRole("button", { name: "Delete" }).click();
  await expect(section.getByRole("status")).toHaveText("The interview was deleted.");
  await expect(section.getByText(/No interviews or calls logged yet/)).toBeVisible();

  await page.getByRole("tab", { name: "Timeline" }).click();
  await expect(page.getByRole("list", { name: "Timeline", exact: true })).not.toContainText(
    "Technical interview",
  );
});

test("more than 50 interviews load a page at a time, long notes show an excerpt until asked for", async ({
  page,
}, testInfo) => {
  // 51 interviews are many writes (each schedules suggestions): one project is enough, and it gets more time.
  test.skip(testInfo.project.name !== "desktop-light", "One run is enough: it logs 51 interviews.");
  test.slow();
  const { application } = await arrange(page);
  const { request, headers } = await api(page);
  const long = `Start of the notes ${"x".repeat(400)} the very end`;
  for (let from = 0; from < 51; from += 5) {
    const batch = Array.from({ length: Math.min(5, 51 - from) }, async (_, offset) => {
      const index = from + offset;
      const data = {
        type: "PHONE_SCREEN",
        localStart: `2031-01-${String(1 + Math.floor(index / 24)).padStart(2, "0")}T${String(index % 24).padStart(2, "0")}:00`,
        timeZone: "Europe/Berlin",
        participantIds: [],
        notes: index === 0 ? long : null,
      };
      expect(
        (await request.post(`/api/applications/${application.id}/interviews`, { data, headers })).status(),
      ).toBe(201);
    });
    await Promise.all(batch);
  }

  await page.goto(`/applications/${application.id}?tab=interviews`);
  const section = page.getByRole("region", { name: "Interviews and calls" });
  await expect(section.getByRole("article")).toHaveCount(50);
  await expectNoA11yViolations(page);
  await snapshot(page, "application-interviews-paged");

  await section.getByRole("button", { name: "Show more interviews" }).click();
  await expect(section.getByRole("article")).toHaveCount(51);
  await expect(section.getByRole("button", { name: "Show more interviews" })).toHaveCount(0);

  const first = section.getByRole("article").first();
  await expect(first.getByText(/^Start of the notes x+$/)).toBeVisible();
  await first.getByRole("button", { name: "Show all notes" }).click();
  await expect(first.getByText(long)).toBeVisible();
  await expectNoA11yViolations(page);

  await first.getByRole("button", { name: /^Edit / }).click();
  await expect(page.getByRole("textbox", { name: "Notes afterwards" })).toHaveValue(long);
});

test.describe("in German", () => {
  test.use({ locale: "de-DE" });

  test("ein Interview eintragen und im Verlauf sehen", async ({ page }) => {
    const { application, contact } = await arrange(page);
    await page.goto(`/applications/${application.id}?tab=interviews`);
    await expect(page.getByRole("tab", { name: "Interviews" })).toHaveAttribute("aria-selected", "true");
    const section = page.getByRole("region", { name: "Interviews und Gespräche" });
    await section.getByRole("button", { name: "Interview oder Gespräch eintragen" }).click();
    const form = section.getByRole("region", { name: "Interview oder Gespräch eintragen" });
    await typeStart(page, form, "07012030" + "1000");
    await choose(page, form, "Zeitzone", "Europe/Berlin");
    await addParticipant(page, form, "Teilnehmende hinzufügen", `${contact.name} hinzufügen`);
    await form.getByRole("button", { name: "Eintragen" }).click();

    await expect(section.getByRole("status")).toHaveText("Das Interview ist eingetragen.");
    await expect(section.getByRole("article").locator("time")).toHaveText(
      "07.01.2030, 10:00 (Europe/Berlin)",
    );
    await expectNoA11yViolations(page);
    await snapshot(page, "application-interviews-de");

    await page.getByRole("tab", { name: "Verlauf" }).click();
    const first = page.getByRole("list", { name: "Verlauf", exact: true }).locator(":scope > li").first();
    await expect(first).toContainText("Telefoninterview");
    await expect(first.locator("time")).toHaveText("07.01.2030, 10:00 (Europe/Berlin)");
  });
});
