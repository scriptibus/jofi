// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { createMemoryHistory } from "@tanstack/react-router";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { setupServer } from "msw/node";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import type { ApplicationResponse, ContactResponse } from "../../api/generated/jofi";
import { anApplication, fakeApplicationBackend } from "../../test/fakeApplicationBackend";
import { fakeAuthBackend } from "../../test/fakeAuthBackend";
import { aCompany, aContact, fakeCompanyBackend } from "../../test/fakeCompanyBackend";
import { App, createApp } from "../App";

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const acme = aCompany({ name: "ACME GmbH" });
const globex = aCompany({ name: "Globex" });
const ada = aContact(acme.id, {
  name: "Ada Lovelace",
  role: "Recruiter",
  channels: [
    { kind: "EMAIL", value: "ada@acme.example", label: null },
    { kind: "PHONE", value: "+49 30 12345", label: "mobile" },
    { kind: "WEB", value: "javascript:alert(1)", label: null },
  ],
});
const alan = aContact(acme.id, { name: "Alan Turing", role: "Hiring manager" });
const grace = aContact(globex.id, { name: "Grace Hopper", role: "CTO" });
const linus = aContact("", { name: "Linus", role: null, companyId: null });

function start(path: string, applications: ApplicationResponse[], contacts: ContactResponse[]) {
  const auth = fakeAuthBackend({ authenticated: true });
  const companies = fakeCompanyBackend({ companies: [acme, globex], contacts: [...contacts] });
  const backend = fakeApplicationBackend({
    applications,
    knownContactIds: () => companies.state.contacts.map((contact) => contact.id),
  });
  server.use(...backend.handlers, ...companies.handlers, ...auth.handlers);
  const app = createApp(createMemoryHistory({ initialEntries: [path] }));
  render(<App app={app} />);
  return { state: backend.state, companies: companies.state, router: app.router, user: userEvent.setup() };
}

const linked = () => screen.getByRole("region", { name: "Linked contacts" });

/** The application's Contacts tab, once it is there. */
async function openTab(application: ApplicationResponse, contacts: ContactResponse[]) {
  const started = start(`/applications/${application.id}?tab=contacts`, [application], contacts);
  await screen.findByRole("region", { name: "Linked contacts" });
  return started;
}

describe("Application contacts tab", () => {
  it("lists the linked contacts with role, company and safe channel links", async () => {
    const application = anApplication(acme.id, { contactIds: [ada.id] });
    await openTab(application, [ada, alan]);

    expect(await screen.findByRole("tab", { name: "Contacts" })).toHaveAttribute("aria-selected", "true");
    const card = (await within(linked()).findByRole("link", { name: "Ada Lovelace" })).closest("article");
    if (!card) throw new Error("no card");
    expect(within(card).getByRole("link", { name: "Ada Lovelace" })).toHaveAttribute(
      "href",
      `/contacts/${ada.id}`,
    );
    expect(await within(card).findByText("Recruiter · ACME GmbH")).toBeVisible();
    expect(within(card).getByRole("link", { name: "ada@acme.example" })).toHaveAttribute(
      "href",
      "mailto:ada@acme.example",
    );
    expect(within(card).getByRole("link", { name: "+49 30 12345" })).toHaveAttribute(
      "href",
      "tel:+493012345",
    );
    expect(within(card).queryByRole("link", { name: "javascript:alert(1)" })).toBeNull();
    expect(within(card).getByText("javascript:alert(1)")).toBeVisible();
    expect(within(linked()).queryByText("Alan Turing")).toBeNull();
    expect(within(linked()).getByText(/only removes the link/)).toBeVisible();
  });

  it("offers the company's contacts first, then all others, without the linked ones, and searches by name", async () => {
    const application = anApplication(acme.id, { contactIds: [ada.id] });
    const { user } = await openTab(application, [ada, alan, grace, linus]);
    await user.click(await screen.findByRole("button", { name: "Link a contact" }));

    const dialog = await screen.findByRole("dialog", { name: "Link a contact" });
    const atCompany = await within(dialog).findByRole("region", { name: "At ACME GmbH" });
    const others = within(dialog).getByRole("region", { name: "Other contacts" });
    expect(
      within(atCompany)
        .getAllByRole("listitem")
        .map((item) => item.textContent),
    ).toEqual([expect.stringContaining("Alan Turing")]);
    expect(
      within(others)
        .getAllByRole("listitem")
        .map((item) => item.textContent),
    ).toEqual([expect.stringContaining("Grace Hopper"), expect.stringContaining("Linus")]);
    expect(await within(others).findByText("CTO · Globex")).toBeVisible();
    expect(within(dialog).queryByText("Ada Lovelace")).toBeNull();

    await user.type(within(dialog).getByRole("searchbox", { name: "Search contacts" }), "grace");
    await waitFor(() => expect(within(dialog).queryByText("Linus")).toBeNull());
    expect(within(dialog).getByRole("button", { name: "Link Grace Hopper" })).toBeVisible();
    expect(within(dialog).queryByRole("region", { name: "At ACME GmbH" })).toBeNull();

    await user.clear(within(dialog).getByRole("searchbox", { name: "Search contacts" }));
    await user.type(within(dialog).getByRole("searchbox", { name: "Search contacts" }), "nobody");
    expect(await within(dialog).findByText(/No contacts match/)).toBeVisible();
    const create = within(dialog).getByRole("link", { name: "Create a new contact" });
    expect(create).toHaveAttribute("href", `/contacts/new?company=${acme.id}&application=${application.id}`);
    await user.click(within(dialog).getByRole("button", { name: "Close" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  });

  it("links a picked contact with the whole set and the version it shows", async () => {
    const application = anApplication(acme.id, { contactIds: [ada.id], version: 3 });
    const { user, state, router } = await openTab(application, [ada, alan, grace]);
    await user.click(await screen.findByRole("button", { name: "Link a contact" }));
    const dialog = await screen.findByRole("dialog");
    await user.click(await within(dialog).findByRole("button", { name: "Link Grace Hopper" }));

    expect(await within(linked()).findByRole("link", { name: "Grace Hopper" })).toBeVisible();
    expect(within(linked()).getByRole("status")).toHaveTextContent("Grace Hopper is now linked.");
    expect(state.contactLinks).toEqual([{ contactIds: [ada.id, grace.id], basedOnVersion: 3 }]);
    expect(screen.queryByRole("dialog")).toBeNull();
    // Only ids in the URL: a contact's name is third-party data.
    expect(router.state.location.href).not.toContain("Grace");
  });

  it("unlinks without asking and says the contact itself is kept", async () => {
    const application = anApplication(acme.id, { contactIds: [ada.id, alan.id], version: 1 });
    const { user, state, companies } = await openTab(application, [ada, alan]);
    await user.click(await within(linked()).findByRole("button", { name: "Unlink Alan Turing" }));

    expect(
      await within(linked()).findByText("Alan Turing is no longer linked. The contact itself was kept."),
    ).toBeVisible();
    expect(within(linked()).queryByRole("link", { name: "Alan Turing" })).toBeNull();
    expect(screen.queryByRole("alertdialog")).toBeNull();
    expect(state.contactLinks).toEqual([{ contactIds: [ada.id], basedOnVersion: 1 }]);
    expect(companies.contacts.map((contact) => contact.name)).toContain("Alan Turing");
  });

  it("says when the application changed meanwhile, and works again after loading the latest version", async () => {
    const application = anApplication(acme.id, { contactIds: [ada.id, alan.id], version: 1 });
    const { user, state } = await openTab(application, [ada, alan]);
    await within(linked()).findByRole("button", { name: "Unlink Alan Turing" });
    // Another tab links nobody else but saves the application meanwhile.
    state.applications = [{ ...application, contactIds: [ada.id, alan.id], version: 2 }];

    await user.click(within(linked()).getByRole("button", { name: "Unlink Alan Turing" }));
    const conflict = await screen.findByRole("alert");
    expect(conflict).toHaveTextContent("Changed meanwhile");
    expect(state.contactLinks).toEqual([]);

    await user.click(within(conflict).getByRole("button", { name: "Load latest version" }));
    await waitFor(() => expect(screen.queryByRole("alert")).toBeNull());
    await user.click(within(linked()).getByRole("button", { name: "Unlink Alan Turing" }));
    await waitFor(() => expect(state.contactLinks).toEqual([{ contactIds: [ada.id], basedOnVersion: 2 }]));
  });

  it("explains a refused link when the contact no longer exists", async () => {
    const application = anApplication(acme.id);
    const { user, companies } = await openTab(application, [alan]);
    await user.click(await screen.findByRole("button", { name: "Link a contact" }));
    const pick = await within(await screen.findByRole("dialog")).findByRole("button", {
      name: "Link Alan Turing",
    });
    companies.contacts = [];

    await user.click(pick);
    expect(await screen.findByRole("alert")).toHaveTextContent("One of these contacts no longer exists");
    expect(within(linked()).getByText("No contacts linked yet.")).toBeVisible();
  });

  it("creates a new contact for the application's company, links it and comes back", async () => {
    const application = anApplication(acme.id, { contactIds: [ada.id], version: 5 });
    const { user, state } = await openTab(application, [ada]);
    await user.click(await within(linked()).findByRole("link", { name: "Create a new contact" }));

    expect(await screen.findByRole("heading", { level: 1, name: "New contact" })).toBeVisible();
    expect(screen.getByText(/linked to the application you came from/)).toBeVisible();
    await waitFor(() =>
      expect(screen.getByRole("button", { name: /Company$/ })).toHaveTextContent("ACME GmbH"),
    );
    await user.type(screen.getByLabelText("Name (required)"), "Hedy Lamarr");
    await user.click(screen.getByRole("button", { name: "Create contact" }));

    expect(await within(linked()).findByRole("link", { name: "Hedy Lamarr" })).toBeVisible();
    expect(screen.getByRole("tab", { name: "Contacts" })).toHaveAttribute("aria-selected", "true");
    expect(state.contactLinks).toHaveLength(1);
    expect(state.contactLinks[0]?.basedOnVersion).toBe(5);
    expect(state.contactLinks[0]?.contactIds[0]).toBe(ada.id);
  });

  it("returns to the tab without creating anything on cancel", async () => {
    const application = anApplication(acme.id);
    const { user, state, companies } = start(
      `/contacts/new?company=${acme.id}&application=${application.id}`,
      [application],
      [],
    );
    await user.click(await screen.findByRole("button", { name: "Cancel" }));
    expect(
      await within(await screen.findByRole("region", { name: "Linked contacts" })).findByText(
        "No contacts linked yet.",
      ),
    ).toBeVisible();
    expect(state.contactLinks).toEqual([]);
    expect(companies.contacts).toEqual([]);
  });

  it("disables linking at the limit of 50 contacts", async () => {
    const contacts = Array.from({ length: 50 }, (_, index) =>
      aContact(acme.id, { name: `Contact ${index}` }),
    );
    const application = anApplication(acme.id, { contactIds: contacts.map((contact) => contact.id) });
    await openTab(application, contacts);
    expect(await screen.findByRole("button", { name: "Link a contact" })).toBeDisabled();
    expect(screen.getByText(/at most 50 contacts/)).toBeVisible();
  });
});
