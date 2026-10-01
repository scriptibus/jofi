// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { expect, type Page, test } from "@playwright/test";
import { api, expectNoA11yViolations, onStack, snapshot, uniqueName } from "./helpers.ts";

// Saved views of the applications list against the real backend (spec §6.3, #101). Every browser project
// runs these in parallel on one stack, and views are shared by all of them, so each test names its views
// uniquely and filters by a company of its own.
test.skip(!onStack, "Needs the full stack: run `pnpm e2e`.");

interface Created {
  id: string;
  name: string;
}

async function createCompanyWithApplications(page: Page, prefix: string): Promise<Created> {
  const { request, headers } = await api(page);
  const company = await request.post("/api/companies", { data: { name: uniqueName(prefix) }, headers });
  expect(company.status()).toBe(201);
  const created = (await company.json()) as Created;
  for (const title of ["Platform Engineer", "Data Engineer"]) {
    const response = await request.post("/api/applications", {
      data: { companyId: created.id, title: `${title} ${uniqueName("#")}` },
      headers,
    });
    expect(response.status()).toBe(201);
  }
  return created;
}

interface Texts {
  region: string;
  save: string;
  saveTitle: string;
  name: string;
  actions: (name: string) => string;
  rename: string;
  renameTitle: string;
  renameAction: string;
  delete: string;
  deleteTitle: string;
  deleteAction: string;
  opened: (name: string) => string;
  deleted: (name: string) => string;
  matching: string;
}

const english: Texts = {
  region: "Saved views",
  save: "Save view",
  saveTitle: "Save this view",
  name: "Name",
  actions: (name) => `Actions for ${name}`,
  rename: "Rename…",
  renameTitle: "Rename view",
  renameAction: "Rename",
  delete: "Delete…",
  deleteTitle: "Delete this view?",
  deleteAction: "Delete view",
  opened: (name) => `Opened view ${name}.`,
  deleted: (name) => `Deleted view ${name}.`,
  matching: "2 applications match",
};

const german: Texts = {
  region: "Gespeicherte Ansichten",
  save: "Ansicht speichern",
  saveTitle: "Diese Ansicht speichern",
  name: "Name",
  actions: (name) => `Aktionen für ${name}`,
  rename: "Umbenennen …",
  renameTitle: "Ansicht umbenennen",
  renameAction: "Umbenennen",
  delete: "Löschen …",
  deleteTitle: "Diese Ansicht löschen?",
  deleteAction: "Ansicht löschen",
  opened: (name) => `Ansicht ${name} geöffnet.`,
  deleted: (name) => `Ansicht ${name} gelöscht.`,
  matching: "2 Bewerbungen passen",
};

/** Save the filtered list as a view, open it from an unfiltered list, rename it, delete it. */
async function saveReopenRenameDelete(page: Page, text: Texts) {
  await page.goto("/applications");
  const company = await createCompanyWithApplications(page, "Initech");
  const name = uniqueName("Initech by title");
  const renamed = `${name} (renamed)`;
  const views = page.getByRole("region", { name: text.region });

  await page.goto(`/applications?company=${company.id}&sort=TITLE&dir=DESCENDING`);
  await expect(page.getByText(text.matching)).toBeVisible();
  await views.getByRole("button", { name: text.save }).click();
  const saveDialog = page.getByRole("dialog", { name: text.saveTitle });
  await saveDialog.getByRole("textbox", { name: text.name }).fill(name);
  await expectNoA11yViolations(page);
  await snapshot(page, "saved-views-save");
  await saveDialog.getByRole("button", { name: text.save }).click();
  await expect(saveDialog).toBeHidden();
  await expect(views.getByRole("button", { name, exact: true })).toBeVisible();

  // From the unfiltered list, the view brings back its company and order.
  await page.goto("/applications");
  await views.getByRole("button", { name, exact: true }).click();
  await expect(views.getByRole("status")).toHaveText(text.opened(name));
  await expect(page).toHaveURL(new RegExp(`company=${company.id}`));
  await expect(page).toHaveURL(/sort=TITLE&dir=DESCENDING|dir=DESCENDING.*sort=TITLE/);
  await expect(page.getByText(text.matching)).toBeVisible();
  await expect(page.getByRole("link", { name: /^(Platform|Data) Engineer/ })).toHaveText([
    /^Platform/,
    /^Data/,
  ]);
  await expectNoA11yViolations(page);
  await snapshot(page, "saved-views-opened");

  await views.getByRole("button", { name: text.actions(name) }).click();
  await page.getByRole("menuitem", { name: text.rename }).click();
  const renameDialog = page.getByRole("dialog", { name: text.renameTitle });
  await renameDialog.getByRole("textbox", { name: text.name }).fill(renamed);
  await renameDialog.getByRole("button", { name: text.renameAction }).click();
  await expect(renameDialog).toBeHidden();
  await expect(views.getByRole("button", { name: renamed, exact: true })).toBeVisible();
  await expect(views.getByRole("button", { name, exact: true })).toHaveCount(0);

  await views.getByRole("button", { name: text.actions(renamed) }).click();
  await page.getByRole("menuitem", { name: text.delete }).click();
  const confirm = page.getByRole("alertdialog", { name: text.deleteTitle });
  await expect(confirm).toContainText(renamed);
  await expectNoA11yViolations(page);
  await snapshot(page, "saved-views-delete");
  await confirm.getByRole("button", { name: text.deleteAction }).click();
  await expect(confirm).toBeHidden();
  await expect(views.getByRole("status")).toHaveText(text.deleted(renamed));
  await expect(views.getByRole("button", { name: renamed, exact: true })).toHaveCount(0);
}

test("save a view, reopen it, rename it and delete it", async ({ page }) => {
  await saveReopenRenameDelete(page, english);
});

test("a second view with the same name is refused", async ({ page }) => {
  await page.goto("/applications");
  const name = uniqueName("Duplicate");
  const views = page.getByRole("region", { name: english.region });
  for (const attempt of [name, name.toUpperCase()]) {
    await views.getByRole("button", { name: english.save }).click();
    const dialog = page.getByRole("dialog", { name: english.saveTitle });
    await dialog.getByRole("textbox", { name: english.name }).fill(attempt);
    await dialog.getByRole("button", { name: english.save }).click();
  }
  const dialog = page.getByRole("dialog", { name: english.saveTitle });
  await expect(dialog.getByText("Another view already has this name.")).toBeVisible();
  await dialog.getByRole("button", { name: "Cancel" }).click();

  // Clean up: the view list is shared by every project.
  await views.getByRole("button", { name: english.actions(name) }).click();
  await page.getByRole("menuitem", { name: english.delete }).click();
  await page.getByRole("alertdialog").getByRole("button", { name: english.deleteAction }).click();
  await expect(views.getByRole("button", { name, exact: true })).toHaveCount(0);
});

test.describe("in German", () => {
  test.use({ locale: "de-DE" });

  test("eine Ansicht speichern, wieder öffnen, umbenennen und löschen", async ({ page }) => {
    await saveReopenRenameDelete(page, german);
  });
});
