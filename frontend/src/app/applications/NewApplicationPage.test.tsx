// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { createMemoryHistory } from "@tanstack/react-router";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { setupServer } from "msw/node";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { type FakeApplicationState, fakeApplicationBackend } from "../../test/fakeApplicationBackend";
import { fakeApplicationListBackend } from "../../test/fakeApplicationListBackend";
import { fakeAuthBackend } from "../../test/fakeAuthBackend";
import { aCompany, fakeCompanyBackend } from "../../test/fakeCompanyBackend";
import { App, createApp } from "../App";

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const acme = aCompany({ name: "ACME GmbH" });
const globex = aCompany({ name: "Globex" });

/** The list and the single-application endpoints share what was created, like the real server. */
function start(path: string, data: Partial<FakeApplicationState> = {}) {
  const auth = fakeAuthBackend({ authenticated: true });
  const list = fakeApplicationListBackend();
  const applications = fakeApplicationBackend({
    companyIds: [acme.id, globex.id],
    onCreate: (created) => list.state.applications.push(created),
    ...data,
  });
  const companies = fakeCompanyBackend({ companies: [acme, globex] });
  // Before the company backend, which answers the applications list with 501.
  server.use(...applications.handlers, ...list.handlers, ...companies.handlers, ...auth.handlers);
  const app = createApp(createMemoryHistory({ initialEntries: [path] }));
  render(<App app={app} />);
  return { state: applications.state, list: list.state, router: app.router, user: userEvent.setup() };
}

type User = ReturnType<typeof userEvent.setup>;

async function fill(user: User, input: HTMLElement, text: string) {
  await user.clear(input);
  await user.click(input);
  await user.paste(text);
  await user.tab();
}

const companyPicker = () => screen.getByRole("button", { name: /Company \(required\)$/ });

describe("New application", () => {
  it("is created from the empty table, then shows its page, and the table lists it", async () => {
    const { user, state, router } = start("/applications");
    const empty = (await screen.findByRole("heading", { name: "Nothing here yet" })).closest("section");
    if (!empty) throw new Error("no empty state");
    await user.click(within(empty).getByRole("link", { name: "New application" }));

    expect(await screen.findByRole("heading", { level: 1, name: "New application" })).toBeVisible();
    await fill(user, screen.getByLabelText("Job title (required)"), " Platform Engineer ");
    await user.click(companyPicker());
    await user.click(await screen.findByRole("option", { name: "Globex" }));
    await fill(user, screen.getByLabelText("Location"), "Berlin");
    await user.click(screen.getByRole("button", { name: "Create application" }));

    expect(await screen.findByRole("heading", { level: 1, name: "Platform Engineer" })).toBeVisible();
    expect(state.creates).toHaveLength(1);
    expect(state.creates[0]).toMatchObject({
      title: "Platform Engineer",
      companyId: globex.id,
      location: "Berlin",
      payBand: null,
      offer: null,
    });

    await router.navigate({ to: "/applications" });
    expect(await screen.findByRole("table", { name: "Applications" })).toHaveTextContent("Platform Engineer");
  });

  it("offers the create page from the table's header, with the filtered company preselected", async () => {
    start(`/applications?company=${acme.id}`);
    const link = await screen.findByRole("link", { name: "New application" });
    expect(link).toHaveAttribute("href", `/applications/new?company=${acme.id}`);
  });

  it("is created from a company's page with that company preselected, and that page lists it", async () => {
    const { user, state, router } = start(`/companies/${acme.id}`);
    const section = await screen.findByRole("region", { name: "Applications" });
    expect(await within(section).findByText("No applications to this company yet.")).toBeVisible();
    await user.click(within(section).getByRole("link", { name: "New application" }));

    await waitFor(() => expect(companyPicker()).toHaveTextContent("ACME GmbH"));
    await fill(user, screen.getByLabelText("Job title (required)"), "QA Engineer");
    await user.click(screen.getByRole("button", { name: "Create application" }));
    expect(await screen.findByRole("heading", { level: 1, name: "QA Engineer" })).toBeVisible();
    expect(state.creates[0]?.companyId).toBe(acme.id);

    await router.navigate({ to: "/companies/$companyId", params: { companyId: acme.id } });
    const refreshed = await screen.findByRole("region", { name: "Applications" });
    expect(await within(refreshed).findByRole("link", { name: "QA Engineer" })).toBeVisible();
  });

  it("goes back to the company's page on cancel when it came from there", async () => {
    const { user, router } = start(`/applications/new?company=${acme.id}`);
    await user.click(await screen.findByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(router.state.location.pathname).toBe(`/companies/${acme.id}`));
  });

  it("asks for a company before sending", async () => {
    const { user, state } = start("/applications/new");
    await fill(user, await screen.findByLabelText("Job title (required)"), "Engineer");
    await user.click(screen.getByRole("button", { name: "Create application" }));
    expect(await screen.findByText("Choose a company.")).toBeVisible();
    expect(state.creates).toEqual([]);

    await user.click(companyPicker());
    await user.click(await screen.findByRole("option", { name: "ACME GmbH" }));
    expect(screen.queryByText("Choose a company.")).toBeNull();
  });

  it("shows the server's refusal of an unknown company next to the company", async () => {
    const { user, state } = start(`/applications/new?company=${crypto.randomUUID()}`);
    await fill(user, await screen.findByLabelText("Job title (required)"), "Engineer");
    await user.click(screen.getByRole("button", { name: "Create application" }));
    expect(
      await screen.findByText("This company does not exist (any more). Choose another one."),
    ).toBeVisible();
    expect(state.creates).toEqual([]);
  });

  it("shows the server's error next to the nested field it names", async () => {
    const { user, state } = start(`/applications/new?company=${acme.id}`, {
      refusePayMax: { value: 60000.25, problem: "TOO_PRECISE" },
    });
    await fill(user, await screen.findByLabelText("Job title (required)"), "Engineer");
    const pay = screen.getByRole("group", { name: "Pay band" });
    await fill(user, within(pay).getByLabelText("Maximum"), "60000.25");
    await user.click(screen.getByRole("button", { name: "Create application" }));
    expect(await within(pay).findByText("Use at most two decimals.")).toBeVisible();
    expect(within(pay).getByLabelText("Maximum")).toHaveAttribute("aria-invalid", "true");
    expect(state.creates).toEqual([]);
  });
});
