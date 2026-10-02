// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { createMemoryHistory } from "@tanstack/react-router";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { setupServer } from "msw/node";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { fakeApplicationBackend } from "../../test/fakeApplicationBackend";
import { aListedApplication } from "../../test/fakeApplicationListBackend";
import { fakeAuthBackend } from "../../test/fakeAuthBackend";
import { aCompany } from "../../test/fakeCompanyBackend";
import { aTask, type FakeTaskState, fakeTaskBackend } from "../../test/fakeTaskBackend";
import { App, createApp } from "../App";

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const platform = aListedApplication(aCompany().id, { title: "Platform Engineer" });

/** A suggestion of the follow-up rule (backend `FollowUpSuggestion`), due on a day, about the application. */
const followUp = (overrides: Parameters<typeof aTask>[0] = {}) =>
  aTask({
    title: "Follow up: Platform Engineer",
    origin: "SUGGESTED",
    suggestionRule: "follow-up",
    status: "SUGGESTED",
    timing: { span: "DAY", startsOn: "2026-10-05", endsBefore: "2026-10-06" },
    link: { type: "APPLICATION", id: platform.id },
    ...overrides,
  });

const preparation = (overrides: Parameters<typeof aTask>[0] = {}) =>
  aTask({
    title: "Prepare for the interview: Site Reliability Engineer",
    origin: "SUGGESTED",
    suggestionRule: "interview-preparation",
    status: "SUGGESTED",
    timing: { span: "DAY", startsOn: "2026-10-07", endsBefore: "2026-10-08" },
    ...overrides,
  });

function start(data: Partial<FakeTaskState> = {}) {
  const tasks = fakeTaskBackend(data);
  const applications = fakeApplicationBackend({ applications: [platform] });
  server.use(
    ...tasks.handlers,
    ...applications.handlers,
    ...fakeAuthBackend({ authenticated: true }).handlers,
  );
  render(<App app={createApp(createMemoryHistory({ initialEntries: ["/tasks"] }))} />);
  return { state: tasks.state, user: userEvent.setup() };
}

const suggestions = () => screen.findByRole("region", { name: "Suggested tasks" });

describe("suggested tasks", () => {
  it("lists each suggestion with when it is due and what it is about, newest first", async () => {
    start({ tasks: [followUp(), preparation(), aTask({ title: "Call Anna" })] });
    const section = await suggestions();
    const items = await within(section).findAllByRole("listitem");
    expect(items.map((item) => item.querySelector(".font-semibold")?.textContent)).toEqual([
      "Prepare for the interview: Site Reliability Engineer",
      "Follow up: Platform Engineer",
    ]);
    expect(within(items[1] as HTMLElement).getByText("Due Oct 5, 2026")).toBeVisible();
    expect(
      await within(items[1] as HTMLElement).findByRole("link", { name: "Application: Platform Engineer" }),
    ).toHaveAttribute("href", `/applications/${platform.id}`);
    // An open task is no suggestion.
    expect(within(section).queryByText("Call Anna")).toBeNull();
  });

  it("accepts a suggestion with one click: it leaves the suggestions and is an open task", async () => {
    const suggestion = followUp({ version: 2 });
    const { user, state } = start({ tasks: [suggestion], groups: { [suggestion.id]: "NEXT_WEEK" } });
    const section = await suggestions();
    expect(screen.queryByRole("region", { name: /^Next week/ })).toBeNull();

    await user.click(
      await within(section).findByRole("button", { name: "Accept suggestion: Follow up: Platform Engineer" }),
    );

    expect(await screen.findByText("“Follow up: Platform Engineer” added to your tasks.")).toBeVisible();
    expect(state.decisions).toEqual([{ decision: "accept", id: suggestion.id, basedOnVersion: 2 }]);
    const nextWeek = await screen.findByRole("region", { name: "Next week (1)" });
    expect(
      within(nextWeek).getByRole("checkbox", { name: "Follow up: Platform Engineer" }),
    ).not.toBeChecked();
    expect(within(section).queryByRole("listitem")).toBeNull();
    expect(within(section).getByRole("heading", { name: "Suggested tasks" })).toHaveFocus();
  });

  it("dismisses a suggestion with one click: it is gone, and no task", async () => {
    const suggestion = preparation();
    const { user, state } = start({ tasks: [followUp(), suggestion] });
    const section = await suggestions();

    await user.click(
      await within(section).findByRole("button", {
        name: "Dismiss suggestion: Prepare for the interview: Site Reliability Engineer",
      }),
    );

    expect(
      await screen.findByText("Suggestion “Prepare for the interview: Site Reliability Engineer” dismissed."),
    ).toBeVisible();
    expect(state.decisions).toEqual([{ decision: "dismiss", id: suggestion.id, basedOnVersion: 0 }]);
    await waitFor(() => expect(within(section).getAllByRole("listitem")).toHaveLength(1));
    expect(within(section).getByText("Follow up: Platform Engineer")).toBeVisible();
    expect(screen.getByRole("heading", { name: "No open tasks" })).toBeVisible();
  });

  it("says when there is nothing to suggest", async () => {
    start({ tasks: [aTask({ title: "Call Anna" })] });
    const section = await suggestions();
    expect(
      await within(section).findByText(
        "No suggestions right now. Follow-ups, interview preparation and offer deadlines show up here.",
      ),
    ).toBeVisible();
    expect(within(section).queryByRole("button")).toBeNull();
  });

  it("says when the suggestions could not be loaded, and tries again", { timeout: 10_000 }, async () => {
    const { user, state } = start({ tasks: [followUp()], suggestionsUnavailable: true });
    const section = await suggestions();
    const alert = await within(section).findByRole("alert", {}, { timeout: 6000 });
    expect(alert).toHaveTextContent("The suggested tasks could not be loaded.");

    state.suggestionsUnavailable = false;
    await user.click(within(alert).getByRole("button", { name: "Try again" }));
    expect(await within(section).findByText("Follow up: Platform Engineer")).toBeVisible();
    expect(within(section).queryByRole("alert")).toBeNull();
  });

  it("says why when the suggestion was decided elsewhere, and loads the suggestions again", async () => {
    const suggestion = followUp();
    const { user, state } = start({ tasks: [suggestion] });
    const section = await suggestions();
    const accept = await within(section).findByRole("button", {
      name: "Accept suggestion: Follow up: Platform Engineer",
    });
    // Dismissed in another tab meanwhile.
    state.tasks = [{ ...suggestion, status: "DISMISSED", version: 1 }];

    await user.click(accept);
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "This task was changed elsewhere meanwhile. The list has been reloaded; please try again.",
    );
    expect(await within(section).findByText(/^No suggestions right now/)).toBeVisible();
    expect(state.decisions).toEqual([]);
  });
});

describe("many suggestions", () => {
  const many = (count: number) =>
    Array.from({ length: count }, (_, index) =>
      followUp({ title: `Follow up ${index + 1}`, suggestionRule: null }),
    );

  it("loads 50 at a time and shows the rest on request", async () => {
    const { user, state } = start({ tasks: many(60) });
    const section = await suggestions();
    await waitFor(() => expect(within(section).getAllByRole("listitem")).toHaveLength(50));
    expect(state.suggestionPages).toEqual([{ page: 0, size: 50 }]);

    await user.click(within(section).getByRole("button", { name: "Show more suggestions" }));

    await waitFor(() => expect(within(section).getAllByRole("listitem")).toHaveLength(60));
    expect(within(section).queryByRole("button", { name: "Show more suggestions" })).toBeNull();
    expect(state.suggestionPages).toContainEqual({ page: 1, size: 50 });
  });

  it("keeps the next page right after a decision, loading the list again", async () => {
    const { user, state } = start({ tasks: many(60) });
    const section = await suggestions();
    await waitFor(() => expect(within(section).getAllByRole("listitem")).toHaveLength(50));

    await user.click(within(section).getByRole("button", { name: "Dismiss suggestion: Follow up 60" }));
    await waitFor(() => expect(state.decisions).toHaveLength(1));
    await user.click(await within(section).findByRole("button", { name: "Show more suggestions" }));

    await waitFor(() => expect(within(section).getAllByRole("listitem")).toHaveLength(59));
    expect(within(section).getByText("Follow up 1")).toBeVisible();
  });
});
