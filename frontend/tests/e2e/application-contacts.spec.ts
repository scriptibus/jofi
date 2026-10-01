// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { expect, type Page, test } from "@playwright/test";
import { api, expectNoA11yViolations, onStack, snapshot, uniqueName } from "./helpers.ts";

// The Contacts tab of the application detail page against the real backend (spec §5, §6.3, #104). Every
// browser project runs these in parallel on one stack, so each test works on its own company, application
// and contacts (unique names).
test.skip(!onStack, "Needs the full stack: run `pnpm e2e`.");

interface Created {
  id: string;
  name: string;
  title: string;
  version: number;
  contactIds: string[];
}

async function post(page: Page, path: string, data: Record<string, unknown>): Promise<Created> {
  const { request, headers } = await api(page);
  const response = await request.post(path, { data, headers });
  expect(response.status()).toBe(201);
  return (await response.json()) as Created;
}

/** A company of this test's own with an application and `contacts` (names) at it. */
async function arrange(page: Page, contacts: string[]) {
  await page.goto("/applications");
  const company = await post(page, "/api/companies", { name: uniqueName("Cyberdyne Systems") });
  const application = await post(page, "/api/applications", {
    title: uniqueName("Robotics Engineer"),
    companyId: company.id,
  });
  const created = [];
  for (const [index, name] of contacts.entries()) {
    const channels =
      index === 0
        ? [
            { kind: "EMAIL", value: "miles@cyberdyne.example" },
            { kind: "PHONE", value: "+1 555 0100", label: "office" },
          ]
        : [];
    created.push(
      await post(page, "/api/contacts", { name, companyId: company.id, role: "Recruiter", channels }),
    );
  }
  return { company, application, contacts: created };
}

async function readApplication(page: Page, id: string): Promise<Created> {
  const { request } = await api(page);
  const response = await request.get(`/api/applications/${id}`);
  expect(response.status()).toBe(200);
  return (await response.json()) as Created;
}

test("link two contacts, see them with their channels, unlink one; the contact page shows the application", async ({
  page,
}) => {
  const [miles, sarah] = [uniqueName("Miles Dyson"), uniqueName("Sarah Connor")];
  const { company, application, contacts } = await arrange(page, [miles, sarah]);
  await page.goto(`/applications/${application.id}`);
  await page.getByRole("tab", { name: "Contacts" }).click();
  const tab = page.getByRole("region", { name: "Linked contacts" });
  await expect(tab.getByText("No contacts linked yet.")).toBeVisible();
  await expect(tab.getByText(/only removes the link/)).toBeVisible();
  await expectNoA11yViolations(page);
  await snapshot(page, "application-contacts-empty");

  await tab.getByRole("button", { name: "Link a contact" }).click();
  const dialog = page.getByRole("dialog", { name: "Link a contact" });
  const atCompany = dialog.getByRole("region", { name: `At ${company.name}` });
  await expect(atCompany.getByRole("button", { name: `Link ${miles}` })).toBeVisible();
  await expect(atCompany.getByRole("button", { name: `Link ${sarah}` })).toBeVisible();
  await expectNoA11yViolations(page);
  await snapshot(page, "application-contacts-picker");
  await atCompany.getByRole("button", { name: `Link ${miles}` }).click();
  await expect(tab.getByRole("status").filter({ hasText: miles })).toHaveText(`${miles} is now linked.`);

  await tab.getByRole("button", { name: "Link a contact" }).click();
  // Found by name; the name never reaches the URL (third-party data).
  await dialog.getByRole("searchbox", { name: "Search contacts" }).fill(sarah);
  await expect(dialog.getByRole("button", { name: `Link ${miles}` })).toHaveCount(0);
  await dialog.getByRole("button", { name: `Link ${sarah}` }).click();
  await expect(tab.getByRole("link", { name: sarah })).toBeVisible();
  expect(page.url()).not.toContain("Sarah");

  const milesCard = tab.getByRole("article").filter({ has: page.getByRole("link", { name: miles }) });
  await expect(milesCard.getByText(`Recruiter · ${company.name}`)).toBeVisible();
  await expect(milesCard.getByRole("link", { name: "miles@cyberdyne.example" })).toHaveAttribute(
    "href",
    "mailto:miles@cyberdyne.example",
  );
  await expect(milesCard.getByRole("link", { name: "+1 555 0100" })).toHaveAttribute("href", "tel:+15550100");
  await expectNoA11yViolations(page);
  await snapshot(page, "application-contacts-linked");

  await tab.getByRole("button", { name: `Unlink ${sarah}` }).click();
  await expect(tab.getByRole("status").filter({ hasText: sarah })).toHaveText(
    `${sarah} is no longer linked. The contact itself was kept.`,
  );
  await expect(tab.getByRole("link", { name: sarah })).toHaveCount(0);
  const saved = await readApplication(page, application.id);
  expect(saved.contactIds).toHaveLength(1);

  await tab.getByRole("link", { name: miles }).click();
  await expect(page.getByRole("heading", { level: 1, name: miles })).toBeVisible();
  await expect(
    page.getByRole("region", { name: "Applications" }).getByRole("link", { name: application.title }),
  ).toBeVisible();

  // The unlinked contact is still there, just no longer linked.
  await page.goto(`/contacts/${contacts[1]?.id}`);
  await expect(page.getByRole("heading", { level: 1, name: sarah })).toBeVisible();
  await expect(
    page.getByRole("region", { name: "Applications" }).getByText("Not linked to any application yet."),
  ).toBeVisible();
});

test("create a new contact from the tab: the company is preselected and the contact comes back linked", async ({
  page,
}) => {
  const { company, application } = await arrange(page, []);
  const name = uniqueName("Kyle Reese");
  await page.goto(`/applications/${application.id}?tab=contacts`);
  const tab = page.getByRole("region", { name: "Linked contacts" });
  await tab.getByRole("link", { name: "Create a new contact" }).click();

  await expect(page.getByRole("heading", { level: 1, name: "New contact" })).toBeVisible();
  await expect(page.getByRole("button", { name: /Company$/ })).toContainText(company.name);
  await page.getByLabel("Name (required)").fill(name);
  await page.getByRole("button", { name: "Create contact" }).click();

  await expect(tab.getByRole("link", { name })).toBeVisible();
  await expect(page.getByRole("tab", { name: "Contacts" })).toHaveAttribute("aria-selected", "true");
  expect((await readApplication(page, application.id)).contactIds).toHaveLength(1);
});

test("a change based on an old version is refused; loading the latest version lets the user go on", async ({
  page,
}) => {
  const miles = uniqueName("Miles Dyson");
  const { application, contacts } = await arrange(page, [miles]);
  await page.goto(`/applications/${application.id}?tab=contacts`);
  const tab = page.getByRole("region", { name: "Linked contacts" });
  await expect(tab.getByText("No contacts linked yet.")).toBeVisible();

  // Meanwhile another tab (here: the API) links the contact.
  const { request, headers } = await api(page);
  const put = await request.put(`/api/applications/${application.id}/contacts`, {
    data: { contactIds: [contacts[0]?.id], basedOnVersion: application.version },
    headers,
  });
  expect(put.status()).toBe(200);

  await tab.getByRole("button", { name: "Link a contact" }).click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: `Link ${miles}` })
    .click();
  const conflict = tab.getByRole("alert").filter({ hasText: "Changed meanwhile" });
  await expect(conflict).toBeVisible();
  await expectNoA11yViolations(page);
  await snapshot(page, "application-contacts-conflict");

  await conflict.getByRole("button", { name: "Load latest version" }).click();
  await expect(tab.getByRole("link", { name: miles })).toBeVisible();
  await tab.getByRole("button", { name: `Unlink ${miles}` }).click();
  await expect(tab.getByText("No contacts linked yet.")).toBeVisible();
});

test.describe("in German", () => {
  test.use({ locale: "de-DE" });

  test("link and unlink a contact in German", async ({ page }) => {
    const miles = uniqueName("Miles Dyson");
    const { application } = await arrange(page, [miles]);
    await page.goto(`/applications/${application.id}`);
    await page.getByRole("tab", { name: "Kontakte" }).click();
    const tab = page.getByRole("region", { name: "Verknüpfte Kontakte" });
    await tab.getByRole("button", { name: "Kontakt verknüpfen" }).click();
    await page
      .getByRole("dialog", { name: "Kontakt verknüpfen" })
      .getByRole("button", { name: `${miles} verknüpfen` })
      .click();
    await expect(tab.getByRole("status").filter({ hasText: miles })).toHaveText(
      `${miles} ist jetzt verknüpft.`,
    );
    await expectNoA11yViolations(page);
    await snapshot(page, "application-contacts-de");

    await tab.getByRole("button", { name: `${miles} lösen` }).click();
    await expect(tab.getByRole("status").filter({ hasText: miles })).toHaveText(
      `${miles} ist nicht mehr verknüpft. Der Kontakt selbst bleibt erhalten.`,
    );
    await expect(tab.getByText("Noch keine Kontakte verknüpft.")).toBeVisible();
  });
});
