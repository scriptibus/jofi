// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { createMemoryHistory } from "@tanstack/react-router";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { setupServer } from "msw/node";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { fakeApplicationBackend } from "../../test/fakeApplicationBackend";
import { aListedApplication, fakeApplicationListBackend } from "../../test/fakeApplicationListBackend";
import { fakeAuthBackend } from "../../test/fakeAuthBackend";
import { aCompany, fakeCompanyBackend } from "../../test/fakeCompanyBackend";
import { aTask, type FakeTaskState, fakeTaskBackend } from "../../test/fakeTaskBackend";
import { App, createApp } from "../App";
import { DONE_PAGE_SIZE } from "./DoneTasks";

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const acme = aCompany({ name: "ACME GmbH" });
const platform = aListedApplication(acme.id, { title: "Platform Engineer" });

function start(data: Partial<FakeTaskState> = {}) {
  const auth = fakeAuthBackend({ authenticated: true });
  const tasks = fakeTaskBackend(data);
  const list = fakeApplicationListBackend({ applications: [platform] });
  const applications = fakeApplicationBackend({ applications: [platform] });
  const companies = fakeCompanyBackend({ companies: [acme] });
  server.use(
    ...tasks.handlers,
    ...list.handlers,
    ...applications.handlers,
    ...companies.handlers,
    ...auth.handlers,
  );
  const app = createApp(createMemoryHistory({ initialEntries: ["/tasks"] }));
  render(<App app={app} />);
  return { state: tasks.state, user: userEvent.setup() };
}

const done = (title: string, completedAt: string, overrides = {}) =>
  aTask({ title, status: "DONE", completedAt, version: 1, ...overrides });

async function openDoneView(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole("tab", { name: "Done" }));
  return screen.findByRole("region", { name: "Done tasks" });
}

describe("the done view", () => {
  it("lists the done tasks newest first with when they were completed and what they are about", async () => {
    const older = done("Send the invoice", "2026-09-01T09:00:00Z");
    const newer = done("Call Anna", "2026-09-02T09:00:00Z", {
      link: { type: "APPLICATION", id: platform.id },
    });
    const { user, state } = start({ tasks: [older, newer, aTask({ title: "Still open" })] });

    expect(await screen.findByRole("tab", { name: "Open", selected: true })).toBeVisible();
    const region = await openDoneView(user);

    expect(await within(region).findAllByRole("listitem")).toHaveLength(2);
    const [first, second] = within(region).getAllByRole("listitem");
    expect(first).toHaveTextContent("Call Anna");
    expect(first).toHaveTextContent("Completed Sep 2, 2026");
    expect(
      await within(first as HTMLElement).findByRole("link", { name: "Application: Platform Engineer" }),
    ).toBeVisible();
    expect(second).toHaveTextContent("Send the invoice");
    expect(within(region).queryByText("Still open")).toBeNull();
    expect(screen.getByRole("tab", { name: "Done", selected: true })).toBeVisible();
    expect(screen.queryByRole("region", { name: "Quick add" })).toBeNull();
    expect(state.doneRequests).toEqual([{ page: 0, size: DONE_PAGE_SIZE }]);
  });

  it("reopens a task based on its version: it leaves the done list and is open again", async () => {
    const task = done("Call Anna", "2026-09-02T09:00:00Z", { version: 3 });
    const { user, state } = start({ tasks: [task] });
    const region = await openDoneView(user);

    await user.click(await within(region).findByRole("button", { name: "Reopen task: Call Anna" }));

    expect(await screen.findByRole("status")).toHaveTextContent("“Call Anna” is open again.");
    expect(state.stateChanges).toEqual([{ done: false, basedOnVersion: 3 }]);
    expect(await within(region).findByRole("heading", { name: "No done tasks" })).toBeVisible();
    expect(within(region).getByRole("heading", { name: "Done tasks" })).toHaveFocus();
    await user.click(screen.getByRole("tab", { name: "Open" }));
    expect(await screen.findByRole("checkbox", { name: "Call Anna" })).not.toBeChecked();
  });

  it("shows a task completed on the open view when the done view is opened", async () => {
    const { user } = start({ tasks: [aTask({ title: "Call Anna" })] });

    await user.click(await screen.findByText("Call Anna"));
    expect(await screen.findByText("“Call Anna” is done.")).toBeVisible();
    const region = await openDoneView(user);

    expect(await within(region).findByText("Call Anna")).toBeVisible();
  });

  it("says so when nothing is done", async () => {
    const { user } = start({ tasks: [aTask()] });
    const region = await openDoneView(user);

    expect(await within(region).findByRole("heading", { name: "No done tasks" })).toBeVisible();
    expect(within(region).queryByRole("button", { name: /^Reopen/ })).toBeNull();
  });

  it("offers a retry when the list cannot be loaded", { timeout: 10_000 }, async () => {
    const { user, state } = start({
      tasks: [done("Call Anna", "2026-09-02T09:00:00Z")],
      doneUnavailable: true,
    });
    const region = await openDoneView(user);

    const alert = await within(region).findByRole("alert", {}, { timeout: 6000 });
    expect(alert).toHaveTextContent("The done tasks could not be loaded.");
    state.doneUnavailable = false;
    await user.click(within(alert).getByRole("button", { name: "Try again" }));

    expect(await within(region).findByText("Call Anna")).toBeVisible();
    expect(within(region).queryByRole("alert")).toBeNull();
  });

  it("shows a bounded page and moves between pages", async () => {
    const tasks = Array.from({ length: DONE_PAGE_SIZE + 5 }, (_, index) =>
      done(`Task ${String(index).padStart(2, "0")}`, `2026-09-01T09:${String(index).padStart(2, "0")}:00Z`),
    );
    const { user, state } = start({ tasks });
    const region = await openDoneView(user);

    expect(await within(region).findAllByRole("listitem")).toHaveLength(DONE_PAGE_SIZE);
    expect(within(region).getAllByRole("listitem")[0]).toHaveTextContent("Task 24");
    expect(within(region).getByRole("button", { name: "Previous page" })).toBeDisabled();
    expect(within(region).getByText("Page 1 of 2")).toBeVisible();

    await user.click(within(region).getByRole("button", { name: "Next page" }));

    expect(await within(region).findByText("Page 2 of 2")).toBeVisible();
    expect(within(region).getAllByRole("listitem")).toHaveLength(5);
    expect(within(region).getByRole("button", { name: "Next page" })).toBeDisabled();
    expect(state.doneRequests.at(-1)).toEqual({ page: 1, size: DONE_PAGE_SIZE });
  });

  it("goes back a page when the last task of the last page is reopened", async () => {
    const tasks = Array.from({ length: DONE_PAGE_SIZE + 1 }, (_, index) =>
      done(`Task ${String(index).padStart(2, "0")}`, `2026-09-01T09:${String(index).padStart(2, "0")}:00Z`),
    );
    const { user } = start({ tasks });
    const region = await openDoneView(user);
    await user.click(await within(region).findByRole("button", { name: "Next page" }));

    await user.click(await within(region).findByRole("button", { name: "Reopen task: Task 00" }));

    await waitFor(() => expect(within(region).getAllByRole("listitem")).toHaveLength(DONE_PAGE_SIZE));
    expect(within(region).queryByRole("navigation", { name: "Pages" })).toBeNull();
  });

  it("explains a task that changed elsewhere meanwhile and reloads the list", async () => {
    const task = done("Call Anna", "2026-09-02T09:00:00Z", { version: 1 });
    const { user, state } = start({ tasks: [task] });
    const region = await openDoneView(user);
    await within(region).findByText("Call Anna");
    // The AI reopened and completed it again after this page loaded it.
    state.tasks = state.tasks.map((other) => ({ ...other, version: 5 }));

    await user.click(within(region).getByRole("button", { name: "Reopen task: Call Anna" }));

    expect(await screen.findByText(/changed elsewhere meanwhile/)).toBeVisible();
    expect(state.stateChanges).toEqual([]);
    await waitFor(() => expect(state.doneRequests.length).toBeGreaterThan(1));
    await user.click(await within(region).findByRole("button", { name: "Reopen task: Call Anna" }));
    expect(await screen.findByText("“Call Anna” is open again.")).toBeVisible();
    expect(state.stateChanges).toEqual([{ done: false, basedOnVersion: 5 }]);
  });
});
