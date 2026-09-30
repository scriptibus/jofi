// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { createMemoryHistory } from "@tanstack/react-router";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { setupServer } from "msw/node";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import type { ApplicationResponse } from "../../api/generated/jofi";
import {
  aListedApplication,
  type FakeApplicationListState,
  fakeApplicationListBackend,
} from "../../test/fakeApplicationListBackend";
import { fakeAuthBackend } from "../../test/fakeAuthBackend";
import { aCompany, fakeCompanyBackend } from "../../test/fakeCompanyBackend";
import { App, createApp } from "../App";

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const acme = aCompany({ name: "ACME GmbH" });
const globex = aCompany({ name: "Globex" });

function start(path: string, data: Partial<FakeApplicationListState> = {}) {
  const auth = fakeAuthBackend({ authenticated: true });
  const list = fakeApplicationListBackend(data);
  const companies = fakeCompanyBackend({ companies: [acme, globex] });
  // The list's handlers first: the company backend answers the applications list with 501.
  server.use(...list.handlers, ...companies.handlers, ...auth.handlers);
  const app = createApp(createMemoryHistory({ initialEntries: [path] }));
  render(<App app={app} />);
  return { state: list.state, router: app.router, user: userEvent.setup() };
}

type User = ReturnType<typeof userEvent.setup>;

async function pick(user: User, label: RegExp, option: string) {
  await user.click(screen.getByRole("button", { name: label }));
  await user.click(await screen.findByRole("option", { name: option }));
}

const table = () => screen.getByRole("table", { name: "Applications" });
const titles = () =>
  within(table())
    .getAllByRole("row")
    .slice(1)
    .map((row) => within(row).getAllByRole("link")[0]?.textContent);

const backend = aListedApplication(acme.id, {
  title: "Backend Engineer",
  status: "APPLIED",
  unread: true,
  deadline: "2026-10-15",
  languageAndTone: { postingLanguage: "de" },
  updatedAt: "2026-09-29T10:00:00Z",
});
const designer = aListedApplication(globex.id, {
  title: "Product Designer",
  status: "OFFER",
  deadline: "2026-10-01",
  languageAndTone: { applicationLanguage: "en" },
  sources: [{ id: crypto.randomUUID(), kind: "SCANNER", discoveredAt: "2026-09-01T10:00:00Z", online: true }],
  updatedAt: "2026-09-30T10:00:00Z",
});
const tester = aListedApplication(acme.id, { title: "QA Engineer", status: "DISCOVERED", wantScore: 4.5 });

describe("Applications list", () => {
  it("shows each application's title, company, status, source, language, deadline and unread dot", async () => {
    start("/applications", { applications: [backend, designer] });
    expect(await screen.findByText("2 applications")).toBeVisible();
    const row = within(table()).getByRole("link", { name: "Backend Engineer" }).closest("tr");
    if (row === null) throw new Error("no row");
    expect(within(row).getByRole("img", { name: "Unread" })).toBeInTheDocument();
    expect(await within(row).findByRole("link", { name: "ACME GmbH" })).toHaveAttribute(
      "href",
      `/companies/${acme.id}`,
    );
    expect(within(row).getByText("Applied")).toBeVisible();
    expect(within(row).getByText("By hand")).toBeVisible();
    expect(within(row).getByText("German")).toBeVisible();
    expect(within(row).getByText("Oct 15, 2026")).toBeVisible();
    expect(within(row).getByRole("link", { name: "Backend Engineer" })).toHaveAttribute(
      "href",
      `/applications/${backend.id}`,
    );
    const other = within(table()).getByRole("link", { name: "Product Designer" }).closest("tr");
    if (other === null) throw new Error("no row");
    expect(within(other).queryByRole("img", { name: "Unread" })).toBeNull();
    expect(within(other).getByText("Scanner")).toBeVisible();
    expect(within(other).getByText("English")).toBeVisible();
  });

  it("shows scores once there are any", async () => {
    start("/applications", { applications: [tester] });
    expect(await screen.findByText("Want 4.5 / Fit –")).toBeVisible();
  });

  it("sorts newest update first and marks it with aria-sort", async () => {
    start("/applications", { applications: [backend, designer] });
    await screen.findByText("2 applications");
    expect(titles()).toEqual(["Product Designer", "Backend Engineer"]);
    expect(within(table()).getByRole("columnheader", { name: "Updated" })).toHaveAttribute(
      "aria-sort",
      "descending",
    );
  });

  it("sorts by deadline from the column heading, with the order in the URL", async () => {
    const { state, user, router } = start("/applications", { applications: [tester, backend, designer] });
    await screen.findByText("3 applications");
    await user.click(within(table()).getByRole("button", { name: "Deadline" }));
    await waitFor(() => expect(titles()).toEqual(["Product Designer", "Backend Engineer", "QA Engineer"]));
    expect(within(table()).getByRole("columnheader", { name: "Deadline" })).toHaveAttribute(
      "aria-sort",
      "ascending",
    );
    expect(state.searches.at(-1)?.get("sort")).toBe("DEADLINE");
    expect(router.state.location.search).toEqual({ sort: "DEADLINE" });

    await user.click(within(table()).getByRole("button", { name: "Deadline" }));
    await waitFor(() => expect(titles()).toEqual(["Backend Engineer", "Product Designer", "QA Engineer"]));
    expect(state.searches.at(-1)?.get("direction")).toBe("DESCENDING");
    expect(router.state.location.search).toEqual({ sort: "DEADLINE", dir: "DESCENDING" });
  });

  it("filters by several statuses, with them in the URL", async () => {
    const { state, user, router } = start("/applications", { applications: [tester, backend, designer] });
    await screen.findByText("3 applications");
    await user.click(screen.getByRole("button", { name: /Any status.*Status/ }));
    const list = await screen.findByRole("listbox", { name: "Status" });
    await user.click(within(list).getByRole("option", { name: "Offer" }));
    await user.click(within(list).getByRole("option", { name: "Applied" }));
    await user.keyboard("{Escape}");
    expect(await screen.findByText("2 applications match")).toBeVisible();
    expect(state.searches.at(-1)?.getAll("status")).toEqual(["APPLIED", "OFFER"]);
    expect(router.state.location.search).toEqual({ status: ["APPLIED", "OFFER"] });
  });

  it("filters by company and unread from the URL on load, and by language", async () => {
    const { state, user } = start(`/applications?company=${globex.id}`, {
      applications: [backend, designer, tester],
    });
    expect(await screen.findByText("1 application matches")).toBeVisible();
    expect(screen.getByRole("button", { name: /Globex.*Company/ })).toBeVisible();
    expect(state.searches.at(-1)?.get("companyId")).toBe(globex.id);

    await pick(user, /Application language/, "German");
    expect(await screen.findByText("No application matches these filters.")).toBeVisible();
    expect(state.searches.at(-1)?.getAll("language")).toEqual(["de"]);
  });

  it("shows only unread ones on request", async () => {
    const { state, user } = start("/applications", { applications: [backend, designer] });
    await screen.findByText("2 applications");
    await user.click(screen.getByText("Unread only"));
    expect(await screen.findByText("1 application matches")).toBeVisible();
    expect(state.searches.at(-1)?.get("unread")).toBe("true");
  });

  it("searches titles as the user types, with the text in the URL", async () => {
    const { state, user, router } = start("/applications", { applications: [backend, designer, tester] });
    await screen.findByText("3 applications");
    await user.type(screen.getByRole("searchbox", { name: "Search job titles" }), "engineer");
    expect(await screen.findByText("2 applications match")).toBeVisible();
    expect(state.searches.at(-1)?.get("search")).toBe("engineer");
    expect(router.state.location.search).toEqual({ q: "engineer" });
    // While searching, the best match comes first: no column is sorted.
    expect(
      within(table())
        .queryAllByRole("columnheader", { name: /./ })
        .some((h) => h.hasAttribute("aria-sort")),
    ).toBe(false);
  });

  it("offers to reset filters when nothing matches", async () => {
    const { user, router } = start("/applications?unread=true", { applications: [designer] });
    expect(await screen.findByText("No application matches these filters.")).toBeVisible();
    await user.click(screen.getAllByRole("button", { name: "Reset filters" })[0] as HTMLElement);
    expect(await screen.findByText("1 application")).toBeVisible();
    expect(router.state.location.search).toEqual({});
  });

  it("sorts from the sort picker (the phone's cards have no column headings)", async () => {
    const { state, user, router } = start("/applications", { applications: [backend, designer] });
    await screen.findByText("2 applications");
    await pick(user, /Sort by/, "Deadline, descending");
    await waitFor(() => expect(state.searches.at(-1)?.get("direction")).toBe("DESCENDING"));
    expect(state.searches.at(-1)?.get("sort")).toBe("DEADLINE");
    expect(router.state.location.search).toEqual({ sort: "DEADLINE", dir: "DESCENDING" });
  });

  it("shows an empty state before the first application", async () => {
    start("/applications");
    expect(await screen.findByRole("heading", { name: "Nothing here yet" })).toBeVisible();
    expect(screen.getByText(/No applications yet/)).toBeVisible();
  });

  it("pages through more than 50, with the page in the URL", async () => {
    const many: ApplicationResponse[] = Array.from({ length: 51 }, (_, index) =>
      aListedApplication(acme.id, {
        title: `Role ${String(index).padStart(2, "0")}`,
        updatedAt: `2026-09-${String(1 + (index % 28)).padStart(2, "0")}T10:00:00Z`,
      }),
    );
    const { state, user, router } = start("/applications?sort=TITLE", { applications: many });
    expect(await screen.findByText("51 applications")).toBeVisible();
    expect(screen.getByText("Page 1 of 2")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "Next page" }));
    expect(await screen.findByText("Page 2 of 2")).toBeVisible();
    expect(titles()).toEqual(["Role 50"]);
    expect(state.searches.at(-1)?.get("page")).toBe("1");
    expect(router.state.location.search).toEqual({ sort: "TITLE", page: 1 });
  });

  it("marks an application read and unread from the list", async () => {
    const application = aListedApplication(acme.id, { title: "Backend Engineer", unread: true });
    const { state, user } = start("/applications", { applications: [application] });
    await screen.findByText("1 application");
    await user.click(within(table()).getByRole("button", { name: "Mark Backend Engineer as read" }));
    expect(
      await within(table()).findByRole("button", { name: "Mark Backend Engineer as unread" }),
    ).toBeVisible();
    expect(within(table()).queryByRole("img", { name: "Unread" })).toBeNull();
    expect(state.applications[0]?.unread).toBe(false);
  });

  it("says when the list cannot be loaded and tries again on request", async () => {
    const { state, user } = start("/applications", { applications: [designer], failWith: 400 });
    expect(await screen.findByText("The applications could not be loaded.")).toBeVisible();
    state.failWith = null;
    await user.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByText("1 application")).toBeVisible();
  });
});
