// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { createMemoryHistory } from "@tanstack/react-router";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { setupServer } from "msw/node";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { fakeAuthBackend } from "../../test/fakeAuthBackend";
import { aCompany, aContact, type FakeCompanyState, fakeCompanyBackend } from "../../test/fakeCompanyBackend";
import { App, createApp } from "../App";

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function start(path: string, data: Partial<FakeCompanyState> = {}) {
  const auth = fakeAuthBackend({ authenticated: true });
  const backend = fakeCompanyBackend(data);
  server.use(...backend.handlers, ...auth.handlers);
  const app = createApp(createMemoryHistory({ initialEntries: [path] }));
  render(<App app={app} />);
  return { state: backend.state, router: app.router, user: userEvent.setup() };
}

type User = ReturnType<typeof userEvent.setup>;

async function pick(user: User, label: RegExp, option: string) {
  await user.click(screen.getByRole("button", { name: label }));
  await user.click(await screen.findByRole("option", { name: option }));
}

/** Typing a whole value at once: key-by-key typing makes the long form tests slow. */
async function fill(user: User, input: HTMLElement, text: string) {
  await user.clear(input);
  await user.click(input);
  await user.paste(text);
}

function channelRow(position: number) {
  return screen.getByRole("group", { name: `Channel ${position}` });
}

const acme = aCompany({ name: "ACME GmbH" });
const globex = aCompany({ name: "Globex" });

describe("Contacts list", () => {
  const ada = aContact(acme.id, {
    name: "Ada Lovelace",
    role: "Recruiter",
    channels: [{ kind: "EMAIL", value: "ada@acme.example", label: null }],
  });
  const grace = aContact(globex.id, { name: "Grace Hopper", role: "CTO" });
  const linus = aContact("", { name: "Linus", role: null, companyId: null });

  it("lists contacts with role, company and number of channels", async () => {
    start("/contacts", { companies: [acme, globex], contacts: [ada, grace, linus] });
    expect(await screen.findByText("3 contacts")).toBeVisible();
    const card = screen.getByRole("link", { name: "Ada Lovelace" }).closest("article");
    if (card === null) throw new Error("no card");
    expect(await within(card).findByText("Recruiter · ACME GmbH")).toBeVisible();
    expect(within(card).getByText("1 way to reach them")).toBeVisible();
  });

  it("searches on the server as the user types, without putting the name in the URL", async () => {
    const { state, user, router } = start("/contacts", { companies: [acme, globex], contacts: [ada, grace] });
    await screen.findByText("2 contacts");
    await user.type(screen.getByRole("searchbox", { name: "Search contacts" }), "grace");
    expect(await screen.findByText("1 contact matches")).toBeVisible();
    expect(screen.getByRole("link", { name: "Grace Hopper" })).toBeVisible();
    expect(state.contactSearches.at(-1)?.get("search")).toBe("grace");
    expect(router.state.location.href).not.toMatch(/grace/i);
  });

  it("filters by company, with the company's id in the URL", async () => {
    const { state, user, router } = start("/contacts", { companies: [acme, globex], contacts: [ada, grace] });
    await screen.findByText("2 contacts");
    await pick(user, /Company/, "Globex");
    expect(await screen.findByText("1 contact matches")).toBeVisible();
    expect(screen.queryByRole("link", { name: "Ada Lovelace" })).toBeNull();
    expect(state.contactSearches.at(-1)?.get("companyId")).toBe(globex.id);
    expect(router.state.location.search).toEqual({ company: globex.id });

    await pick(user, /Company/, "All companies");
    expect(await screen.findByText("2 contacts")).toBeVisible();
  });

  it("shows an empty state with the way to add the first contact", async () => {
    start("/contacts");
    expect(await screen.findByText(/No contacts yet/)).toBeVisible();
    expect(screen.getByRole("link", { name: "New contact" })).toHaveAttribute("href", "/contacts/new");
  });
});

describe("Create and edit", () => {
  it("creates a contact with several channels in the chosen order", async () => {
    const { user, state } = start("/contacts/new", { companies: [acme, globex] });
    await fill(user, await screen.findByLabelText("Name (required)"), "  Ada Lovelace ");
    await fill(user, screen.getByLabelText("Role"), "Recruiter");
    await pick(user, /Company/, "ACME GmbH");

    const add = screen.getByRole("button", { name: "Add a way to reach them" });
    await user.click(add);
    await fill(user, within(channelRow(1)).getByLabelText("Email address"), "ada@");
    await user.click(add);
    await user.click(within(channelRow(2)).getByText("Phone"));
    await fill(user, within(channelRow(2)).getByLabelText("Phone number"), "+٤٩ 30 123");
    await fill(user, within(channelRow(2)).getByLabelText("Label"), "mobile");
    await user.click(add);
    await user.click(within(channelRow(3)).getByText("Web"));
    await fill(user, within(channelRow(3)).getByLabelText("Web address"), "https://ada.example");
    await user.click(screen.getByRole("button", { name: "Move channel 2 up" }));
    expect(within(channelRow(1)).getByLabelText("Phone number")).toHaveValue("+٤٩ 30 123");

    await user.click(screen.getByRole("button", { name: "Create contact" }));
    // Checked before anything is sent.
    expect(within(channelRow(2)).getByLabelText("Email address")).toHaveAccessibleDescription(
      /Enter an email address/,
    );
    expect(state.contacts).toEqual([]);

    await fill(user, within(channelRow(2)).getByLabelText("Email address"), "ada@acme.example");
    await user.click(screen.getByRole("button", { name: "Create contact" }));

    expect(await screen.findByRole("heading", { level: 1, name: "Ada Lovelace" })).toBeVisible();
    expect(state.contacts[0]).toMatchObject({
      name: "Ada Lovelace",
      companyId: acme.id,
      channels: [
        { kind: "PHONE", value: "+٤٩ 30 123", label: "mobile" },
        { kind: "EMAIL", value: "ada@acme.example", label: null },
        { kind: "WEB", value: "https://ada.example", label: null },
      ],
    });
    const channels = screen.getByRole("region", { name: "Ways to reach them" });
    const links = within(channels).getAllByRole("link");
    expect(links.map((link) => link.getAttribute("href"))).toEqual([
      "tel:+4930123",
      "mailto:ada@acme.example",
      "https://ada.example",
    ]);
    expect(within(channels).getByRole("link", { name: "+٤٩ 30 123" })).toBeVisible();
    expect(links[2]).toHaveAttribute("rel", "noopener noreferrer nofollow");
    expect(screen.getByRole("link", { name: "ACME GmbH" })).toHaveAttribute("href", `/companies/${acme.id}`);
  });

  it("shows the server's error next to the channel it names, and drops it when rows move", async () => {
    const { user, state } = start("/contacts/new", {
      refuseChannel: { value: "ada\u0001@x.example", problem: "INVALID_CHARACTER" },
    });
    await fill(user, await screen.findByLabelText("Name (required)"), "Ada");
    const add = screen.getByRole("button", { name: "Add a way to reach them" });
    await user.click(add);
    await user.click(within(channelRow(1)).getByText("Other"));
    await fill(user, within(channelRow(1)).getByLabelText("Details"), "@ada on Signal");
    await user.click(add);
    await fill(user, within(channelRow(2)).getByLabelText("Email address"), "ada\u0001@x.example");
    await user.click(screen.getByRole("button", { name: "Create contact" }));

    const value = within(channelRow(2)).getByLabelText("Email address");
    expect(
      await within(channelRow(2)).findByText("This contains a character Jofi cannot store."),
    ).toBeVisible();
    expect(value).toHaveAttribute("aria-invalid", "true");
    expect(state.contacts).toEqual([]);

    await user.click(screen.getByRole("button", { name: "Remove channel 2" }));
    expect(screen.queryByText("This contains a character Jofi cannot store.")).toBeNull();
    await user.click(screen.getByRole("button", { name: "Create contact" }));
    expect(await screen.findByRole("heading", { level: 1, name: "Ada" })).toBeVisible();
    expect(state.contacts[0]?.channels).toEqual([{ kind: "OTHER", value: "@ada on Signal", label: null }]);
  });

  it("refuses a save based on an old version, then loads the latest one to edit again", async () => {
    const contact = aContact(acme.id, { name: "Ada", role: "Recruiter", version: 3 });
    const { user, state } = start(`/contacts/${contact.id}/edit`, { companies: [acme], contacts: [contact] });
    const role = await screen.findByLabelText("Role");
    // Meanwhile, another tab renames the contact.
    state.contacts = [{ ...contact, name: "Ada King", version: 4 }];

    await user.clear(role);
    await user.type(role, "Head of Talent");
    await user.click(screen.getByRole("button", { name: "Save changes" }));
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Changed meanwhile");
    expect(state.contacts[0]).toMatchObject({ name: "Ada King", role: "Recruiter" });

    await user.click(within(alert).getByRole("button", { name: "Load latest version" }));
    expect(await screen.findByDisplayValue("Ada King")).toBeVisible();
    await user.clear(screen.getByLabelText("Role"));
    await user.type(screen.getByLabelText("Role"), "Head of Talent");
    await user.click(screen.getByRole("button", { name: "Save changes" }));
    expect(await screen.findByRole("heading", { level: 1, name: "Ada King" })).toBeVisible();
    expect(state.contacts[0]).toMatchObject({ role: "Head of Talent", version: 5 });
  });
});

describe("Contact detail", () => {
  it("renders channels safely: encoded mail and phone links, plain OTHER values and labels", async () => {
    const contact = aContact(acme.id, {
      name: "Mallory",
      channels: [
        { kind: "EMAIL", value: "m@evil.example?bcc=boss@acme.example", label: "<b>work</b>" },
        { kind: "PHONE", value: "+1 555 CALL-NOW", label: null },
        { kind: "WEB", value: "javascript:alert(1)", label: null },
        { kind: "OTHER", value: "https://not-a-link.example", label: null },
      ],
      relationshipNotes: 'Met **twice**. <img src="https://tracker.example/p.gif"> [x](javascript:alert(1))',
    });
    start(`/contacts/${contact.id}`, { companies: [acme], contacts: [contact] });
    expect(await screen.findByRole("heading", { level: 1, name: "Mallory" })).toBeVisible();

    const channels = screen.getByRole("region", { name: "Ways to reach them" });
    expect(
      within(channels).getByRole("link", { name: "m@evil.example?bcc=boss@acme.example" }),
    ).toHaveAttribute("href", "mailto:m%40evil.example%3Fbcc%3Dboss@acme.example");
    expect(within(channels).getByText("Email · <b>work</b>")).toBeVisible();
    expect(within(channels).getByRole("link", { name: "+1 555 CALL-NOW" })).toHaveAttribute(
      "href",
      "tel:+1555",
    );
    expect(within(channels).getByText("javascript:alert(1)").closest("a")).toBeNull();
    expect(within(channels).getByText("https://not-a-link.example").closest("a")).toBeNull();
    expect(within(channels).getAllByRole("link")).toHaveLength(2);

    const notes = screen.getByRole("region", { name: "Relationship notes" });
    expect(within(notes).getByText("twice").tagName).toBe("STRONG");
    expect(within(notes).queryByRole("link")).toBeNull();
    expect(document.querySelector("main img, main b")).toBeNull();
    expect(document.body.innerHTML).not.toContain("tracker.example");

    const applications = screen.getByRole("region", { name: "Applications" });
    expect(
      await within(applications).findByText(/appear here once the applications pages exist/),
    ).toBeVisible();
  });

  it("says when the contact does not exist", async () => {
    start(`/contacts/${crypto.randomUUID()}`);
    expect(await screen.findByRole("heading", { level: 1, name: "Contact not found" })).toBeVisible();
  });

  it("deletes a contact only after the confirmation, which names the linked applications", async () => {
    const contact = aContact(acme.id, { name: "Bill" });
    const { user, state, router } = start(`/contacts/${contact.id}`, {
      companies: [acme],
      contacts: [contact],
      linkedApplications: { [contact.id]: 2 },
    });
    await user.click(await screen.findByRole("button", { name: "Delete…" }));
    const dialog = await screen.findByRole("alertdialog", { name: "Delete this contact?" });
    expect(dialog).toHaveTextContent("Bill will be deleted with all personal data stored about them");
    expect(dialog).toHaveTextContent("The links to 2 applications are removed.");
    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
    expect(state.contacts).toHaveLength(1);

    await user.click(screen.getByRole("button", { name: "Delete…" }));
    const again = await screen.findByRole("alertdialog", { name: "Delete this contact?" });
    const confirm = within(again).getByRole("button", { name: "Delete contact" });
    await waitFor(() => expect(confirm).toBeEnabled());
    await user.click(confirm);
    expect(await screen.findByRole("heading", { level: 1, name: "Contacts" })).toBeVisible();
    expect(router.state.location.pathname).toBe("/contacts");
    expect(state.contacts).toEqual([]);
    expect(state.deleteCalls).toEqual(["first", "first", "confirmed"]);
  });
});

describe("From a company's page", () => {
  it("links each contact and adds a new one with the company preselected", async () => {
    const contact = aContact(acme.id, { name: "Grace Hopper", role: "CTO" });
    const { user, state } = start(`/companies/${acme.id}`, {
      companies: [acme, globex],
      contacts: [contact],
    });
    const contacts = await screen.findByRole("region", { name: "Contacts" });
    expect(await within(contacts).findByRole("link", { name: "Grace Hopper" })).toHaveAttribute(
      "href",
      `/contacts/${contact.id}`,
    );

    await user.click(within(contacts).getByRole("link", { name: "New contact" }));
    await user.type(await screen.findByLabelText("Name (required)"), "Alan");
    expect(await screen.findByRole("button", { name: /ACME GmbH.*Company/ })).toBeVisible();
    await user.click(screen.getByRole("button", { name: "Create contact" }));
    expect(await screen.findByRole("heading", { level: 1, name: "Alan" })).toBeVisible();
    expect(state.contacts.at(-1)).toMatchObject({ name: "Alan", companyId: acme.id });
  });
});
