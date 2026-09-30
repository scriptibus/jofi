// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { expect, type Page, test } from "@playwright/test";
import { api, expectNoA11yViolations, onStack, snapshot, uniqueName } from "./helpers.ts";

// The contacts pages against the real backend (spec §5, §13, #109). Every browser project runs these in
// parallel on one stack, so each test works on companies and contacts of its own (unique names).
test.skip(!onStack, "Needs the full stack: run `pnpm e2e`.");

interface Created {
  id: string;
  name: string;
  version: number;
}

async function createCompany(page: Page, name: string): Promise<Created> {
  const { request, headers } = await api(page);
  const response = await request.post("/api/companies", { data: { name }, headers });
  expect(response.status()).toBe(201);
  return (await response.json()) as Created;
}

async function createContact(page: Page, data: Record<string, unknown>): Promise<Created> {
  const { request, headers } = await api(page);
  const response = await request.post("/api/contacts", { data, headers });
  expect(response.status()).toBe(201);
  return (await response.json()) as Created;
}

/** Picks an option in one of our Selects (a button named by its value and label, then a list box). */
async function pick(page: Page, label: string, option: string) {
  await page.getByRole("button", { name: new RegExp(`${label}$`) }).click();
  await page.getByRole("option", { name: option, exact: true }).click();
  await expect(page.getByRole("button", { name: new RegExp(`${label}$`) })).toContainText(option);
}

function channel(page: Page, position: number) {
  return page.getByRole("group", { name: `Channel ${position}` });
}

test("create a contact with several channels, find it with a typo, filter by company", async ({ page }) => {
  await page.goto("/companies");
  const company = await createCompany(page, uniqueName("Tyrell Corporation"));
  const name = uniqueName("Rachael Rosen");

  await page.getByRole("link", { name: "All contacts" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Contacts" })).toBeVisible();
  await page.getByRole("link", { name: "New contact" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "New contact" })).toBeVisible();
  await page.getByLabel("Name (required)").fill(name);
  await page.getByLabel("Role").fill("Head of Talent");
  await pick(page, "Company", company.name);

  const add = page.getByRole("button", { name: "Add a way to reach them" });
  await add.click();
  await channel(page, 1).getByLabel("Email address").fill("rachael@tyrell");
  await add.click();
  await channel(page, 2).getByText("Phone", { exact: true }).click();
  await channel(page, 2).getByLabel("Phone number").fill("+٤٩ ٣٠ ١٢٣٤٥");
  await channel(page, 2).getByLabel("Label").fill("mobile");
  await add.click();
  await channel(page, 3).getByText("Web", { exact: true }).click();
  await channel(page, 3).getByLabel("Web address").fill("tyrell.example/rachael");
  await page.getByRole("button", { name: "Move channel 2 up" }).click();
  await expect(channel(page, 1).getByLabel("Phone number")).toHaveValue("+٤٩ ٣٠ ١٢٣٤٥");
  await page
    .getByLabel("Relationship notes")
    .fill("Met at the **Voight-Kampff** demo.\n\n<script>window.__xss = true</script>");
  await page.getByRole("button", { name: "Create contact" }).click();
  // Checked before anything is sent.
  await expect(
    channel(page, 3).getByText("Enter a full web address starting with https:// or http://."),
  ).toBeVisible();
  await expectNoA11yViolations(page);
  await snapshot(page, "contact-new-invalid");

  await channel(page, 3).getByLabel("Web address").fill("https://tyrell.example/rachael?ref=a&b=c");
  await page.getByRole("button", { name: "Create contact" }).click();
  await expect(page.getByRole("heading", { level: 1, name })).toBeVisible();
  const channels = page.getByRole("region", { name: "Ways to reach them" });
  await expect(channels.getByRole("link", { name: "+٤٩ ٣٠ ١٢٣٤٥" })).toHaveAttribute(
    "href",
    "tel:+493012345",
  );
  await expect(channels.getByRole("link", { name: "rachael@tyrell" })).toHaveAttribute(
    "href",
    "mailto:rachael@tyrell",
  );
  const web = channels.getByRole("link", { name: "https://tyrell.example/rachael?ref=a&b=c" });
  await expect(web).toHaveAttribute("rel", "noopener noreferrer nofollow");
  await expect(web).toHaveAttribute("target", "_blank");
  await expect(channels.getByText("Phone · mobile")).toBeVisible();
  await expect(
    page.getByRole("region", { name: "Details" }).getByRole("link", { name: company.name }),
  ).toBeVisible();
  await expect(page.getByRole("region", { name: "Relationship notes" }).locator("strong")).toHaveText(
    "Voight-Kampff",
  );
  expect(await page.evaluate(() => "__xss" in window)).toBe(false);
  await expectNoA11yViolations(page);
  await snapshot(page, "contact-detail");

  await page.getByRole("link", { name: "All contacts" }).click();
  // Fuzzy: a typo still finds it, and the name never reaches the URL (third-party data).
  await page.getByRole("searchbox", { name: "Search contacts" }).fill(name.replace("Rachael", "Rachel"));
  await expect(page.getByRole("link", { name })).toBeVisible();
  expect(page.url()).not.toContain("Rach");
  await page.getByRole("searchbox", { name: "Search contacts" }).fill("");
  await pick(page, "Company", company.name);
  await expect(page).toHaveURL(new RegExp(`company=${company.id}`));
  await expect(page.getByRole("status").filter({ hasText: "1 contact matches" })).toBeVisible();
  await expect(page.getByRole("link", { name })).toBeVisible();
  await expectNoA11yViolations(page);
  await snapshot(page, "contacts-filtered");
});

test("a save based on an old version is refused; loading the latest version lets the user edit again", async ({
  page,
}) => {
  await page.goto("/contacts");
  const contact = await createContact(page, { name: uniqueName("Roy Batty"), role: "Recruiter" });
  await page.goto(`/contacts/${contact.id}/edit`);
  await expect(page.getByLabel("Name (required)")).toHaveValue(contact.name);

  // Meanwhile another tab (here: the API) renames the contact.
  const renamed = `${contact.name} Nexus`;
  const { request, headers } = await api(page);
  const put = await request.put(`/api/contacts/${contact.id}`, {
    data: { details: { name: renamed, role: "Recruiter" }, basedOnVersion: contact.version },
    headers,
  });
  expect(put.status()).toBe(200);

  await page.getByLabel("Role").fill("Combat model");
  await page.getByRole("button", { name: "Save changes" }).click();
  const conflict = page.getByRole("alert").filter({ hasText: "Changed meanwhile" });
  await expect(conflict).toBeVisible();
  await expectNoA11yViolations(page);
  await snapshot(page, "contact-edit-conflict");

  await conflict.getByRole("button", { name: "Load latest version" }).click();
  await expect(page.getByLabel("Name (required)")).toHaveValue(renamed);
  await page.getByLabel("Role").fill("Combat model");
  await page.getByRole("button", { name: "Save changes" }).click();
  await expect(page.getByRole("heading", { level: 1, name: renamed })).toBeVisible();
  await expect(page.getByRole("region", { name: "Details" }).getByText("Combat model")).toBeVisible();
});

test("delete asks first, says all personal data goes, and removes the contact from its company", async ({
  page,
}) => {
  await page.goto("/companies");
  const company = await createCompany(page, uniqueName("Wallace Corp"));
  const contact = await createContact(page, {
    name: uniqueName("Luv"),
    companyId: company.id,
    channels: [{ kind: "EMAIL", value: "luv@wallace.example" }],
  });

  await page.goto(`/companies/${company.id}`);
  const contacts = page.getByRole("region", { name: "Contacts" });
  await contacts.getByRole("link", { name: contact.name }).click();
  await expect(page.getByRole("heading", { level: 1, name: contact.name })).toBeVisible();
  await page.getByRole("button", { name: "Delete…" }).click();
  const dialog = page.getByRole("alertdialog", { name: "Delete this contact?" });
  await expect(dialog).toContainText(
    `${contact.name} will be deleted with all personal data stored about them`,
  );
  await expectNoA11yViolations(page);
  await snapshot(page, "contact-delete-confirm");

  await dialog.getByRole("button", { name: "Cancel" }).click();
  await expect(dialog).toBeHidden();
  const { request } = await api(page);
  expect((await request.get(`/api/contacts/${contact.id}`)).status()).toBe(200);

  await page.getByRole("button", { name: "Delete…" }).click();
  await dialog.getByRole("button", { name: "Delete contact" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Contacts" })).toBeVisible();
  expect((await request.get(`/api/contacts/${contact.id}`)).status()).toBe(404);

  await page.goto(`/companies/${company.id}`);
  await expect(contacts.getByText("No contacts at this company yet.")).toBeVisible();
  await expect(contacts.getByRole("link", { name: contact.name })).toHaveCount(0);
});

test.describe("in German", () => {
  test.use({ locale: "de-DE" });

  test("create a contact from its company's page and delete it in German", async ({ page }) => {
    await page.goto("/companies");
    const company = await createCompany(page, uniqueName("Stelline Labor"));
    const name = uniqueName("Ana Stelline");
    await page.goto(`/companies/${company.id}`);
    await page.getByRole("region", { name: "Kontakte" }).getByRole("link", { name: "Neuer Kontakt" }).click();

    await expect(page.getByRole("heading", { level: 1, name: "Neuer Kontakt" })).toBeVisible();
    await expect(page.getByRole("button", { name: /Firma$/ })).toContainText(company.name);
    await page.getByLabel("Name (Pflichtfeld)").fill(name);
    await page.getByRole("button", { name: "Kontaktweg hinzufügen" }).click();
    const first = page.getByRole("group", { name: "Kontaktweg 1" });
    await first.getByText("Sonstiges", { exact: true }).click();
    await first.getByLabel("Angabe").fill("@ana auf Signal");
    await page.getByRole("button", { name: "Kontakt anlegen" }).click();
    await expect(page.getByRole("heading", { level: 1, name })).toBeVisible();
    await expect(
      page.getByRole("region", { name: "Kontaktwege" }).getByText("@ana auf Signal"),
    ).toBeVisible();
    await expectNoA11yViolations(page);
    await snapshot(page, "contact-detail-de");

    await page.getByRole("button", { name: "Löschen …" }).click();
    const dialog = page.getByRole("alertdialog", { name: "Diesen Kontakt löschen?" });
    await expect(dialog).toContainText("mit allen gespeicherten persönlichen Daten gelöscht");
    await expectNoA11yViolations(page);
    await snapshot(page, "contact-delete-confirm-de");
    await dialog.getByRole("button", { name: "Kontakt löschen" }).click();
    await expect(page.getByRole("heading", { level: 1, name: "Kontakte" })).toBeVisible();
    await expect(page.getByRole("link", { name })).toHaveCount(0);
  });
});
