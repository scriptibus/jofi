// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { createMemoryHistory } from "@tanstack/react-router";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { setupServer } from "msw/node";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import type { ApplicationResponse, ChangeActorDto } from "../../api/generated/jofi";
import { anApplication, fakeApplicationBackend } from "../../test/fakeApplicationBackend";
import { fakeAuthBackend } from "../../test/fakeAuthBackend";
import { aCompany, fakeCompanyBackend } from "../../test/fakeCompanyBackend";
import { anEntry, type FakeTimelineState, fakeTimelineBackend, user } from "../../test/fakeTimelineBackend";
import { App, createApp } from "../App";

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const acme = aCompany({ name: "ACME GmbH" });

function start(application: ApplicationResponse, data: Partial<FakeTimelineState> = {}) {
  const auth = fakeAuthBackend({ authenticated: true });
  const applications = fakeApplicationBackend({ applications: [application] });
  const timeline = fakeTimelineBackend(data);
  const companies = fakeCompanyBackend({ companies: [acme] });
  server.use(...applications.handlers, ...timeline.handlers, ...companies.handlers, ...auth.handlers);
  const path = `/applications/${application.id}?tab=timeline`;
  render(<App app={createApp(createMemoryHistory({ initialEntries: [path] }))} />);
  return { state: timeline.state, user: userEvent.setup() };
}

const timelineList = async () => within(await screen.findByRole("list", { name: "Timeline" }));
/** The entries, each as "title | details | actor and time", in the order shown. */
const items = async () =>
  (await timelineList()).getAllByRole("listitem").filter((item) => item.parentElement?.tagName === "OL");

const statusMove = (occurredAt: string, actor: ChangeActorDto = user) =>
  anEntry(occurredAt, {
    kind: "STATUS_CHANGE",
    statusChange: { actor, from: "DISCOVERED", to: "APPLIED", declineCategory: null },
  });

describe("timeline tab", () => {
  it("shows every kind of entry: changes, status moves, descriptions, interviews and tasks", async () => {
    start(anApplication(acme.id), {
      entries: [
        anEntry("2026-09-01T08:00:00Z", {
          kind: "CHANGE",
          change: {
            actor: user,
            fields: [
              { field: "title", before: null, after: null },
              { field: "seniority", before: "JUNIOR", after: "SENIOR" },
              { field: "remoteShare", before: null, after: "40" },
            ],
          },
        }),
        anEntry("2026-09-02T08:00:00Z", {
          kind: "STATUS_CHANGE",
          statusChange: { actor: user, from: "OFFER", to: "DECLINED", declineCategory: "SALARY" },
        }),
        anEntry("2026-09-03T08:00:00Z", {
          kind: "DESCRIPTION_SNAPSHOT",
          descriptionSnapshot: {
            sourceId: crypto.randomUUID(),
            reason: "MANUAL",
            frozenAt: "2026-09-03T08:00:00Z",
          },
        }),
        anEntry("2026-10-05T08:00:00Z", {
          kind: "INTERVIEW",
          interview: {
            type: "PHONE_SCREEN",
            localStart: "2026-10-05T10:00:00",
            timeZone: "Europe/Berlin",
            outcome: "PASSED",
          },
        }),
        anEntry("2026-09-04T08:00:00Z", {
          kind: "TASK",
          task: { title: "Send the <b>portfolio</b>", completedAt: null },
        }),
      ],
    });
    const [interview, task, snapshot, status, change] = await items();

    expect(interview).toHaveTextContent("Interview");
    expect(interview).toHaveTextContent("Phone screen · Outcome: passed");
    // The agreed time in the zone it was planned in, whatever the browser's zone (ADR-0048).
    expect(
      within(interview as HTMLElement).getByText(/^Oct 5, 2026, 10:00\sAM \(Europe\/Berlin\)$/),
    ).toHaveAttribute("datetime", "2026-10-05T08:00:00Z");
    expect(task).toHaveTextContent("Task");
    expect(within(task as HTMLElement).getByText("Send the <b>portfolio</b>")).toBeVisible();
    expect(task).toHaveTextContent("Open");
    expect(snapshot).toHaveTextContent("Job description saved");
    expect(snapshot).toHaveTextContent("Recorded by hand");
    expect(snapshot).toHaveTextContent("Frozen when you applied");
    expect(status).toHaveTextContent("Status changed");
    expect(
      within(status as HTMLElement)
        .getAllByText(/^(Offer|to|Declined)$/)
        .map((part) => part.textContent),
    ).toEqual(["Offer", "to", "Declined"]);
    expect(status).toHaveTextContent("Reason: Salary");
    expect(change).toHaveTextContent("Details changed");
    const fields = within(change as HTMLElement).getAllByRole("listitem");
    expect(fields.map((field) => field.textContent)).toEqual([
      "Job title",
      "Seniority: Junior → to Senior",
      "Remote share: none → to 40%",
    ]);
  });

  it("badges who made each change with words and an icon", async () => {
    const actors: ChangeActorDto[] = [
      { kind: "USER", name: null },
      { kind: "AI", name: null },
      { kind: "SCANNER", name: "Bundesagentur" },
      { kind: "EXTERNAL_CLIENT", name: "Claude Desktop" },
      { kind: "SYSTEM", name: null },
    ];
    start(anApplication(acme.id), {
      entries: actors.map((actor, index) => statusMove(`2026-09-0${5 - index}T08:00:00Z`, actor)),
    });
    const badges = (await items()).map((item) => within(item).getByText(/^By:/).parentElement);
    expect(badges.map((badge) => badge?.textContent)).toEqual([
      "By: User",
      "By: AI",
      "By: Scanner (Bundesagentur)",
      "By: External client (Claude Desktop)",
      "By: System",
    ]);
    for (const badge of badges) expect(badge?.querySelector("svg")).toHaveAttribute("aria-hidden", "true");
  });

  it("lists newest first and loads older entries a page at a time", async () => {
    const { user: person } = start(anApplication(acme.id), {
      pageSize: 2,
      entries: ["2026-09-01", "2026-09-03", "2026-09-02"].map((day) => statusMove(`${day}T08:00:00Z`)),
    });
    const times = async () =>
      (await items()).map((item) => item.querySelector("time")?.getAttribute("datetime"));
    expect(await times()).toEqual(["2026-09-03T08:00:00Z", "2026-09-02T08:00:00Z"]);
    expect(screen.getByText("Newest first.")).toBeVisible();

    await person.click(screen.getByRole("button", { name: "Show older entries" }));
    await screen.findByText((_, element) => element?.getAttribute("datetime") === "2026-09-01T08:00:00Z");
    expect(await times()).toEqual(["2026-09-03T08:00:00Z", "2026-09-02T08:00:00Z", "2026-09-01T08:00:00Z"]);
    expect(screen.queryByRole("button", { name: "Show older entries" })).not.toBeInTheDocument();
  });

  it("says when there is nothing yet", async () => {
    start(anApplication(acme.id));
    expect(await screen.findByRole("heading", { name: "Nothing has happened yet" })).toBeVisible();
    expect(screen.getByRole("tab", { name: "Timeline" })).toHaveAttribute("aria-selected", "true");
  });

  it("says when the timeline cannot be loaded and tries again", async () => {
    const { state, user: person } = start(anApplication(acme.id), {
      failing: "first",
      entries: [statusMove("2026-09-01T08:00:00Z")],
    });
    expect(await screen.findByText("The timeline could not be loaded.")).toBeVisible();
    state.failing = undefined;
    await person.click(screen.getByRole("button", { name: "Try again" }));
    expect(await timelineList()).toBeTruthy();
    expect(screen.queryByText("The timeline could not be loaded.")).not.toBeInTheDocument();
  });

  it("says when older entries cannot be loaded, keeping the ones shown", async () => {
    const { state, user: person } = start(anApplication(acme.id), {
      pageSize: 1,
      failing: "more",
      entries: [statusMove("2026-09-01T08:00:00Z"), statusMove("2026-09-02T08:00:00Z")],
    });
    await person.click(await screen.findByRole("button", { name: "Show older entries" }));
    expect(await screen.findByText("The older entries could not be loaded.")).toBeVisible();
    expect(await items()).toHaveLength(1);
    state.failing = undefined;
    await person.click(screen.getByRole("button", { name: "Try again" }));
    await screen.findByText((_, element) => element?.getAttribute("datetime") === "2026-09-01T08:00:00Z");
    expect(await items()).toHaveLength(2);
  });
});
