// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { expect, type Locator, type Page, test } from "@playwright/test";
import { api, expectNoA11yViolations, onStack, snapshot, uniqueName } from "./helpers.ts";

// The status control and status history on the application detail page (spec §6.2, ADR-0044, #103),
// against the real backend. Every test creates its own company and application (unique names).
test.skip(!onStack, "Needs the full stack: run `pnpm e2e`.");

interface Application {
  id: string;
  title: string;
  version: number;
  status: string;
}

async function createApplication(page: Page, title: string): Promise<Application> {
  await page.goto("/companies");
  const { request, headers } = await api(page);
  const company = await request.post("/api/companies", { data: { name: uniqueName("Hooli") }, headers });
  expect(company.status()).toBe(201);
  const { id: companyId } = (await company.json()) as { id: string };
  const response = await request.post("/api/applications", { data: { title, companyId }, headers });
  expect(response.status()).toBe(201);
  return (await response.json()) as Application;
}

/** Opens the status control's list (its button is named by the placeholder and the label). */
async function openMoves(page: Page, label: string): Promise<Locator> {
  await page.getByRole("button", { name: new RegExp(`${label}$`) }).click();
  return page.getByRole("listbox", { name: label });
}

/** Picks a status in the control; the dialog for the move opens. */
async function moveTo(page: Page, label: string, status: string) {
  const list = await openMoves(page, label);
  await list.getByRole("option", { name: status, exact: true }).click();
  return page.getByRole("dialog");
}

async function pickReason(page: Page, dialog: Locator, label: string, category: string) {
  await dialog.getByRole("button", { name: new RegExp(`${label}$`) }).click();
  await page.getByRole("option", { name: category, exact: true }).click();
}

test("move an application through the pipeline, decline, correct the reason and reopen", async ({ page }) => {
  const application = await createApplication(page, uniqueName("Staff Engineer"));
  await page.goto(`/applications/${application.id}`);
  const status = page.getByRole("region", { name: "Status", exact: true });
  await expect(status.getByText("Discovered")).toBeVisible();

  // An invalid move is not offered: from Discovered there is no Accepted, Rejected or Ghosted.
  const moves = await openMoves(page, "Change status");
  await expect(moves.getByRole("option", { name: "Applied", exact: true })).toBeVisible();
  for (const invalid of ["Discovered", "Accepted", "Rejected", "Withdrawn", "Ghosted"])
    await expect(moves.getByRole("option", { name: invalid, exact: true })).toHaveCount(0);
  await page.keyboard.press("Escape");

  let dialog = await moveTo(page, "Change status", "Applied");
  await expect(dialog.getByRole("heading", { name: "Move to Applied" })).toBeVisible();
  await dialog.getByLabel("Why? (optional)").fill("Sent via the **portal**");
  await expectNoA11yViolations(page);
  await snapshot(page, "status-move-dialog");
  await dialog.getByRole("button", { name: "Change status" }).click();
  await expect(dialog).toBeHidden();
  await expect(status.getByText("Applied")).toBeVisible();

  for (const next of ["Interviewing", "Offer"]) {
    dialog = await moveTo(page, "Change status", next);
    await dialog.getByRole("button", { name: "Change status" }).click();
    await expect(dialog).toBeHidden();
    await expect(status.getByText(next)).toBeVisible();
  }

  // Declining needs a category: the dialog says so before anything is sent.
  dialog = await moveTo(page, "Change status", "Declined");
  await dialog.getByRole("button", { name: "Change status" }).click();
  await expect(dialog.getByText("Choose a reason.")).toBeVisible();
  await pickReason(page, dialog, "Reason", "Salary");
  await dialog.getByLabel("In detail (optional)").fill("Below my **minimum**");
  await expectNoA11yViolations(page);
  await snapshot(page, "status-decline-dialog");
  await dialog.getByRole("button", { name: "Change status" }).click();
  await expect(dialog).toBeHidden();
  await expect(status.getByText("Declined")).toBeVisible();
  await expect(status.getByText("Salary")).toBeVisible();
  await expect(status.locator("strong")).toHaveText("minimum");

  await status.getByRole("button", { name: "Correct reason" }).click();
  dialog = page.getByRole("dialog", { name: "Correct the reason" });
  await expect(dialog.getByLabel("In detail (optional)")).toHaveValue("Below my **minimum**");
  await pickReason(page, dialog, "Reason", "Location");
  await dialog.getByRole("button", { name: "Save reason" }).click();
  await expect(dialog).toBeHidden();
  await expect(status.getByText("Location")).toBeVisible();

  // Every ended status reopens into the pipeline.
  dialog = await moveTo(page, "Change status", "Interviewing");
  await dialog.getByLabel("Why? (optional)").fill("They raised the offer");
  await dialog.getByRole("button", { name: "Change status" }).click();
  await expect(dialog).toBeHidden();
  await expect(status.getByText("Interviewing")).toBeVisible();
  await expect(status.getByRole("button", { name: "Correct reason" })).toHaveCount(0);

  const entries = page.getByRole("region", { name: "Status history", exact: true }).getByRole("listitem");
  await expect(entries).toHaveCount(7);
  await expect(entries.nth(0)).toContainText(/Created as\s*Discovered/);
  await expect(entries.nth(1)).toContainText(/Discovered\s*→\s*to\s*Applied/);
  await expect(entries.nth(1)).toContainText("by you");
  await expect(entries.nth(1).locator("strong")).toHaveText("portal");
  await expect(entries.nth(4)).toContainText(/Offer\s*→\s*to\s*Declined/);
  await expect(entries.nth(4)).toContainText("Reason: Salary");
  await expect(entries.nth(5)).toContainText(/Declined\s*→\s*to\s*Declined/);
  await expect(entries.nth(5)).toContainText("Reason: Location");
  await expect(entries.nth(6)).toContainText(/Declined\s*→\s*to\s*Interviewing/);
  await expect(entries.nth(6)).toContainText("They raised the offer");
  await expectNoA11yViolations(page);
  await snapshot(page, "status-history");
});

test("a status change based on an old version is refused; loading the latest version shows it", async ({
  page,
}) => {
  const application = await createApplication(page, uniqueName("Security Engineer"));
  await page.goto(`/applications/${application.id}`);
  const status = page.getByRole("region", { name: "Status", exact: true });
  await expect(status.getByText("Discovered")).toBeVisible();

  // Meanwhile another tab (here: the API) moves it.
  const { request, headers } = await api(page);
  const put = await request.put(`/api/applications/${application.id}/status`, {
    data: { status: "SHORTLISTED", basedOnVersion: application.version },
    headers,
  });
  expect(put.status()).toBe(200);

  const dialog = await moveTo(page, "Change status", "Preparing");
  await dialog.getByRole("button", { name: "Change status" }).click();
  const conflict = dialog.getByRole("alert").filter({ hasText: "Changed meanwhile" });
  await expect(conflict).toBeVisible();
  await expectNoA11yViolations(page);
  await snapshot(page, "status-conflict");
  await conflict.getByRole("button", { name: "Load latest version" }).click();
  await expect(dialog).toBeHidden();
  await expect(status.getByText("Shortlisted")).toBeVisible();
  await expect(
    page.getByRole("region", { name: "Status history", exact: true }).getByRole("listitem"),
  ).toHaveCount(2);
});

test.describe("in German", () => {
  test.use({ locale: "de-DE" });

  test("decline an application in German and see it in the history", async ({ page }) => {
    const application = await createApplication(page, uniqueName("Plattform-Entwicklerin"));
    await page.goto(`/applications/${application.id}`);
    const status = page.getByRole("region", { name: "Status", exact: true });
    await expect(status.getByText("Entdeckt")).toBeVisible();

    const dialog = await moveTo(page, "Status ändern", "Selbst abgesagt");
    await expect(dialog.getByRole("heading", { name: "Status: Selbst abgesagt" })).toBeVisible();
    await pickReason(page, dialog, "Grund", "Remote-Regelung");
    await dialog.getByLabel("Im Detail (optional)").fill("Nur vor Ort");
    await expectNoA11yViolations(page);
    await snapshot(page, "status-decline-dialog-de");
    await dialog.getByRole("button", { name: "Status ändern" }).click();
    await expect(dialog).toBeHidden();
    await expect(status.getByText("Remote-Regelung")).toBeVisible();

    const entries = page.getByRole("region", { name: "Statusverlauf", exact: true }).getByRole("listitem");
    await expect(entries).toHaveCount(2);
    await expect(entries.nth(0)).toContainText(/Angelegt als\s*Entdeckt/);
    await expect(entries.nth(1)).toContainText(/Entdeckt\s*→\s*nach\s*Selbst abgesagt/);
    await expect(entries.nth(1)).toContainText("von dir");
    await expect(entries.nth(1)).toContainText("Grund: Remote-Regelung");
    await expectNoA11yViolations(page);
    await snapshot(page, "status-history-de");
  });
});
