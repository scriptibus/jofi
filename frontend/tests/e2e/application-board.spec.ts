// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { expect, type Locator, type Page, test } from "@playwright/test";
import { api, expectNoA11yViolations, onStack, snapshot, uniqueName } from "./helpers.ts";

// The Kanban board (spec §6.3, ADR-0044, #36) against the real backend. Every browser project runs these in
// parallel on one stack, so each test creates its own company and applications and filters the board by
// that company (the same `?company=` filter the table uses).
test.skip(!onStack, "Needs the full stack: run `pnpm e2e`.");

interface Created {
  id: string;
  title: string;
  version: number;
}

async function createCompany(page: Page): Promise<string> {
  await page.goto("/companies");
  const { request, headers } = await api(page);
  const response = await request.post("/api/companies", { data: { name: uniqueName("Initech") }, headers });
  expect(response.status()).toBe(201);
  return ((await response.json()) as { id: string }).id;
}

async function createApplication(page: Page, companyId: string, title: string, status?: string) {
  const { request, headers } = await api(page);
  const response = await request.post("/api/applications", { data: { companyId, title }, headers });
  expect(response.status()).toBe(201);
  const created = (await response.json()) as Created;
  if (!status) return created;
  const moved = await request.put(`/api/applications/${created.id}/status`, {
    data: { basedOnVersion: created.version, status },
    headers,
  });
  expect(moved.status()).toBe(200);
  return (await moved.json()) as Created;
}

async function statusOf(page: Page, id: string): Promise<string> {
  const { request } = await api(page);
  const response = await request.get(`/api/applications/${id}`);
  return ((await response.json()) as { status: string }).status;
}

const column = (page: Page, status: string) => page.getByRole("grid", { name: status, exact: true });
/** A card in a column, found by its title link. */
const card = (list: Locator, title: string) => list.getByRole("link", { name: title, exact: true });

async function openBoard(page: Page, companyId: string) {
  await page.goto(`/applications?view=board&company=${companyId}`);
  await expect(column(page, "Discovered")).toBeVisible();
}

async function openMoves(page: Page, title: string, label = `Move ${title} to…`): Promise<Locator> {
  await page.getByRole("button", { name: label }).click();
  return page.getByRole("menu", { name: label });
}

test("switch to the board with the filters kept, and move a card with the keyboard menu", async ({
  page,
}) => {
  const companyId = await createCompany(page);
  const title = uniqueName("Keyboard Engineer");
  const application = await createApplication(page, companyId, title);
  await createApplication(page, companyId, uniqueName("Interview Engineer"), "INTERVIEWING");

  await page.goto(`/applications?company=${companyId}`);
  await expect(page.getByText("2 applications match")).toBeVisible();
  await page.getByRole("radiogroup", { name: "View" }).getByText("Board", { exact: true }).click();
  const params = () => new URL(page.url()).searchParams;
  await expect.poll(() => params().get("view")).toBe("board");
  expect(params().get("company")).toBe(companyId);
  await expect(card(column(page, "Discovered"), title)).toBeVisible();
  await expectNoA11yViolations(page);
  await snapshot(page, "board");

  const menuButton = page.getByRole("button", { name: `Move ${title} to…` });
  await menuButton.focus();
  await page.keyboard.press("Enter");
  const menu = page.getByRole("menu", { name: `Move ${title} to…` });
  await expect(menu.getByRole("menuitem", { name: "Applied", exact: true })).toBeVisible();
  await expectNoA11yViolations(page);
  await snapshot(page, "board-move-menu");
  await page.keyboard.type("Applied");
  await page.keyboard.press("Enter");

  await expect(card(column(page, "Applied"), title)).toBeVisible();
  await expect(card(column(page, "Discovered"), title)).toHaveCount(0);
  await expect(page.getByRole("status").filter({ hasText: `${title} moved to Applied.` })).toBeAttached();
  await expect.poll(() => statusOf(page, application.id)).toBe("APPLIED");
});

test("drag a card to another column with the mouse", async ({ page }) => {
  test.skip(test.info().project.name === "phone", "Mouse drag; phones move cards with the menu.");
  const companyId = await createCompany(page);
  const title = uniqueName("Drag Engineer");
  const application = await createApplication(page, companyId, title, "APPLIED");
  await openBoard(page, companyId);

  // A mouse drags the whole card (the handle is the keyboard's way in); grab it by its padding.
  const source = column(page, "Applied").getByRole("row").filter({ hasText: title });
  // The board scrolls sideways: bring the target column (and, next to it, the card) into view first.
  await column(page, "Interviewing").scrollIntoViewIfNeeded();
  const from = await source.boundingBox();
  const to = await column(page, "Interviewing").boundingBox();
  if (!from || !to) throw new Error("card or column not laid out");
  // Several small moves, as a hand would: the drop target needs dragenter and dragover before the drop.
  await page.mouse.move(from.x + 4, from.y + 4);
  await page.mouse.down();
  await page.mouse.move(from.x + 40, from.y + 20, { steps: 5 });
  await page.mouse.move(to.x + to.width / 2, to.y + to.height / 2, { steps: 20 });
  await expect(column(page, "Interviewing")).toHaveAttribute("data-drop-target", "true");
  await page.mouse.up();
  await expect(card(column(page, "Interviewing"), title)).toBeVisible();
  await expect.poll(() => statusOf(page, application.id)).toBe("INTERVIEWING");
  await page.reload();
  await expect(card(column(page, "Interviewing"), title)).toBeVisible();
});

test("drag a card with the keyboard: only allowed columns take it", async ({ page }) => {
  const companyId = await createCompany(page);
  const title = uniqueName("Keydrag Engineer");
  const application = await createApplication(page, companyId, title);
  await openBoard(page, companyId);

  await page.getByRole("button", { name: `Drag ${title}` }).focus();
  await page.keyboard.press("Enter");
  // The first column that accepts a Discovered card is the next one; Enter drops it there.
  const target = page.locator(":focus");
  await expect(target).toHaveAttribute("aria-roledescription", "drop indicator");
  await expect(column(page, "Shortlisted")).toHaveAttribute("data-drop-target", "true");
  await snapshot(page, "board-keyboard-drag");
  await page.keyboard.press("Enter");
  await expect(card(column(page, "Shortlisted"), title)).toBeVisible();
  await expect.poll(() => statusOf(page, application.id)).toBe("SHORTLISTED");
});

test("an invalid move is not offered", async ({ page }) => {
  const companyId = await createCompany(page);
  const discovered = uniqueName("Fresh Engineer");
  const applied = uniqueName("Sent Engineer");
  await createApplication(page, companyId, discovered);
  await createApplication(page, companyId, applied, "APPLIED");
  await openBoard(page, companyId);

  let menu = await openMoves(page, discovered);
  await expect(menu.getByRole("menuitem", { name: "Declined", exact: true })).toBeVisible();
  for (const invalid of ["Discovered", "Accepted", "Rejected", "Withdrawn", "Ghosted"])
    await expect(menu.getByRole("menuitem", { name: invalid, exact: true })).toHaveCount(0);
  await page.keyboard.press("Escape");

  menu = await openMoves(page, applied);
  await expect(menu.getByRole("menuitem", { name: "Ghosted", exact: true })).toBeVisible();
  for (const invalid of ["Applied", "Accepted", "Declined"])
    await expect(menu.getByRole("menuitem", { name: invalid, exact: true })).toHaveCount(0);
  await page.keyboard.press("Escape");
});

test("decline from the board asks for a reason, then the card is in the ended columns", async ({ page }) => {
  const companyId = await createCompany(page);
  const title = uniqueName("Decline Engineer");
  const application = await createApplication(page, companyId, title, "OFFER");
  await openBoard(page, companyId);

  const menu = await openMoves(page, title);
  await menu.getByRole("menuitem", { name: "Declined", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "Move to Declined" });
  await dialog.getByRole("button", { name: "Change status" }).click();
  await expect(dialog.getByText("Choose a reason.")).toBeVisible();
  await dialog.getByRole("button", { name: /Reason$/ }).click();
  await page.getByRole("option", { name: "Salary", exact: true }).click();
  await expectNoA11yViolations(page);
  await snapshot(page, "board-decline-dialog");
  await dialog.getByRole("button", { name: "Change status" }).click();
  await expect(dialog).toBeHidden();

  await expect(card(column(page, "Offer"), title)).toHaveCount(0);
  await page.getByRole("button", { name: "Ended (1 application)" }).click();
  await expect(card(column(page, "Declined"), title)).toBeVisible();
  await expect.poll(() => statusOf(page, application.id)).toBe("DECLINED");
  await expectNoA11yViolations(page);
  await snapshot(page, "board-ended");
});

test("a move refused by the server puts the card back and says why", async ({ page }) => {
  const companyId = await createCompany(page);
  const title = uniqueName("Stale Engineer");
  const application = await createApplication(page, companyId, title);
  await openBoard(page, companyId);

  // Meanwhile another tab (here: the API) moves it; the board still holds the old version.
  const { request, headers } = await api(page);
  const put = await request.put(`/api/applications/${application.id}/status`, {
    data: { status: "PREPARING", basedOnVersion: application.version },
    headers,
  });
  expect(put.status()).toBe(200);

  const menu = await openMoves(page, title);
  await menu.getByRole("menuitem", { name: "Applied", exact: true }).click();
  const alert = page.getByRole("alert").filter({ hasText: "Not moved" });
  await expect(alert).toContainText(`${title} was changed elsewhere meanwhile`);
  await expect(card(column(page, "Preparing"), title)).toBeVisible();
  await expect(card(column(page, "Applied"), title)).toHaveCount(0);
  await expectNoA11yViolations(page);
  await snapshot(page, "board-conflict");
});

test.describe("in German", () => {
  test.use({ locale: "de-DE" });

  test("move a card on the board in German", async ({ page }) => {
    const companyId = await createCompany(page);
    const title = uniqueName("Plattform-Entwicklerin");
    await createApplication(page, companyId, title);
    await page.goto(`/applications?view=board&company=${companyId}`);
    await expect(column(page, "Entdeckt")).toBeVisible();
    await expect(page.getByRole("radiogroup", { name: "Ansicht" })).toBeVisible();

    const menu = await openMoves(page, title, `${title} verschieben nach …`);
    await menu.getByRole("menuitem", { name: "Beworben", exact: true }).click();
    await expect(card(column(page, "Beworben"), title)).toBeVisible();
    await expect(page.getByRole("button", { name: /^Beendet/ })).toBeVisible();
    await expectNoA11yViolations(page);
    await snapshot(page, "board-de");
  });
});
