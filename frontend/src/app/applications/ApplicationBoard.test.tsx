// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { createMemoryHistory } from "@tanstack/react-router";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { setupServer } from "msw/node";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
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

function start(path: string, data: Partial<FakeApplicationListState> = {}) {
  const auth = fakeAuthBackend({ authenticated: true });
  const list = fakeApplicationListBackend(data);
  const companies = fakeCompanyBackend({ companies: [acme] });
  server.use(...list.handlers, ...companies.handlers, ...auth.handlers);
  const app = createApp(createMemoryHistory({ initialEntries: [path] }));
  render(<App app={app} />);
  return { state: list.state, router: app.router, user: userEvent.setup() };
}

type User = ReturnType<typeof userEvent.setup>;

const column = (status: string) => screen.getByRole("grid", { name: status });
const cardTitles = (status: string) =>
  within(column(status))
    .queryAllByRole("row")
    .flatMap((row) => within(row).queryAllByRole("link").slice(0, 1))
    .map((link) => link.textContent);

async function openMoves(user: User, title: string) {
  await user.click(await screen.findByRole("button", { name: `Move ${title} to…` }));
  return screen.findByRole("menu", { name: `Move ${title} to…` });
}

const backend = aListedApplication(acme.id, { title: "Backend Engineer", status: "APPLIED" });
const designer = aListedApplication(acme.id, { title: "Product Designer", status: "OFFER" });
const tester = aListedApplication(acme.id, { title: "QA Engineer", status: "DISCOVERED" });
const ghosted = aListedApplication(acme.id, { title: "Data Engineer", status: "GHOSTED" });

describe("Applications board", () => {
  it("puts each application in the column of its status, the ended ones behind a disclosure", async () => {
    start("/applications?view=board", { applications: [backend, designer, tester, ghosted] });
    expect(await screen.findByText("4 applications")).toBeVisible();
    expect(cardTitles("Discovered")).toEqual(["QA Engineer"]);
    expect(cardTitles("Applied")).toEqual(["Backend Engineer"]);
    expect(cardTitles("Offer")).toEqual(["Product Designer"]);
    expect(within(column("Shortlisted")).getByText("Nothing here")).toBeVisible();
    expect(screen.queryByRole("grid", { name: "Ghosted" })).toBeNull();

    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Ended (1 application)" }));
    expect(cardTitles("Ghosted")).toEqual(["Data Engineer"]);
    expect(within(column("Applied")).getByRole("button", { name: "Drag Backend Engineer" })).toBeVisible();
  });

  it("switches between table and board with the filters kept in the URL", async () => {
    const { user, router, state } = start("/applications?status=APPLIED", {
      applications: [backend, designer],
    });
    expect(await screen.findByRole("table", { name: "Applications" })).toBeVisible();
    await user.click(screen.getByRole("radio", { name: "Board" }));
    expect(await screen.findByRole("grid", { name: "Applied" })).toBeVisible();
    expect(router.state.location.search).toEqual({ status: ["APPLIED"], view: "board" });
    expect(cardTitles("Applied")).toEqual(["Backend Engineer"]);
    expect(cardTitles("Offer")).toEqual([]);
    expect(state.searches.at(-1)?.getAll("status")).toEqual(["APPLIED"]);
    expect(state.searches.at(-1)?.get("size")).toBe("200");

    await user.click(screen.getByRole("radio", { name: "Table" }));
    expect(await screen.findByRole("table", { name: "Applications" })).toBeVisible();
    expect(router.state.location.search).toEqual({ status: ["APPLIED"] });
  });

  it("offers only the moves the matrix allows in a card's menu", async () => {
    const { user } = start("/applications?view=board", { applications: [tester] });
    const menu = await openMoves(user, "QA Engineer");
    const offered = within(menu)
      .getAllByRole("menuitem")
      .map((item) => item.textContent);
    expect(offered).toEqual(["Shortlisted", "Preparing", "Applied", "Interviewing", "Offer", "Declined"]);
  });

  it("moves a card with the keyboard through its menu, at once and then saved", async () => {
    const { user, state } = start("/applications?view=board", { applications: [tester] });
    await screen.findByText("1 application");
    let release = () => {};
    state.statusGate = new Promise((resolve) => {
      release = resolve;
    });
    screen.getByRole("button", { name: "Move QA Engineer to…" }).focus();
    await user.keyboard("{Enter}");
    const menu = await screen.findByRole("menu");
    await user.keyboard("{ArrowDown}{ArrowDown}");
    expect(within(menu).getByRole("menuitem", { name: "Applied" })).toHaveFocus();
    await user.keyboard("{Enter}");

    await waitFor(() => expect(cardTitles("Applied")).toEqual(["QA Engineer"]));
    expect(cardTitles("Discovered")).toEqual([]);
    release();
    await waitFor(() =>
      expect(state.statusChanges).toEqual([
        { status: "APPLIED", reason: null, declineCategory: null, basedOnVersion: 0 },
      ]),
    );
    expect(await screen.findByText("QA Engineer moved to Applied.")).toBeInTheDocument();
    expect(cardTitles("Applied")).toEqual(["QA Engineer"]);
  });

  it("moves a card by keyboard drag and drop to the next column that accepts it", async () => {
    const { user, state } = start("/applications?view=board", { applications: [tester] });
    const handle = await screen.findByRole("button", { name: "Drag QA Engineer" });
    handle.focus();
    await user.keyboard("{Enter}");
    await waitFor(() =>
      expect(document.activeElement).toHaveAttribute("aria-roledescription", "drop indicator"),
    );
    await user.keyboard("{Enter}");
    await waitFor(() =>
      expect(state.statusChanges).toEqual([
        { status: "SHORTLISTED", reason: null, declineCategory: null, basedOnVersion: 0 },
      ]),
    );
    await waitFor(() => expect(cardTitles("Shortlisted")).toEqual(["QA Engineer"]));
  });

  it("puts the card back and says why when the server refuses the move (changed elsewhere)", async () => {
    // The server already holds a newer version: the move is refused with 409 version-conflict.
    const stale = { ...backend, version: 3 };
    const { user, state } = start("/applications?view=board", { applications: [backend] });
    await screen.findByText("1 application");
    state.applications = [stale];
    const menu = await openMoves(user, "Backend Engineer");
    await user.click(within(menu).getByRole("menuitem", { name: "Interviewing" }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Not moved");
    expect(alert).toHaveTextContent("Backend Engineer was changed elsewhere meanwhile");
    await waitFor(() => expect(cardTitles("Applied")).toEqual(["Backend Engineer"]));
    expect(cardTitles("Interviewing")).toEqual([]);
    expect(state.statusChanges).toEqual([]);
  });

  it("names both statuses when the move is not allowed from where the server has it", async () => {
    const { user, state } = start("/applications?view=board", { applications: [backend] });
    await screen.findByText("1 application");
    // Meanwhile it was withdrawn elsewhere, without a new version reaching the board.
    state.applications = [{ ...backend, status: "DISCOVERED" }];
    const menu = await openMoves(user, "Backend Engineer");
    await user.click(within(menu).getByRole("menuitem", { name: "Ghosted" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Backend Engineer cannot move from Applied to Ghosted.",
    );
    // The board loads again and shows where the server has it.
    await waitFor(() => expect(cardTitles("Discovered")).toEqual(["Backend Engineer"]));
  });

  it("asks for a reason before declining, then shows the card in the ended columns", async () => {
    const { user, state } = start("/applications?view=board", { applications: [tester] });
    const menu = await openMoves(user, "QA Engineer");
    await user.click(within(menu).getByRole("menuitem", { name: "Declined" }));
    const dialog = await screen.findByRole("dialog", { name: "Move to Declined" });
    await user.click(within(dialog).getByRole("button", { name: "Change status" }));
    expect(await within(dialog).findByText("Choose a reason.")).toBeVisible();
    await user.click(within(dialog).getByRole("button", { name: /Reason$/ }));
    await user.click(await screen.findByRole("option", { name: "Salary" }));
    await user.click(within(dialog).getByRole("button", { name: "Change status" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(state.statusChanges).toEqual([
      { status: "DECLINED", reason: null, declineCategory: "SALARY", basedOnVersion: 0 },
    ]);
    await waitFor(() => expect(cardTitles("Discovered")).toEqual([]));
    await user.click(screen.getByRole("button", { name: "Ended (1 application)" }));
    expect(cardTitles("Declined")).toEqual(["QA Engineer"]);
  });

  it("opens the ended columns when the status filter asks for an ended status", async () => {
    start("/applications?view=board&status=GHOSTED", { applications: [ghosted, backend] });
    expect(await screen.findByRole("grid", { name: "Ghosted" })).toBeVisible();
    expect(cardTitles("Ghosted")).toEqual(["Data Engineer"]);
  });

  it("says when the board shows only the first 200 matches, and has no pages", async () => {
    const many = Array.from({ length: 201 }, (_, index) =>
      aListedApplication(acme.id, { title: `Role ${index}`, status: "DISCOVERED" }),
    );
    start("/applications?view=board", { applications: many });
    expect(
      await screen.findByText(
        "The board shows the first 200 of 201 applications. Narrow the filters to see the rest.",
      ),
    ).toBeVisible();
    expect(screen.queryByRole("navigation", { name: "Pages" })).toBeNull();
  });
});
