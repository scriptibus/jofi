// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { createMemoryHistory } from "@tanstack/react-router";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { setupServer } from "msw/node";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { fakeAuthBackend } from "../../test/fakeAuthBackend";
import {
  aCostSummary,
  anActivityEntry,
  aPipeline,
  type FakeDashboardState,
  fakeDashboardBackend,
} from "../../test/fakeDashboardBackend";
import { aTask } from "../../test/fakeTaskBackend";
import { App, createApp } from "../App";
import { viewerTimeZone } from "../tasks/task";

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function start(data: Partial<FakeDashboardState> = {}) {
  // An AI provider exists or the guide was skipped: the dashboard opens instead of the setup guide.
  window.localStorage.setItem("jofi.setup-guide", "dismissed");
  const auth = fakeAuthBackend({ authenticated: true });
  const dashboard = fakeDashboardBackend(data);
  server.use(...dashboard.handlers, ...auth.handlers);
  const app = createApp(createMemoryHistory({ initialEntries: ["/"] }));
  render(<App app={app} />);
  return { state: dashboard.state, router: app.router, user: userEvent.setup() };
}

const widget = (name: string) => screen.findByRole("region", { name });

/** The href the router gives a link to the applications list with these filters. */
const hrefOf = (router: ReturnType<typeof createApp>["router"], search: Record<string, unknown>) =>
  router.buildLocation({ to: "/applications", search }).href;

describe("a fresh instance", () => {
  it("shows every widget with its empty state, and a way to start", async () => {
    start();
    expect(
      await screen.findByRole("heading", { level: 1, name: "Let the donkey do the donkey work." }),
    ).toBeVisible();

    const pipeline = await widget("Pipeline");
    expect(await within(pipeline).findByText(/^No applications yet\./)).toBeVisible();
    expect(within(pipeline).getByRole("link", { name: "New application" })).toHaveAttribute(
      "href",
      "/applications/new",
    );
    expect(await within(await widget("Funnel")).findByText(/^Once you have applied somewhere/)).toBeVisible();
    expect(
      await within(await widget("Tasks")).findByText("Nothing overdue and nothing due in the next 7 days."),
    ).toBeVisible();
    expect(
      await within(await widget("Recent activity")).findByText(/^Nothing has happened yet\./),
    ).toBeVisible();
    const cost = await widget("AI cost this month");
    expect(await within(cost).findByText("$0.00")).toBeVisible();
    expect(within(cost).getByText("No monthly budget set.")).toBeVisible();
    // No failure anywhere.
    expect(screen.queryByRole("alert")).toBeNull();
  });
});

describe("pipeline and funnel", () => {
  it("lists the occupied statuses with their counts, each linking to the list filtered by it", async () => {
    const { router } = start({ pipeline: aPipeline({ DISCOVERED: 1234, INTERVIEWING: 2 }, {}, 3) });
    const pipeline = await widget("Pipeline");

    const interviewing = await within(pipeline).findByRole("link", { name: "Interviewing" });
    expect(interviewing).toHaveAttribute("href", hrefOf(router, { status: ["INTERVIEWING"] }));
    expect(within(pipeline).getByRole("link", { name: "Discovered" }).closest("li")).toHaveTextContent(
      "1,234",
    );
    expect(within(pipeline).queryByRole("link", { name: "Offer" })).toBeNull();
    expect(within(pipeline).getByRole("link", { name: "Unread: 3" })).toHaveAttribute(
      "href",
      hrefOf(router, { unread: true }),
    );
    expect(within(pipeline).getByRole("link", { name: "All applications" })).toHaveAttribute(
      "href",
      "/applications",
    );
  });

  it("shows applied → interview → offer with their rates and the response rate", async () => {
    const { router } = start({
      pipeline: aPipeline(
        { APPLIED: 3, OFFER: 1, REJECTED: 1 },
        {
          applied: 5,
          interviewed: 2,
          offered: 1,
          responded: 2,
          interviewRate: 0.4,
          offerRate: 0.5,
          responseRate: 0.4,
        },
      ),
    });
    const funnel = await widget("Funnel");
    expect(await within(funnel).findByText("Applied")).toBeVisible();
    expect(within(funnel).getByText("Interview").closest("div")).toHaveTextContent(
      "2 · 40% of the stage before",
    );
    expect(within(funnel).getByText("Offer").closest("div")).toHaveTextContent("1 · 50% of the stage before");
    expect(within(funnel).getByText("40%")).toBeVisible();
    expect(within(funnel).getByText("Response rate: 2 of 5 applications got an answer.")).toBeVisible();
    expect(within(funnel).getByRole("link", { name: "Applications you applied for" })).toHaveAttribute(
      "href",
      hrefOf(router, {
        status: ["APPLIED", "INTERVIEWING", "OFFER", "ACCEPTED", "REJECTED", "WITHDRAWN", "GHOSTED"],
      }),
    );
  });

  it("speaks of one application in the singular", async () => {
    start({
      pipeline: aPipeline(
        { REJECTED: 1 },
        { applied: 1, responded: 1, interviewRate: 0, offerRate: null, responseRate: 1 },
      ),
    });
    const funnel = await widget("Funnel");
    expect(await within(funnel).findByText("Response rate: 1 of 1 application got an answer.")).toBeVisible();
  });
});

describe("tasks", () => {
  it("lists overdue and upcoming tasks in the viewer's zone, the first five of each", async () => {
    const overdue = aTask({
      title: "Send the thank-you note",
      timing: { span: "DAY", startsOn: "2026-09-01" },
    });
    const upcoming = Array.from({ length: 6 }, (_, index) => aTask({ title: `Prepare call ${index + 1}` }));
    const { state } = start({ tasks: { overdue: [overdue], upcoming } });
    const tasks = await widget("Tasks");

    const late = await within(tasks).findByRole("list", { name: "Overdue (1)" });
    expect(within(late).getByText("Send the thank-you note")).toBeVisible();
    expect(within(late).getByText("Due Sep 1, 2026")).toBeVisible();
    const soon = within(tasks).getByRole("list", { name: "Next 7 days (6)" });
    expect(within(soon).getAllByRole("listitem")).toHaveLength(5);
    expect(within(soon).queryByText("Prepare call 6")).toBeNull();
    expect(within(tasks).getByText("And 1 more.")).toBeVisible();
    expect(within(tasks).getByRole("link", { name: "All tasks" })).toHaveAttribute("href", "/tasks");
    expect(state.taskZones[0]).toBe(viewerTimeZone());
  });

  it("leaves out an empty list", async () => {
    start({ tasks: { overdue: [], upcoming: [aTask({ title: "Call Anna" })] } });
    const tasks = await widget("Tasks");
    expect(await within(tasks).findByText("Call Anna")).toBeVisible();
    expect(within(tasks).queryByRole("list", { name: /^Overdue/ })).toBeNull();
  });
});

describe("recent activity", () => {
  it("says what happened, by whom and when, and links an application to its timeline", async () => {
    const applicationId = crypto.randomUUID();
    const { state } = start({
      activity: [
        anActivityEntry({
          id: 2,
          description: "Changed application status",
          actor: { kind: "AI", name: null },
          application: { id: applicationId, title: "Platform Engineer" },
        }),
        anActivityEntry({ id: 1, entityType: "task", description: "Dismissed suggestion" }),
      ],
    });
    const activity = await widget("Recent activity");
    const items = await within(activity).findAllByRole("listitem");
    expect(items).toHaveLength(2);
    expect(items[0]).toHaveTextContent("Status changed");
    expect(items[0]).toHaveTextContent("by the AI");
    expect(within(items[0] as HTMLElement).getByRole("link", { name: "Platform Engineer" })).toHaveAttribute(
      "href",
      `/applications/${applicationId}?tab=timeline`,
    );
    expect(items[1]).toHaveTextContent("Suggestion dismissed");
    expect(items[1]).toHaveTextContent("by you");
    expect(within(items[1] as HTMLElement).queryByRole("link")).toBeNull();
    expect(within(activity).getAllByText((_, element) => element?.tagName === "TIME")[0]).toHaveAttribute(
      "datetime",
      "2026-09-30T10:00:00Z",
    );
    expect(state.activityLimits).toEqual(["10"]);
  });
});

describe("AI cost", () => {
  it("shows this month's cost against the budget and links to the budget in Settings", async () => {
    start({ costs: aCostSummary(2_500_000, 10_000_000, 2) });
    const cost = await widget("AI cost this month");
    expect(await within(cost).findByText("$2.50")).toBeVisible();
    expect(within(cost).getByText("of $10.00, $7.50 left")).toBeVisible();
    expect(within(cost).getByText("Plus calls with unknown cost: 2.")).toBeVisible();
    expect(within(cost).queryByRole("note")).toBeNull();
    expect(within(cost).getByRole("link", { name: "AI budget in Settings" })).toHaveAttribute(
      "href",
      "/settings#ai-budget-heading",
    );
  });

  it("warns when the budget is reached", async () => {
    start({ costs: aCostSummary(10_000_000, 10_000_000) });
    const cost = await widget("AI cost this month");
    const warning = await within(cost).findByRole("note");
    expect(warning).toHaveTextContent("Budget reached: AI jobs that can wait are paused.");
    expect(warning).toHaveTextContent(/Paused until Nov 1, 2026/);
  });
});

describe("a figure that fails", () => {
  it("shows the failure in its own widget with a retry, while the others load", async () => {
    const { state, user } = start({
      unavailable: new Set(["costs"]),
      pipeline: aPipeline({ APPLIED: 2 }),
    });
    const cost = await widget("AI cost this month");
    const alert = await within(cost).findByRole("alert", {}, { timeout: 6000 });
    expect(alert).toHaveTextContent("The AI cost could not be loaded.");
    expect(within(await widget("Pipeline")).getByRole("link", { name: "Applied" })).toBeVisible();
    expect(screen.getAllByRole("alert")).toHaveLength(1);

    state.unavailable.clear();
    await user.click(within(alert).getByRole("button", { name: "Try again" }));
    expect(await within(cost).findByText("$0.00")).toBeVisible();
    expect(screen.queryByRole("alert")).toBeNull();
  });
});
