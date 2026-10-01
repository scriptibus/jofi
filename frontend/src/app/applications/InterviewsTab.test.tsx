// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { createMemoryHistory } from "@tanstack/react-router";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent, { type UserEvent } from "@testing-library/user-event";
import { setupServer } from "msw/node";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import type { ContactResponse, InterviewResponse } from "../../api/generated/jofi";
import { anApplication, fakeApplicationBackend } from "../../test/fakeApplicationBackend";
import { fakeAuthBackend } from "../../test/fakeAuthBackend";
import { aCompany, aContact, fakeCompanyBackend } from "../../test/fakeCompanyBackend";
import { anInterview, fakeInterviewBackend } from "../../test/fakeInterviewBackend";
import { fakeTimelineBackend } from "../../test/fakeTimelineBackend";
import { App, createApp } from "../App";
import { formatInstant, formatLocalDateTime } from "./format";
import { deviceTimeZone } from "./interviews";

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const acme = aCompany({ name: "ACME GmbH" });
const ada = aContact(acme.id, { name: "Ada Lovelace", role: "Recruiter" });
const alan = aContact(acme.id, { name: "Alan Turing", role: "Hiring manager" });
const application = anApplication(acme.id, { title: "Backend Engineer" });

interface Setup {
  interviews?: InterviewResponse[];
  contacts?: ContactResponse[];
  listFails?: boolean;
  tab?: string;
}

async function start({
  interviews = [],
  contacts = [ada, alan],
  listFails = false,
  tab = "interviews",
}: Setup) {
  const auth = fakeAuthBackend({ authenticated: true });
  const companies = fakeCompanyBackend({ companies: [acme], contacts: [...contacts] });
  const applications = fakeApplicationBackend({ applications: [application] });
  const timeline = fakeTimelineBackend();
  const backend = fakeInterviewBackend({
    interviews: [...interviews],
    listFails,
    timeline: timeline.state,
    knownContactIds: () => companies.state.contacts.map((contact) => contact.id),
  });
  server.use(
    ...backend.handlers,
    ...timeline.handlers,
    ...applications.handlers,
    ...companies.handlers,
    ...auth.handlers,
  );
  const app = createApp(
    createMemoryHistory({ initialEntries: [`/applications/${application.id}?tab=${tab}`] }),
  );
  render(<App app={app} />);
  if (tab === "interviews") await screen.findByRole("region", { name: "Interviews and calls" });
  return {
    state: backend.state,
    companies: companies.state,
    timeline: timeline.state,
    user: userEvent.setup(),
  };
}

const section = () => screen.getByRole("region", { name: "Interviews and calls" });
const form = (name: RegExp | string) => screen.getByRole("region", { name });

/** Types a wall-clock time into the date and time segments (en-US: month, day, year, hour, minute, AM/PM). */
async function typeStart(user: UserEvent, where: HTMLElement, keys: string) {
  await user.click(within(where).getByRole("spinbutton", { name: /^month/i }));
  await user.keyboard(keys);
}

async function chooseOption(user: UserEvent, where: HTMLElement, field: RegExp, option: string) {
  await user.click(within(where).getByRole("button", { name: field }));
  await user.click(await screen.findByRole("option", { name: option }));
}

describe("Interviews tab", () => {
  it("says when nothing is logged yet", async () => {
    await start({});
    expect(await screen.findByRole("tab", { name: "Interviews" })).toHaveAttribute("aria-selected", "true");
    expect(await within(section()).findByText(/No interviews or calls logged yet/)).toBeVisible();
  });

  it("shows a failed load with a retry", async () => {
    const { state, user } = await start({ listFails: true });
    expect(await within(section()).findByText("The interviews could not be loaded.")).toBeVisible();
    state.listFails = false;
    await user.click(within(section()).getByRole("button", { name: "Try again" }));
    expect(await within(section()).findByText(/No interviews or calls logged yet/)).toBeVisible();
  });

  it("lists interviews in start order at the agreed time and zone, with participants and outcome", async () => {
    const later = anInterview(application.id, {
      type: "TECHNICAL",
      localStart: "2026-10-12T09:30",
      timeZone: "Pacific/Kiritimati",
      participantIds: [ada.id, alan.id],
      outcome: "PASSED",
      notes: "Went **well**",
    });
    const sooner = anInterview(application.id, {
      localStart: "2026-10-05T10:00",
      timeZone: deviceTimeZone(),
    });
    await start({ interviews: [later, sooner] });

    const list = await within(section()).findByRole("list", { name: "Interviews and calls" });
    const items = within(list).getAllByRole("article");
    expect(items.map((item) => within(item).getByRole("heading").textContent)).toEqual([
      "Phone screen",
      "Technical interview",
    ]);
    const [first, second] = items as [HTMLElement, HTMLElement];
    expect(
      within(first).getByText(`${formatLocalDateTime("2026-10-05T10:00")} (${deviceTimeZone()})`),
    ).toBeVisible();
    // Agreed in the user's own zone: no second time.
    expect(within(first).queryByText(/^Your time/)).toBeNull();
    expect(within(first).getByText("Not decided yet")).toBeVisible();

    // Agreed far away: the agreed time stays, the user's own clock comes second.
    expect(
      within(second).getByText(`${formatLocalDateTime("2026-10-12T09:30")} (Pacific/Kiritimati)`),
    ).toBeVisible();
    expect(within(second).getByText(`Your time: ${formatInstant(later.startsAt)}`)).toBeVisible();
    expect(within(second).getByText("Passed")).toBeVisible();
    expect(await within(second).findByText("Ada Lovelace, Alan Turing")).toBeVisible();
    expect(within(second).getByText("well").tagName).toBe("STRONG");
  });

  it("picks participants from the contacts, without one already chosen and without leaving the form", async () => {
    const { user } = await start({});
    await user.click(within(section()).getByRole("button", { name: "Log an interview or call" }));
    const log = form("Log an interview or call");
    expect(within(log).getByText("No participants.")).toBeVisible();
    await user.click(within(log).getByRole("button", { name: "Add a participant" }));
    const picker = await screen.findByRole("dialog", { name: "Add a participant" });
    expect(within(picker).queryByRole("link", { name: "Create a new contact" })).toBeNull();
    await user.click(await within(picker).findByRole("button", { name: "Add Alan Turing" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(await within(log).findByRole("button", { name: "Remove Alan Turing" })).toBeVisible();

    await user.click(within(log).getByRole("button", { name: "Add a participant" }));
    const again = await screen.findByRole("dialog", { name: "Add a participant" });
    expect(await within(again).findByRole("button", { name: "Add Ada Lovelace" })).toBeVisible();
    expect(within(again).queryByRole("button", { name: "Add Alan Turing" })).toBeNull();
    await user.click(within(again).getByRole("button", { name: "Close" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());

    await user.click(within(log).getByRole("button", { name: "Remove Alan Turing" }));
    expect(within(log).getByText("No participants.")).toBeVisible();
  });

  // Choosing a zone renders the whole list of zones, which is slow in jsdom.
  it("logs an interview in the chosen zone and refreshes the timeline", { timeout: 20_000 }, async () => {
    const { state, user } = await start({ tab: "timeline" });
    expect(await screen.findByText("Nothing has happened yet")).toBeVisible();
    await user.click(screen.getByRole("tab", { name: "Interviews" }));
    await user.click(await within(section()).findByRole("button", { name: "Log an interview or call" }));
    const log = form("Log an interview or call");

    expect(
      within(log).getByRole("button", { name: new RegExp(`${deviceTimeZone()}.*Time zone`) }),
    ).toBeVisible();
    await chooseOption(user, log, /Type/, "Technical interview");
    await typeStart(user, log, "10052026" + "0230P");
    await chooseOption(user, log, /Time zone/, "America/New_York");

    await user.click(within(log).getByRole("button", { name: "Add a participant" }));
    const picker = await screen.findByRole("dialog", { name: "Add a participant" });
    await user.click(await within(picker).findByRole("button", { name: "Add Alan Turing" }));
    expect(await within(log).findByRole("button", { name: "Remove Alan Turing" })).toBeVisible();

    await user.type(within(log).getByRole("textbox", { name: "Preparation notes" }), "Read the posting");
    await user.click(within(log).getByRole("button", { name: "Log it" }));

    expect(await within(section()).findByText("The interview was logged.")).toBeVisible();
    expect(state.logs).toEqual([
      {
        type: "TECHNICAL",
        localStart: "2026-10-05T14:30",
        timeZone: "America/New_York",
        participantIds: [alan.id],
        preparationNotes: "Read the posting",
        notes: null,
        outcome: null,
      },
    ]);
    expect(
      await within(section()).findByText(`${formatLocalDateTime("2026-10-05T14:30")} (America/New_York)`),
    ).toBeVisible();
    expect(screen.queryByRole("region", { name: "Log an interview or call" })).toBeNull();

    await user.click(screen.getByRole("tab", { name: "Timeline" }));
    const timeline = await screen.findByRole("list", { name: "Timeline" });
    expect(within(timeline).getByText("Technical interview")).toBeVisible();
  });

  it("asks for the start before sending and shows the server's field errors", {
    timeout: 15_000,
  }, async () => {
    const gone = aContact(acme.id, { name: "Grace Hopper" });
    const { state, companies, user } = await start({ contacts: [ada, gone] });
    await user.click(await within(section()).findByRole("button", { name: "Log an interview or call" }));
    const log = form("Log an interview or call");
    await user.click(within(log).getByRole("button", { name: "Log it" }));
    expect(await within(log).findByText("Enter the date and time.")).toBeVisible();
    expect(state.logs).toEqual([]);

    await typeStart(user, log, "10052150" + "1000A");
    await user.click(within(log).getByRole("button", { name: "Add a participant" }));
    await user.click(await screen.findByRole("button", { name: "Add Grace Hopper" }));
    companies.contacts = companies.contacts.filter((contact) => contact.id !== gone.id);
    await user.click(within(log).getByRole("button", { name: "Log it" }));
    expect(await within(log).findByText("Choose a time between the years 2000 and 2099.")).toBeVisible();

    await typeStart(user, log, "10052026" + "1000A");
    await user.click(within(log).getByRole("button", { name: "Log it" }));
    expect(await within(log).findByText(/A participant no longer exists/)).toBeVisible();
    expect(state.logs).toEqual([]);
  });

  it("edits the interview as opened: every field with the version it showed", async () => {
    const interview = anInterview(application.id, {
      localStart: "2026-10-05T10:00",
      timeZone: "Europe/Berlin",
      participantIds: [ada.id],
      preparationNotes: "Old notes",
      version: 2,
    });
    const { state, user } = await start({ interviews: [interview] });
    await user.click(
      await within(section()).findByRole("button", { name: /^Edit Phone screen on Oct 5, 2026/ }),
    );
    const edit = form("Edit: Phone screen");
    expect(within(edit).getByRole("group", { name: "Date and time" }).textContent).toMatch(
      /10\/5\/2026, .*10:00/,
    );
    expect(within(edit).getByRole("button", { name: /Europe\/Berlin.*Time zone/ })).toBeVisible();
    await user.click(await within(edit).findByRole("button", { name: "Remove Ada Lovelace" }));
    await chooseOption(user, edit, /Outcome/, "Passed");
    await user.type(within(edit).getByRole("textbox", { name: "Notes afterwards" }), "Next round");
    await user.click(within(edit).getByRole("button", { name: "Save changes" }));

    expect(await within(section()).findByText("The interview was saved.")).toBeVisible();
    expect(state.updates).toEqual([
      {
        basedOnVersion: 2,
        details: {
          type: "PHONE_SCREEN",
          localStart: "2026-10-05T10:00",
          timeZone: "Europe/Berlin",
          participantIds: [],
          preparationNotes: "Old notes",
          notes: "Next round",
          outcome: "PASSED",
        },
      },
    ]);
  });

  it("says when the interview changed meanwhile and loads the latest version", async () => {
    const interview = anInterview(application.id, { version: 1 });
    const { state, user } = await start({ interviews: [interview] });
    await user.click(await within(section()).findByRole("button", { name: /^Edit Phone screen/ }));
    state.interviews = [{ ...interview, notes: "Changed in another tab", version: 2 }];
    const edit = form("Edit: Phone screen");
    await user.type(within(edit).getByRole("textbox", { name: "Notes afterwards" }), "Mine");
    await user.click(within(edit).getByRole("button", { name: "Save changes" }));

    expect(await within(edit).findByText("Changed meanwhile")).toBeVisible();
    expect(state.updates).toEqual([]);
    await user.click(within(edit).getByRole("button", { name: "Load latest version" }));
    await waitFor(() =>
      expect(
        within(form("Edit: Phone screen")).getByRole("textbox", { name: "Notes afterwards" }),
      ).toHaveValue("Changed in another tab"),
    );
    await user.click(within(form("Edit: Phone screen")).getByRole("button", { name: "Save changes" }));
    expect(await within(section()).findByText("The interview was saved.")).toBeVisible();
    expect(state.updates.map((update) => update.basedOnVersion)).toEqual([2]);
  });

  it("deletes only after the server's question is confirmed; cancel keeps it", async () => {
    const interview = anInterview(application.id, {
      localStart: "2026-10-06T14:30",
      timeZone: "Europe/Berlin",
    });
    const { state, user } = await start({ interviews: [interview] });
    const remove = await within(section()).findByRole("button", {
      name: /^Delete Phone screen on Oct 6, 2026/,
    });

    await user.click(remove);
    const question = await screen.findByRole("alertdialog", { name: "Delete this interview?" });
    expect(question).toHaveTextContent(
      `Phone screen on ${formatLocalDateTime("2026-10-06T14:30")} (Europe/Berlin) will be deleted with its notes.`,
    );
    await user.click(within(question).getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
    expect(state.deleteCalls).toEqual(["first"]);
    expect(within(section()).getByRole("heading", { name: "Phone screen" })).toBeVisible();

    await user.click(remove);
    const again = await screen.findByRole("alertdialog", { name: "Delete this interview?" });
    const confirm = within(again).getByRole("button", { name: "Delete" });
    await waitFor(() => expect(confirm).toBeEnabled());
    await user.click(confirm);
    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
    expect(await within(section()).findByText("The interview was deleted.")).toBeVisible();
    expect(state.deleteCalls).toEqual(["first", "first", "confirmed"]);
    expect(await within(section()).findByText(/No interviews or calls logged yet/)).toBeVisible();
  });

  it("says when the interview to delete is gone already", async () => {
    const interview = anInterview(application.id);
    const { state, user } = await start({ interviews: [interview] });
    const remove = await within(section()).findByRole("button", { name: /^Delete Phone screen/ });
    state.interviews = [];
    await user.click(remove);
    expect(await within(section()).findByText(/This interview no longer exists/)).toBeVisible();
    expect(await within(section()).findByText(/No interviews or calls logged yet/)).toBeVisible();
  });
});
