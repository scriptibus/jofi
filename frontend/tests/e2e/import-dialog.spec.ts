// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { expect, type Page, test } from "@playwright/test";
import { choose, expectNoA11yViolations, onStack, snapshot, uniqueName } from "./helpers.ts";

// The import dialog and the share target (#98) against the full stack: a pasted text goes through the real worker
// and the fake AI (fixtures/extraction), a link to a site Jofi never scrapes is refused before any fetch, an
// address the SSRF guard blocks answers "unreachable", and a link that an application already has answers at once
// with that application (the seeded source of tests/stack/seed/db/0003-application-sources.sql).
//
// What this suite cannot do: import a fresh link successfully. Posting fetches have no allowlist (adapters/net
// stays untouched) and the stack has no internet, so a WireMock-served posting is unreachable; that path is
// covered by PostingUrlImportFlowTest (backend) and the dialog's component tests.
test.skip(!onStack, "Needs the full stack: run `pnpm e2e`.");

const SEEDED_LINK = "https://jobs.seeded.example/engineer?ref=e2e&team=platform";
const SEEDED_APPLICATION = "00000000-0000-4000-8000-0000000e2e11";
const LINKEDIN_LINK = "https://www.linkedin.com/jobs/view/4000000001";
// Resolves inside the stack's internal network: refused by the guard, nothing is sent.
const INTERNAL_LINK = "http://wiremock:8080/placeholder-source/jobs";

interface Texts {
  open: string;
  title: string;
  source: string;
  sourceUrl: string;
  sourceText: string;
  urlLabel: string;
  textLabel: string;
  submit: string;
  done: string;
  already: string;
  openApplication: string;
  close: string;
  pasteInstead: string;
  notAllowed: RegExp;
  unreachable: RegExp;
  shareHeading: string;
  review: string;
}

const english: Texts = {
  open: "Import posting",
  title: "Import a job posting",
  source: "Import from",
  sourceUrl: "A link",
  sourceText: "Pasted text",
  urlLabel: "Link to the posting",
  textLabel: "Text of the posting",
  submit: "Import",
  done: "Posting imported",
  already: "Already imported",
  openApplication: "Open the application",
  close: "Close",
  pasteInstead: "Paste the text instead",
  notAllowed: /does not open links to LinkedIn, StepStone or Indeed/,
  unreachable: /could not reach that page/,
  shareHeading: "Shared with Jofi",
  review: "Review and import",
};

const german: Texts = {
  open: "Anzeige importieren",
  title: "Stellenanzeige importieren",
  source: "Importieren aus",
  sourceUrl: "Einem Link",
  sourceText: "Eingefügtem Text",
  urlLabel: "Link zur Anzeige",
  textLabel: "Text der Anzeige",
  submit: "Importieren",
  done: "Anzeige importiert",
  already: "Bereits importiert",
  openApplication: "Bewerbung öffnen",
  close: "Schließen",
  pasteInstead: "Stattdessen Text einfügen",
  notAllowed: /öffnet keine Links zu LinkedIn, StepStone oder Indeed/,
  unreachable: /nicht erreichen/,
  shareHeading: "Mit Jofi geteilt",
  review: "Prüfen und importieren",
};

async function openDialog(page: Page, text: Texts) {
  await page.goto("/applications");
  await page.getByRole("button", { name: text.open }).click();
  const dialog = page.getByRole("dialog", { name: text.title });
  await expect(dialog).toBeVisible();
  return dialog;
}

async function importsText(page: Page, text: Texts) {
  const dialog = await openDialog(page, text);
  await expect(dialog.getByLabel(text.urlLabel)).toBeFocused();
  await expectNoA11yViolations(page);
  await snapshot(page, "import-form");

  await choose(page, text.source, text.sourceText);
  await dialog
    .getByLabel(text.textLabel)
    .fill(`Senior Kotlin Developer at Posting Fixture GmbH, Berlin. ${uniqueName("ref")}`);
  await snapshot(page, "import-text");
  await dialog.getByRole("button", { name: text.submit, exact: true }).click();

  await expect(dialog.getByText(text.done)).toBeVisible({ timeout: 60_000 });
  await expectNoA11yViolations(page);
  await snapshot(page, "import-done");
  await dialog.getByRole("link", { name: text.openApplication }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Senior Kotlin Developer" })).toBeVisible();
}

async function refusesLinks(page: Page, text: Texts) {
  const dialog = await openDialog(page, text);
  await dialog.getByLabel(text.urlLabel).fill(LINKEDIN_LINK);
  await dialog.getByRole("button", { name: text.submit, exact: true }).click();

  await expect(dialog.getByRole("alert")).toContainText(text.notAllowed);
  await expect(dialog.getByLabel(text.urlLabel)).toHaveAttribute("aria-invalid", "true");
  await expectNoA11yViolations(page);
  await snapshot(page, "import-refused");

  await dialog.getByRole("button", { name: text.pasteInstead }).click();
  await expect(dialog.getByLabel(text.textLabel)).toBeFocused();
  await expect(dialog.getByRole("alert")).toHaveCount(0);

  await choose(page, text.source, text.sourceUrl);
  await dialog.getByLabel(text.urlLabel).fill(INTERNAL_LINK);
  await dialog.getByRole("button", { name: text.submit, exact: true }).click();
  await expect(dialog.getByRole("alert")).toContainText(text.unreachable);
  await expectNoA11yViolations(page);
}

async function recognisesKnownLinks(page: Page, text: Texts) {
  const dialog = await openDialog(page, text);
  await dialog.getByLabel(text.urlLabel).fill(SEEDED_LINK);
  await dialog.getByRole("button", { name: text.submit, exact: true }).click();

  await expect(dialog.getByText(text.already)).toBeVisible();
  await expectNoA11yViolations(page);
  await snapshot(page, "import-already");
  await dialog.getByRole("link", { name: text.openApplication }).click();
  await expect(page).toHaveURL(new RegExp(`/applications/${SEEDED_APPLICATION}`));
}

async function receivesShares(page: Page, text: Texts) {
  const shared = new URLSearchParams({ title: "Platform Engineer", text: "Look at this", url: SEEDED_LINK });
  await page.goto(`/share?${shared.toString()}`);

  // The dialog is open with the shared link filled in; nothing was sent, and the address bar is clean.
  const dialog = page.getByRole("dialog", { name: text.title });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByLabel(text.urlLabel)).toHaveValue(SEEDED_LINK);
  await expect(page).toHaveURL(/\/share$/);
  await expectNoA11yViolations(page);
  await snapshot(page, "share-dialog");

  await dialog.getByRole("button", { name: text.submit, exact: true }).click();
  await expect(dialog.getByText(text.already)).toBeVisible();

  // Closed, the page still shows what was shared and can reopen the dialog.
  await dialog.getByRole("button", { name: text.close }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByRole("heading", { level: 1, name: text.shareHeading })).toBeVisible();
  await expect(page.getByText("Platform Engineer")).toBeVisible();
  await page.getByRole("button", { name: text.review }).click();
  await expect(page.getByRole("dialog", { name: text.title })).toBeVisible();
}

async function importsSharedText(page: Page, text: Texts) {
  const posting = `Senior Kotlin Developer at Posting Fixture GmbH. ${uniqueName("shared")}`;
  await page.goto(`/share?${new URLSearchParams({ text: posting }).toString()}`);

  const dialog = page.getByRole("dialog", { name: text.title });
  await expect(dialog.getByLabel(text.textLabel)).toHaveValue(posting);
  await dialog.getByRole("button", { name: text.submit, exact: true }).click();
  await expect(dialog.getByText(text.done)).toBeVisible({ timeout: 60_000 });
}

test.describe("import dialog and share target in English", () => {
  test("a pasted text becomes an application", async ({ page }) => {
    await importsText(page, english);
  });

  test("a link Jofi never opens, or cannot reach, is explained and the text is offered instead", async ({
    page,
  }) => {
    await refusesLinks(page, english);
  });

  test("a link that an application already has answers with that application", async ({ page }) => {
    await recognisesKnownLinks(page, english);
  });

  test("what another app shares fills the dialog; the user confirms before anything is imported", async ({
    page,
  }) => {
    await receivesShares(page, english);
  });

  test("a shared text imports as text", async ({ page }) => {
    await importsSharedText(page, english);
  });
});

test.describe("import dialog and share target in German", () => {
  test.use({ locale: "de-DE" });

  test("a pasted text becomes an application", async ({ page }) => {
    await importsText(page, german);
  });

  test("a link Jofi never opens, or cannot reach, is explained and the text is offered instead", async ({
    page,
  }) => {
    await refusesLinks(page, german);
  });

  test("a link that an application already has answers with that application", async ({ page }) => {
    await recognisesKnownLinks(page, german);
  });

  test("what another app shares fills the dialog; the user confirms before anything is imported", async ({
    page,
  }) => {
    await receivesShares(page, german);
  });
});
