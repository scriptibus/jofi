// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { createMemoryHistory } from "@tanstack/react-router";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { setupServer } from "msw/node";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { aListedApplication, fakeApplicationListBackend } from "../../test/fakeApplicationListBackend";
import { fakeAuthBackend } from "../../test/fakeAuthBackend";
import { aCompany, fakeCompanyBackend } from "../../test/fakeCompanyBackend";
import { aSavedView, type FakeSavedViewState } from "../../test/fakeSavedViewBackend";
import { App, createApp } from "../App";

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const acme = aCompany({ name: "ACME GmbH" });

function start(path: string, views: Partial<FakeSavedViewState> = {}) {
  const auth = fakeAuthBackend({ authenticated: true });
  const list = fakeApplicationListBackend(
    { applications: [aListedApplication(acme.id, { title: "Backend Engineer", status: "APPLIED" })] },
    views,
  );
  const companies = fakeCompanyBackend({ companies: [acme] });
  server.use(...list.handlers, ...companies.handlers, ...auth.handlers);
  const app = createApp(createMemoryHistory({ initialEntries: [path] }));
  render(<App app={app} />);
  return { views: list.views, router: app.router, user: userEvent.setup() };
}

type User = ReturnType<typeof userEvent.setup>;

const savedViews = () => screen.getByRole("region", { name: "Saved views" });
const region = () => screen.findByRole("region", { name: "Saved views" });

async function viewAction(user: User, name: string, action: "Rename…" | "Delete…") {
  await user.click(await screen.findByRole("button", { name: `Actions for ${name}` }));
  await user.click(await screen.findByRole("menuitem", { name: action }));
}

describe("Saved views", () => {
  it("says so when there are none yet", async () => {
    start("/applications");
    expect(await screen.findByText(/No saved views yet/)).toBeVisible();
  });

  it("shows 'Loading…' until the views arrive", async () => {
    let release = () => {};
    const listGate = new Promise<void>((resolve) => {
      release = resolve;
    });
    start("/applications", { listGate, views: [aSavedView({ name: "Offers" })] });
    expect(await screen.findByText("Loading saved views…")).toBeVisible();
    release();
    expect(await screen.findByRole("button", { name: "Offers" })).toBeVisible();
  });

  it("says why the views could not be loaded and tries again", async () => {
    const { views } = start("/applications", { failWith: 400, views: [aSavedView({ name: "Offers" })] });
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("The saved views could not be loaded.");
    views.failWith = null;
    const user = userEvent.setup();
    await user.click(within(alert).getByRole("button", { name: "Try again" }));
    expect(await screen.findByRole("button", { name: "Offers" })).toBeVisible();
  });

  it("saves the filters and the order shown under a name", async () => {
    const { views, user } = start(`/applications?company=${acme.id}&status=APPLIED&sort=TITLE&updated=30`);
    await screen.findByText("1 application matches");
    await user.click(within(savedViews()).getByRole("button", { name: "Save view" }));
    const dialog = await screen.findByRole("dialog", { name: "Save this view" });
    await user.type(within(dialog).getByRole("textbox", { name: "Name" }), "  Applied at ACME ");
    await user.click(within(dialog).getByRole("button", { name: "Save view" }));

    expect(await within(await region()).findByRole("button", { name: "Applied at ACME" })).toBeVisible();
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(within(savedViews()).getByRole("status")).toHaveTextContent("Saved view Applied at ACME.");
    expect(views.saved).toEqual([
      {
        name: "Applied at ACME",
        filter: {
          companyId: acme.id,
          status: ["APPLIED"],
          sort: "TITLE",
          direction: "ASCENDING",
          updatedFrom: expect.any(String),
        },
      },
    ]);
  });

  it("refuses a name another view has, at the name field", async () => {
    const { views, user } = start("/applications", { views: [aSavedView({ name: "Offers" })] });
    await user.click(await within(await region()).findByRole("button", { name: "Save view" }));
    const dialog = await screen.findByRole("dialog", { name: "Save this view" });
    await user.type(within(dialog).getByRole("textbox", { name: "Name" }), "offers");
    await user.click(within(dialog).getByRole("button", { name: "Save view" }));
    expect(await within(dialog).findByText("Another view already has this name.")).toBeVisible();
    expect(views.saved).toEqual([]);
  });

  it("reopens a view: its filters and order replace the list's, and the table follows", async () => {
    const offers = aSavedView({
      name: "ACME offers",
      filter: { companyId: acme.id, status: ["OFFER"], sort: "DEADLINE", direction: "DESCENDING" },
    });
    const { router, user } = start("/applications?q=engineer&view=board", { views: [offers] });
    await user.click(await within(await region()).findByRole("button", { name: "ACME offers" }));

    expect(router.state.location.search).toEqual({
      company: acme.id,
      status: ["OFFER"],
      sort: "DEADLINE",
      dir: "DESCENDING",
      view: "board",
    });
    expect(within(savedViews()).getByRole("status")).toHaveTextContent("Opened view ACME offers.");
    expect(await screen.findByText("No application matches these filters.")).toBeVisible();
  });

  it("says when a reopened view lost filters", async () => {
    const adjusted = aSavedView({ name: "Old view", adjusted: true, filter: { contactId: acme.id } });
    const { user } = start("/applications", { views: [adjusted] });
    await user.click(await within(await region()).findByRole("button", { name: "Old view" }));
    const status = within(savedViews()).getByRole("status");
    expect(status).toHaveTextContent("Some of its filters are no longer valid and were left out.");
    expect(status).toHaveTextContent("Some of its filters cannot be shown on this page and were left out.");
  });

  it("renames a view, keeping its filter", async () => {
    const filter = { companyId: acme.id, wantMax: 4 };
    const { views, user } = start("/applications", { views: [aSavedView({ name: "Offers", filter })] });
    await viewAction(user, "Offers", "Rename…");
    const dialog = await screen.findByRole("dialog", { name: "Rename view" });
    const name = within(dialog).getByRole("textbox", { name: "Name" });
    expect(name).toHaveValue("Offers");
    await user.clear(name);
    await user.type(name, "Good offers");
    await user.click(within(dialog).getByRole("button", { name: "Rename" }));

    expect(await within(await region()).findByRole("button", { name: "Good offers" })).toBeVisible();
    expect(views.saved).toEqual([{ basedOnVersion: 0, view: { name: "Good offers", filter } }]);
    expect(within(savedViews()).getByRole("status")).toHaveTextContent("Renamed the view to Good offers.");
  });

  it("shows 'Changed meanwhile' when the view changed elsewhere, then renames the latest version", async () => {
    const offers = aSavedView({ name: "Offers" });
    const { views, user } = start("/applications", { views: [offers] });
    await viewAction(user, "Offers", "Rename…");
    const dialog = await screen.findByRole("dialog", { name: "Rename view" });
    views.views = [{ ...offers, version: 1 }];
    const name = within(dialog).getByRole("textbox", { name: "Name" });
    await user.clear(name);
    await user.type(name, "Mine");
    await user.click(within(dialog).getByRole("button", { name: "Rename" }));

    const conflict = await within(dialog).findByRole("alert");
    expect(conflict).toHaveTextContent("Changed meanwhile");
    expect(views.saved).toEqual([]);
    await user.click(within(conflict).getByRole("button", { name: "Load latest version" }));
    await waitFor(() => expect(within(dialog).queryByRole("alert")).toBeNull());
    await user.click(within(dialog).getByRole("button", { name: "Rename" }));
    expect(await within(await region()).findByRole("button", { name: "Mine" })).toBeVisible();
    expect(views.saved).toEqual([{ basedOnVersion: 1, view: { name: "Mine", filter: {} } }]);
  });

  it("deletes a view only after the user confirms the server's question", async () => {
    const { views, user } = start("/applications", { views: [aSavedView({ name: "Offers" })] });
    await viewAction(user, "Offers", "Delete…");
    const dialog = await screen.findByRole("alertdialog", { name: "Delete this view?" });
    expect(dialog).toHaveTextContent("The view Offers will be deleted. The applications it shows stay.");
    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
    expect(views.deleteCalls).toEqual(["first"]);
    expect(within(savedViews()).getByRole("button", { name: "Offers" })).toBeVisible();

    await viewAction(user, "Offers", "Delete…");
    const again = await screen.findByRole("alertdialog", { name: "Delete this view?" });
    const confirm = within(again).getByRole("button", { name: "Delete view" });
    await waitFor(() => expect(confirm).toBeEnabled());
    await user.click(confirm);

    expect(await within(await region()).findByText(/No saved views yet/)).toBeVisible();
    expect(views.deleteCalls).toEqual(["first", "first", "confirmed"]);
    expect(within(savedViews()).getByRole("status")).toHaveTextContent("Deleted view Offers.");
  });
});
