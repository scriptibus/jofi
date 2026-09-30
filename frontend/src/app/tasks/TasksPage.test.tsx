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
import { viewerTimeZone } from "./task";

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const acme = aCompany({ name: "ACME GmbH" });
const platform = aListedApplication(acme.id, { title: "Platform Engineer" });

function start(path: string, data: Partial<FakeTaskState> = {}) {
  const auth = fakeAuthBackend({ authenticated: true });
  const tasks = fakeTaskBackend(data);
  const list = fakeApplicationListBackend({ applications: [platform] });
  const applications = fakeApplicationBackend({ applications: [platform] });
  const companies = fakeCompanyBackend({ companies: [acme] });
  // Before the company backend, which answers the applications list with 501.
  server.use(
    ...tasks.handlers,
    ...list.handlers,
    ...applications.handlers,
    ...companies.handlers,
    ...auth.handlers,
  );
  const app = createApp(createMemoryHistory({ initialEntries: [path] }));
  render(<App app={app} />);
  return { state: tasks.state, router: app.router, queryClient: app.queryClient, user: userEvent.setup() };
}

type User = ReturnType<typeof userEvent.setup>;

async function fill(user: User, input: HTMLElement, text: string) {
  await user.clear(input);
  await user.click(input);
  await user.paste(text);
}

const group = (name: RegExp) => screen.findByRole("region", { name });

describe("the grouped list", () => {
  it("shows each group with tasks as a section with its count, in the viewer's zone", async () => {
    const late = aTask({ title: "Send the thank-you note", timing: { span: "DAY", startsOn: "2026-09-01" } });
    const call = aTask({ title: "Call Anna", link: { type: "APPLICATION", id: platform.id } });
    const read = aTask({ title: "Read the annual report", timing: { span: "SOMEDAY" } });
    const { state } = start("/tasks", {
      tasks: [late, call, read],
      groups: { [late.id]: "OVERDUE", [call.id]: "THIS_WEEK", [read.id]: "SOMEDAY" },
    });

    const overdue = await group(/^Overdue \(1\)$/);
    expect(within(overdue).getByRole("checkbox", { name: "Send the thank-you note" })).not.toBeChecked();
    // Not colour alone: the word is there next to the icon.
    expect(within(overdue).getByText("Overdue")).toBeVisible();
    expect(within(overdue).getByText("Due Sep 1, 2026")).toBeVisible();

    const week = await group(/^This week \(1\)$/);
    const chip = await within(week).findByRole("link", { name: "Application: Platform Engineer" });
    expect(chip).toHaveAttribute("href", `/applications/${platform.id}`);
    expect(within(week).queryByText("Overdue")).toBeNull();
    expect(await group(/^Someday \(1\)$/)).toHaveTextContent("Read the annual report");
    expect(screen.queryByRole("region", { name: /^Today/ })).toBeNull();

    expect(state.listZones[0]).toBe(viewerTimeZone());
  });

  it("says so when nothing is open", async () => {
    start("/tasks");
    expect(await screen.findByRole("heading", { name: "No open tasks" })).toBeVisible();
  });

  it("shows notes as Markdown behind a disclosure", async () => {
    const { user } = start("/tasks", { tasks: [aTask({ notes: "Ask about **remote** days" })] });
    await user.click(await screen.findByRole("button", { name: "Notes" }));
    expect(await screen.findByText("remote", { selector: "strong" })).toBeVisible();
  });
});

describe("quick add", () => {
  it("adds a task for this week by default, or in the chosen bucket", async () => {
    const { user, state } = start("/tasks");
    const quick = await screen.findByRole("region", { name: "Quick add" });
    expect(within(quick).getByRole("radio", { name: "This week" })).toBeChecked();

    await fill(user, within(quick).getByLabelText("Task (required)"), "Update the CV ");
    await user.click(within(quick).getByRole("button", { name: "Add task" }));
    expect(await group(/^This week \(1\)$/)).toHaveTextContent("Update the CV");
    expect(state.creates[0]).toEqual({
      title: "Update the CV",
      timing: { timeZone: viewerTimeZone(), bucket: "THIS_WEEK" },
      link: null,
      notes: null,
    });
    expect(screen.getByRole("status")).toHaveTextContent("“Update the CV” added.");
    expect(within(quick).getByLabelText("Task (required)")).toHaveValue("");

    await fill(user, within(quick).getByLabelText("Task (required)"), "Book the train");
    await user.click(within(quick).getByText("Today"));
    await user.click(within(quick).getByRole("button", { name: "Add task" }));
    expect(await group(/^Today \(1\)$/)).toHaveTextContent("Book the train");
    expect(state.creates[1]?.timing.bucket).toBe("TODAY");
    expect(within(quick).getByRole("radio", { name: "This week" })).toBeChecked();
  });

  it("asks for a title before sending", async () => {
    const { user, state } = start("/tasks");
    const quick = await screen.findByRole("region", { name: "Quick add" });
    await user.click(within(quick).getByRole("button", { name: "Add task" }));
    expect(await within(quick).findByText("Enter a value.")).toBeVisible();
    expect(state.creates).toEqual([]);
  });
});

describe("complete and undo", () => {
  it("checks the task at once, keeps it in place, and undo reopens it", async () => {
    const { promise: gate, resolve: release } = Promise.withResolvers<void>();
    const task = aTask({ title: "Call Anna", version: 3 });
    const { user, state } = start("/tasks", { tasks: [task], completeGate: gate });

    const box = await screen.findByRole("checkbox", { name: "Call Anna" });
    await user.click(box);
    // Optimistic: checked before the server answered.
    expect(box).toBeChecked();
    expect(state.stateChanges).toEqual([]);
    release();

    expect(await screen.findByText("“Call Anna” is done.")).toBeVisible();
    expect(state.stateChanges).toEqual([{ done: true, basedOnVersion: 3 }]);
    expect(screen.getByRole("checkbox", { name: "Call Anna" })).toBeChecked();
    expect(await group(/^This week \(0\)$/)).toBeVisible();

    await user.click(screen.getByRole("button", { name: "Undo" }));
    expect(await screen.findByText("“Call Anna” is open again.")).toBeVisible();
    // Based on the version the complete answered with.
    expect(state.stateChanges[1]).toEqual({ done: false, basedOnVersion: 4 });
    expect(screen.getByRole("checkbox", { name: "Call Anna" })).not.toBeChecked();
  });

  it("brings a reopened task back when the list was reloaded after it was done", async () => {
    const { user, state, queryClient } = start("/tasks", { tasks: [aTask({ title: "Call Anna" })] });
    await user.click(await screen.findByRole("checkbox", { name: "Call Anna" }));
    await screen.findByText("“Call Anna” is done.");
    // A reload meanwhile (e.g. on window focus): the server lists open tasks only.
    await queryClient.refetchQueries({ queryKey: ["/api/tasks"] });
    await waitFor(() => expect(screen.queryByRole("checkbox", { name: "Call Anna" })).toBeNull());

    await user.click(screen.getByRole("button", { name: "Undo" }));
    expect(await screen.findByRole("checkbox", { name: "Call Anna" })).not.toBeChecked();
    expect(state.stateChanges.map(({ done }) => done)).toEqual([true, false]);
  });

  it("reopens with the checkbox too", async () => {
    const { user, state } = start("/tasks", { tasks: [aTask({ title: "Call Anna" })] });
    const box = await screen.findByRole("checkbox", { name: "Call Anna" });
    await user.click(box);
    await screen.findByText("“Call Anna” is done.");
    await user.click(box);
    await waitFor(() => expect(state.stateChanges.map(({ done }) => done)).toEqual([true, false]));
    expect(box).not.toBeChecked();
  });

  it("puts the checkbox back and says why when the task changed elsewhere", async () => {
    const task = aTask({ title: "Call Anna", version: 1 });
    const { user, state } = start("/tasks", { tasks: [task] });
    const box = await screen.findByRole("checkbox", { name: "Call Anna" });
    // Edited in another tab meanwhile.
    state.tasks = [{ ...task, title: "Call Anna back", version: 2 }];

    await user.click(box);
    expect(await screen.findByRole("alert")).toHaveTextContent("This task was changed elsewhere meanwhile.");
    // The list loads again with the latest version, open.
    expect(await screen.findByRole("checkbox", { name: "Call Anna back" })).not.toBeChecked();
    expect(state.stateChanges).toEqual([]);
  });
});

describe("delete", () => {
  it("asks with the server's effect and deletes only on yes", async () => {
    const task = aTask({ title: "Call Anna" });
    const { user, state } = start("/tasks", { tasks: [task] });
    await user.click(await screen.findByRole("button", { name: "Delete task: Call Anna" }));
    const dialog = await screen.findByRole("alertdialog");
    expect(dialog).toHaveTextContent("“Call Anna” will be deleted. This cannot be undone.");
    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
    expect(state.tasks).toHaveLength(1);

    await user.click(screen.getByRole("button", { name: "Delete task: Call Anna" }));
    const again = await screen.findByRole("alertdialog", { name: "Delete this task?" });
    const confirm = within(again).getByRole("button", { name: "Delete task" });
    await waitFor(() => expect(confirm).toBeEnabled());
    await user.click(confirm);
    expect(await screen.findByText("“Call Anna” deleted.")).toBeVisible();
    expect(await screen.findByRole("heading", { name: "No open tasks" })).toBeVisible();
    expect(state.deleteCalls).toEqual(["first", "first", "confirmed"]);
  });
});

describe("the full form", () => {
  it("creates a task at an exact time in the viewer's zone, linked to an application", async () => {
    const { user, state, router } = start("/tasks");
    await user.click(await screen.findByRole("link", { name: "New task with all details" }));
    expect(await screen.findByRole("heading", { level: 1, name: "New task" })).toBeVisible();

    await fill(user, screen.getByLabelText("Task (required)"), "Prepare the interview");
    await user.click(screen.getByText("At a set time"));
    expect(screen.getByText(`Time in ${viewerTimeZone()}.`)).toBeVisible();
    await fill(user, screen.getByLabelText("Date and time (required)"), "2026-10-05T10:00");
    await user.click(within(screen.getByRole("radiogroup", { name: "About" })).getByText("Application"));
    await user.click(screen.getByRole("button", { name: /Application$/ }));
    await user.click(await screen.findByRole("option", { name: "Platform Engineer · ACME GmbH" }));
    await fill(user, screen.getByLabelText("Notes"), "Read the **posting**");
    await user.click(screen.getByRole("button", { name: "Create task" }));

    await waitFor(() => expect(router.state.location.pathname).toBe("/tasks"));
    expect(state.creates[0]).toEqual({
      title: "Prepare the interview",
      notes: "Read the **posting**",
      timing: { timeZone: viewerTimeZone(), localDue: "2026-10-05T10:00" },
      link: { type: "APPLICATION", id: platform.id },
    });
    expect(await screen.findByRole("checkbox", { name: "Prepare the interview" })).toBeVisible();
  });

  it("creates a bucketed task, and asks for a record once a kind of link is chosen", async () => {
    const { user, state } = start("/tasks/new");
    await fill(user, await screen.findByLabelText("Task (required)"), "Research ACME");
    await user.click(screen.getByText("Next week"));
    await user.click(within(screen.getByRole("radiogroup", { name: "About" })).getByText("Company"));
    await user.click(screen.getByRole("button", { name: "Create task" }));
    expect(await screen.findByText("Choose one, or choose “Nothing”.")).toBeVisible();
    expect(state.creates).toEqual([]);

    await user.click(screen.getByRole("button", { name: /Company$/ }));
    await user.click(await screen.findByRole("option", { name: "ACME GmbH" }));
    await user.click(screen.getByRole("button", { name: "Create task" }));
    await waitFor(() => expect(state.creates).toHaveLength(1));
    expect(state.creates[0]).toMatchObject({
      timing: { bucket: "NEXT_WEEK" },
      link: { type: "COMPANY", id: acme.id },
    });
  });

  it("shows the server's refusal of a date next to the field", async () => {
    const { user, state } = start("/tasks/new");
    await fill(user, await screen.findByLabelText("Task (required)"), "Time travel");
    await user.click(screen.getByText("At a set time"));
    await fill(user, screen.getByLabelText("Date and time (required)"), "1999-12-31T23:00");
    await user.click(screen.getByRole("button", { name: "Create task" }));
    expect(await screen.findByText("Enter a date between 2000 and 2099.")).toBeVisible();
    expect(state.creates).toEqual([]);
  });
});

describe("edit", () => {
  it("opens with the task's values and saves them based on its version", async () => {
    const task = aTask({
      title: "Call Anna",
      version: 5,
      timing: { dueAt: "2026-10-05T09:00:00Z", localDue: "2026-10-05T10:00:00", timeZone: "Europe/London" },
      link: { type: "APPLICATION", id: platform.id },
    });
    const { user, state, router } = start("/tasks", { tasks: [task] });
    await user.click(await screen.findByRole("link", { name: "Edit task: Call Anna" }));
    expect(await screen.findByRole("heading", { level: 1, name: "Edit task" })).toBeVisible();
    expect(screen.getByLabelText("Task (required)")).toHaveValue("Call Anna");
    expect(screen.getByLabelText("Date and time (required)")).toHaveValue("2026-10-05T10:00");
    expect(screen.getByText("Time in Europe/London.")).toBeVisible();
    await waitFor(() =>
      expect(screen.getByRole("button", { name: /Application$/ })).toHaveTextContent("Platform Engineer"),
    );

    await fill(user, screen.getByLabelText("Task (required)"), "Call Anna back");
    await user.click(screen.getByRole("button", { name: "Save changes" }));
    await waitFor(() => expect(router.state.location.pathname).toBe("/tasks"));
    expect(state.updates[0]).toEqual({
      basedOnVersion: 5,
      details: {
        title: "Call Anna back",
        notes: null,
        timing: { timeZone: "Europe/London", localDue: "2026-10-05T10:00" },
        link: { type: "APPLICATION", id: platform.id },
      },
    });
    expect(await screen.findByRole("checkbox", { name: "Call Anna back" })).toBeVisible();
  });

  it("says when the task changed meanwhile and loads the latest version", async () => {
    const task = aTask({ title: "Call Anna", version: 1 });
    const { user, state } = start(`/tasks/${task.id}/edit`, { tasks: [task] });
    await screen.findByDisplayValue("Call Anna");
    state.tasks = [{ ...task, title: "Call Anna today", version: 2 }];

    await user.click(screen.getByRole("button", { name: "Save changes" }));
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Changed meanwhile");
    await user.click(within(alert).getByRole("button", { name: "Load latest version" }));
    expect(await screen.findByDisplayValue("Call Anna today")).toBeVisible();
    expect(state.updates).toEqual([]);
  });

  it("asks for a new timing when the task's bucket has passed", async () => {
    const task = aTask({
      title: "Call Anna",
      timing: { span: "DAY", startsOn: "2026-01-05", endsBefore: "2026-01-06" },
    });
    const { user, state } = start(`/tasks/${task.id}/edit`, { tasks: [task] });
    expect(
      await screen.findByText("This task was due: Due Jan 5, 2026. Choose when it is due now."),
    ).toBeVisible();
    await user.click(screen.getByRole("button", { name: "Save changes" }));
    expect(await screen.findByText("Choose when it is due.")).toBeVisible();
    expect(state.updates).toEqual([]);

    await user.click(screen.getByText("This month"));
    await user.click(screen.getByRole("button", { name: "Save changes" }));
    await waitFor(() => expect(state.updates[0]?.details.timing.bucket).toBe("THIS_MONTH"));
  });

  it("says when the task does not exist", async () => {
    start(`/tasks/${crypto.randomUUID()}/edit`);
    expect(await screen.findByRole("heading", { level: 1, name: "Task not found" })).toBeVisible();
  });
});
