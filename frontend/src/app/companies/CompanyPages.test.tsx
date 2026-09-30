// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { createMemoryHistory } from "@tanstack/react-router";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { setupServer } from "msw/node";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import type { CompanyResponse } from "../../api/generated/jofi";
import { fakeAuthBackend } from "../../test/fakeAuthBackend";
import { aCompany, aContact, type FakeCompanyState, fakeCompanyBackend } from "../../test/fakeCompanyBackend";
import { App, createApp } from "../App";

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function start(path: string, companies: Partial<FakeCompanyState> = {}) {
  const auth = fakeAuthBackend({ authenticated: true });
  const backend = fakeCompanyBackend(companies);
  server.use(...backend.handlers, ...auth.handlers);
  const app = createApp(createMemoryHistory({ initialEntries: [path] }));
  render(<App app={app} />);
  return { state: backend.state, router: app.router, user: userEvent.setup() };
}

describe("Companies list", () => {
  const acme = aCompany({
    name: "ACME GmbH",
    preference: "FAVOURITE",
    industry: "Robotics",
    applicationCount: 2,
  });
  const globex = aCompany({ name: "Globex", preference: "BLACKLISTED" });
  const initech = aCompany({ name: "Initech" });

  it("lists companies with flag, industry and number of applications", async () => {
    start("/companies", { companies: [acme, globex, initech] });
    expect(await screen.findByText("3 companies")).toBeVisible();
    const card = screen.getByRole("link", { name: "ACME GmbH" }).closest("article");
    if (card === null) throw new Error("no card");
    expect(within(card).getByText("Favourite")).toBeVisible();
    expect(within(card).getByText("Robotics")).toBeVisible();
    expect(within(card).getByText("2 applications")).toBeVisible();
  });

  it("searches on the server as the user types and keeps the query in the URL", async () => {
    const { state, user, router } = start("/companies", { companies: [acme, globex, initech] });
    await screen.findByText("3 companies");
    await user.type(screen.getByRole("searchbox", { name: "Search companies" }), "glob");
    expect(await screen.findByText("1 company matches")).toBeVisible();
    expect(screen.getByRole("link", { name: "Globex" })).toBeVisible();
    expect(screen.queryByRole("link", { name: "Initech" })).toBeNull();
    expect(state.searches.at(-1)?.get("search")).toBe("glob");
    expect(router.state.location.search).toEqual({ q: "glob" });
  });

  it("follows the URL when it changes on its own, without typing it back", async () => {
    const { user, router } = start("/companies?q=glob", { companies: [acme, globex, initech] });
    const box = await screen.findByRole("searchbox", { name: "Search companies" });
    expect(box).toHaveValue("glob");
    await router.navigate({ to: "/companies", search: { q: "init" } });
    await waitFor(() => expect(box).toHaveValue("init"));
    expect(await screen.findByRole("link", { name: "Initech" })).toBeVisible();
    await user.clear(box);
    await waitFor(() => expect(router.state.location.search).toEqual({}));
    expect(await screen.findByText("3 companies")).toBeVisible();
  });

  it("filters by flag", async () => {
    const { state, user } = start("/companies", { companies: [acme, globex, initech] });
    await screen.findByText("3 companies");
    await user.click(within(screen.getByRole("radiogroup", { name: "Show" })).getByText("Blacklisted"));
    expect(await screen.findByText("1 company matches")).toBeVisible();
    expect(screen.getByRole("link", { name: "Globex" })).toBeVisible();
    expect(state.searches.at(-1)?.get("preference")).toBe("BLACKLISTED");
  });

  it("shows an empty state with the way to add the first company", async () => {
    start("/companies");
    expect(await screen.findByText(/No companies yet/)).toBeVisible();
    expect(screen.getByRole("link", { name: "New company" })).toHaveAttribute("href", "/companies/new");
  });

  it("pages through more than 50 companies", async () => {
    const many = Array.from({ length: 51 }, (_, index) => aCompany({ name: `Company ${index}` }));
    const { user } = start("/companies", { companies: many });
    expect(await screen.findByText("Page 1 of 2")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "Next page" }));
    expect(await screen.findByRole("link", { name: "Company 50" })).toBeVisible();
    expect(screen.getByRole("button", { name: "Next page" })).toBeDisabled();
  });
});

describe("Create and edit", () => {
  it("creates a company after checking the web address, then shows it", async () => {
    const { user, state } = start("/companies/new");
    await user.type(await screen.findByLabelText("Name (required)"), "  Initech  ");
    await user.type(screen.getByLabelText("Website"), "initech.example");
    await user.type(screen.getByLabelText("Locations"), "Austin{Enter}austin{Enter}Remote");
    await user.click(screen.getByRole("button", { name: "Create company" }));
    expect(screen.getByLabelText("Website")).toHaveAccessibleDescription(/Enter a full web address/);
    expect(state.companies).toEqual([]);

    await user.clear(screen.getByLabelText("Website"));
    await user.type(screen.getByLabelText("Website"), "https://initech.example");
    await user.click(screen.getByRole("button", { name: "Create company" }));

    expect(await screen.findByRole("heading", { level: 1, name: "Initech" })).toBeVisible();
    expect(state.companies[0]).toMatchObject({ name: "Initech", locations: ["Austin", "Remote"] });
    expect(screen.getByText("Austin · Remote")).toBeVisible();
  });

  it("shows the server's field errors next to the fields", async () => {
    const { user, state } = start("/companies/new", {
      refuse: { field: "industry", value: "Bad\u0001", problem: "INVALID_CHARACTER" },
    });
    await user.type(await screen.findByLabelText("Name (required)"), "Initech");
    await user.type(screen.getByLabelText("Industry"), "Bad\u0001");
    await user.click(screen.getByRole("button", { name: "Create company" }));
    expect(await screen.findByText("This contains a character Jofi cannot store.")).toBeVisible();
    expect(screen.getByLabelText("Industry")).toHaveAttribute("aria-invalid", "true");
    expect(state.companies).toEqual([]);

    // Editing the field clears its server error, so the next submit is not blocked.
    await user.clear(screen.getByLabelText("Industry"));
    await user.type(screen.getByLabelText("Industry"), "Software");
    await user.click(screen.getByRole("button", { name: "Create company" }));
    expect(await screen.findByRole("heading", { level: 1, name: "Initech" })).toBeVisible();
  });

  it("refuses a save based on an old version, then loads the latest one to edit again", async () => {
    const company = aCompany({ name: "Hooli", version: 3 });
    const { user, state } = start(`/companies/${company.id}/edit`, { companies: [company] });
    const name = await screen.findByLabelText("Name (required)");
    // Meanwhile, another tab renames it.
    state.companies = [{ ...company, name: "Hooli XYZ", version: 4 }];

    await user.clear(name);
    await user.type(name, "Hooli Inc");
    await user.click(screen.getByRole("button", { name: "Save changes" }));
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Changed meanwhile");
    expect(state.companies[0]?.name).toBe("Hooli XYZ");

    await user.click(within(alert).getByRole("button", { name: "Load latest version" }));
    expect(await screen.findByDisplayValue("Hooli XYZ")).toBeVisible();
    await user.type(screen.getByLabelText("Name (required)"), " Inc");
    await user.click(screen.getByRole("button", { name: "Save changes" }));
    expect(await screen.findByRole("heading", { level: 1, name: "Hooli XYZ Inc" })).toBeVisible();
    expect(state.companies[0]).toMatchObject({ name: "Hooli XYZ Inc", version: 5 });
  });
});

describe("Company detail", () => {
  function companyWithEverything(): CompanyResponse {
    return aCompany({
      name: "ACME GmbH",
      website: "https://acme.example",
      careersPage: "https://jobs.acme.example",
      size: "MEDIUM",
      researchNotes: "**Great** team. [Jobs](https://jobs.acme.example)",
      profile: {
        generatedAt: "2026-09-30T10:00:00Z",
        markdown:
          "Summary <script>alert(1)</script>\n\n[click](javascript:alert(1)) ![pixel](https://tracker.example/p.gif)",
      },
      applicationCount: 1,
    });
  }

  it("shows the details, sanitised notes and profile, contacts and applications", async () => {
    const company = companyWithEverything();
    const contact = aContact(company.id, { name: "Grace Hopper", role: "CTO" });
    start(`/companies/${company.id}`, { companies: [company], contacts: [contact] });

    expect(await screen.findByRole("heading", { level: 1, name: "ACME GmbH" })).toBeVisible();
    const website = screen.getByRole("link", { name: "https://acme.example" });
    expect(website).toHaveAttribute("rel", "noopener noreferrer nofollow");
    expect(screen.getByText("50–249")).toBeVisible();
    expect(screen.getByText("Great").tagName).toBe("STRONG");

    const profile = screen.getByRole("region", { name: "AI profile" });
    expect(within(profile).getByText(/Generated by AI on/)).toBeVisible();
    expect(within(profile).getByText("click")).toBeVisible();
    expect(within(profile).queryByRole("link")).toBeNull();
    expect(document.querySelector("main img, main script")).toBeNull();
    expect(document.body.innerHTML).not.toContain("tracker.example");

    const contacts = screen.getByRole("region", { name: "Contacts" });
    expect(await within(contacts).findByText("Grace Hopper")).toBeVisible();
    expect(within(contacts).getByText("CTO")).toBeVisible();
    const applications = screen.getByRole("region", { name: "Applications" });
    expect(await within(applications).findByText(/1 application\. The list follows/)).toBeVisible();
  });

  it("says when the company does not exist", async () => {
    start(`/companies/${crypto.randomUUID()}`);
    expect(await screen.findByRole("heading", { level: 1, name: "Company not found" })).toBeVisible();
  });

  it("flags a company as blacklisted with a reason", async () => {
    const company = aCompany({ name: "Globex" });
    const { user, state } = start(`/companies/${company.id}`, { companies: [company] });
    await user.click(await screen.findByRole("button", { name: "Change flag…" }));
    const dialog = screen.getByRole("dialog", { name: "Flag this company" });
    await user.click(within(dialog).getByText("Blacklisted"));
    await user.type(within(dialog).getByLabelText("Reason"), "  Unpaid trial work  ");
    await user.click(within(dialog).getByRole("button", { name: "Save flag" }));

    expect(dialog).not.toBeInTheDocument();
    expect(state.companies[0]).toMatchObject({
      preference: "BLACKLISTED",
      preferenceReason: "Unpaid trial work",
    });
    const section = screen.getByRole("region", { name: "Flag" });
    expect(within(section).getByText("Blacklisted")).toBeVisible();
    expect(within(section).getByText("Unpaid trial work")).toBeVisible();
  });

  it("reports a flag change based on an old version and saves it after loading the latest", async () => {
    const company = aCompany({ name: "Globex", version: 1 });
    const { user, state } = start(`/companies/${company.id}`, { companies: [company] });
    await user.click(await screen.findByRole("button", { name: "Change flag…" }));
    state.companies = [{ ...company, industry: "Chemicals", version: 2 }];
    const dialog = screen.getByRole("dialog", { name: "Flag this company" });
    await user.click(within(dialog).getByText("Favourite"));
    await user.click(within(dialog).getByRole("button", { name: "Save flag" }));
    const alert = await within(dialog).findByRole("alert");
    expect(alert).toHaveTextContent("changed elsewhere");

    await user.click(within(alert).getByRole("button", { name: "Load latest version" }));
    await user.click(within(dialog).getByRole("button", { name: "Save flag" }));
    expect(await screen.findByText("Chemicals")).toBeVisible();
    expect(state.companies[0]).toMatchObject({ preference: "FAVOURITE", version: 3 });
  });

  it("deletes a company and its contacts only after the confirmation", async () => {
    const company = aCompany({ name: "Initech" });
    const contacts = [aContact(company.id), aContact(company.id, { name: "Bill" })];
    const { user, state, router } = start(`/companies/${company.id}`, { companies: [company], contacts });
    await user.click(await screen.findByRole("button", { name: "Delete…" }));
    const dialog = await screen.findByRole("alertdialog", { name: "Delete this company?" });
    expect(dialog).toHaveTextContent("Initech will be deleted together with its 2 contacts.");
    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
    expect(state.companies).toHaveLength(1);

    await user.click(screen.getByRole("button", { name: "Delete…" }));
    const again = await screen.findByRole("alertdialog", { name: "Delete this company?" });
    const confirm = within(again).getByRole("button", { name: "Delete company" });
    // Armed only after a moment, so the tap that opened the dialog cannot confirm it.
    await waitFor(() => expect(confirm).toBeEnabled());
    await user.click(confirm);
    expect(await screen.findByRole("heading", { level: 1, name: "Companies" })).toBeVisible();
    expect(router.state.location.pathname).toBe("/companies");
    expect(state.companies).toEqual([]);
    expect(state.deleteCalls).toEqual(["first", "first", "confirmed"]);
  });

  it("refuses to delete a company that still has applications, without asking", async () => {
    const company = aCompany({ name: "Hooli", applicationCount: 1 });
    const { user, state } = start(`/companies/${company.id}`, { companies: [company] });
    await user.click(await screen.findByRole("button", { name: "Delete…" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("still has applications");
    expect(screen.queryByRole("alertdialog")).toBeNull();
    expect(state.companies).toHaveLength(1);
  });
});
