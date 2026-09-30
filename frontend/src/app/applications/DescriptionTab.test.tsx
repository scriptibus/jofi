// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { createMemoryHistory } from "@tanstack/react-router";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { setupServer } from "msw/node";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import type { ApplicationResponse, ApplicationSourceResponse } from "../../api/generated/jofi";
import { anApplication, fakeApplicationBackend } from "../../test/fakeApplicationBackend";
import { fakeAuthBackend } from "../../test/fakeAuthBackend";
import { aCompany, fakeCompanyBackend } from "../../test/fakeCompanyBackend";
import {
  aSnapshot,
  type FakeDescriptionState,
  fakeDescriptionBackend,
} from "../../test/fakeDescriptionBackend";
import { App, createApp } from "../App";

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const acme = aCompany({ name: "ACME GmbH" });

function aSource(overrides: Partial<ApplicationSourceResponse> = {}): ApplicationSourceResponse {
  return {
    id: crypto.randomUUID(),
    kind: "URL",
    originalUrl: "https://jobs.example.com/postings/42",
    discoveredAt: "2026-09-01T08:00:00Z",
    online: true,
    offlineSince: null,
    ...overrides,
  };
}

function start(
  application: ApplicationResponse,
  data: Partial<FakeDescriptionState> = {},
  tab = "description",
) {
  const auth = fakeAuthBackend({ authenticated: true });
  const applications = fakeApplicationBackend({ applications: [application] });
  const descriptions = fakeDescriptionBackend(data);
  const companies = fakeCompanyBackend({ companies: [acme] });
  server.use(...applications.handlers, ...descriptions.handlers, ...companies.handlers, ...auth.handlers);
  const path = `/applications/${application.id}${tab === "overview" ? "" : `?tab=${tab}`}`;
  render(<App app={createApp(createMemoryHistory({ initialEntries: [path] }))} />);
  return { state: descriptions.state, user: userEvent.setup() };
}

const versionsCard = () => screen.getByRole("region", { name: "Versions" });
/** The page has loaded the versions once the text of one shows. */
const loaded = () => screen.findByRole("region", { name: /^Text of version/ });
const textCard = () => screen.getByRole("region", { name: /^Text of version/ });
const compareCard = () => screen.getByRole("region", { name: "Compare versions" });

describe("description tab", () => {
  it("explains that there is nothing to show without sources", async () => {
    start(anApplication(acme.id));
    expect(await screen.findByRole("heading", { name: "No job description yet" })).toBeVisible();
    expect(screen.getByRole("tab", { name: "Description" })).toHaveAttribute("aria-selected", "true");
  });

  it("lists the versions newest first with date, reason, length and the frozen badge", async () => {
    const source = aSource();
    const first = aSnapshot(source.id, "First text", {
      reason: "DISCOVERY",
      capturedAt: "2026-09-01T08:00:00Z",
    });
    const frozen = aSnapshot(source.id, "Applied text", { frozenAt: "2026-09-10T08:00:00Z" });
    const latest = aSnapshot(source.id, "Latest text", { reason: "CHANGE_DETECTED" });
    start(anApplication(acme.id, { sources: [source] }), { snapshots: [first, frozen, latest] });

    const radios = await within(await screen.findByRole("radiogroup", { name: "Versions" })).findAllByRole(
      "radio",
    );
    expect(radios.map((radio) => radio.closest("label")?.textContent)).toEqual([
      expect.stringMatching(/^Version 3.*Change detected · 11 characters$/),
      expect.stringMatching(/^Version 2.*Recorded by hand · 12 characters.*Frozen when you applied$/),
      expect.stringMatching(/^Version 1Sep 1, 2026.*Saved when found · 10 characters$/),
    ]);
    expect(radios[0]).toBeChecked();
    expect(within(versionsCard()).getAllByText("Frozen when you applied")).toHaveLength(1);
    expect(await within(textCard()).findByText("Latest text")).toBeVisible();
    expect(screen.getByText("Added from a link")).toBeVisible();
  });

  it("shows posting text as plain text with its line breaks, never as HTML or Markdown", async () => {
    const source = aSource();
    const text =
      "Intro **bold**\n<script>window.__descriptionXss = true</script>\n<img src=x onerror=alert(1)>";
    const application = anApplication(acme.id, {
      sources: [source],
      languageAndTone: { postingLanguage: "de" },
    });
    start(application, { snapshots: [aSnapshot(source.id, text)] });

    const shown = await within(await screen.findByRole("region", { name: "Text of version 1" })).findByText(
      /Intro \*\*bold\*\*/,
    );
    expect(shown.textContent).toBe(text);
    expect(shown).toHaveAttribute("lang", "de");
    expect(shown).toHaveClass("whitespace-pre-wrap");
    expect(shown.querySelector("script, img, strong")).toBeNull();
    expect((window as { __descriptionXss?: boolean }).__descriptionXss).toBeUndefined();
  });

  it("shows another version's text when chosen", async () => {
    const source = aSource();
    const snapshots = [aSnapshot(source.id, "Old text"), aSnapshot(source.id, "New text")];
    const { user } = start(anApplication(acme.id, { sources: [source] }), { snapshots });
    await within(await screen.findByRole("region", { name: "Text of version 2" })).findByText("New text");
    await user.click(within(versionsCard()).getByRole("radio", { name: /^Version 1/ }));
    expect(await within(textCard()).findByText("Old text")).toBeVisible();
    expect(textCard()).toHaveAccessibleName("Text of version 1");
  });

  it("records a new text as a new version and says when a text is unchanged", async () => {
    const source = aSource();
    const { state, user } = start(anApplication(acme.id, { sources: [source] }), {
      snapshots: [aSnapshot(source.id, "Line one\nLine two")],
    });
    await user.click(await screen.findByRole("button", { name: "Record the current text" }));
    const field = screen.getByLabelText("Current text of the posting");
    const record = screen.getByRole("button", { name: "Record text" });
    expect(record).toBeDisabled();

    await user.click(field);
    await user.paste("Line one\nLine two, changed");
    await user.click(record);
    expect(await screen.findByRole("status")).toHaveTextContent("New version saved: version 2.");
    expect(field).toHaveValue("");
    expect(
      await within(textCard()).findByText("Line one Line two, changed", {
        normalizer: (t) => t.replace(/\n/g, " "),
      }),
    ).toBeVisible();
    expect(within(versionsCard()).getByRole("radio", { name: /^Version 2/ })).toBeChecked();

    await user.click(field);
    await user.paste("Line one\nLine two, changed");
    await user.click(record);
    expect(await screen.findByText(/^Unchanged: this text matches the latest version/)).toBeVisible();
    expect(within(versionsCard()).getAllByRole("radio")).toHaveLength(2);
    expect(state.recorded).toEqual(["Line one\nLine two, changed", "Line one\nLine two, changed"]);
  });

  it("shows the server's reason when recording is refused", async () => {
    const source = aSource();
    const { user } = start(anApplication(acme.id, { sources: [source] }));
    expect(await screen.findByText(/No version of this source is saved yet/)).toBeVisible();
    await user.click(screen.getByRole("button", { name: "Record the current text" }));
    await user.click(screen.getByLabelText("Current text of the posting"));
    await user.paste("   ");
    // Only blanks: the button stays disabled, so nothing is sent.
    expect(screen.getByRole("button", { name: "Record text" })).toBeDisabled();
  });

  it("compares the frozen version with the latest by default, marking lines in words and signs", async () => {
    const source = aSource();
    const snapshots = [
      aSnapshot(source.id, "Title\nOld duty\nFooter", { frozenAt: "2026-09-10T08:00:00Z" }),
      aSnapshot(source.id, "Title\nMiddle duty\nFooter"),
      aSnapshot(source.id, "Title\nNew duty\nFooter"),
    ];
    const { state } = start(anApplication(acme.id, { sources: [source] }), { snapshots });
    await loaded();
    const diff = await within(compareCard()).findByRole("list", {
      name: "Changes from Version 1 to Version 3",
    });
    expect(state.diffs).toEqual([`${snapshots[0]?.id}→${snapshots[2]?.id}`]);
    expect(within(compareCard()).getByText("Lines added: 1 · lines removed: 1")).toBeVisible();
    const lines = within(diff).getAllByRole("listitem");
    expect(lines.map((line) => line.textContent)).toEqual([
      " Title",
      "−Removed: Old duty",
      "+Added: New duty",
      " Footer",
    ]);
    expect(within(diff).getByText("Old duty").closest("del")).not.toBeNull();
    expect(within(diff).getByText("New duty").closest("ins")).not.toBeNull();
  });

  it("compares the previous version with the latest when nothing is frozen, and lets the user choose", async () => {
    const source = aSource();
    const snapshots = [
      aSnapshot(source.id, "One"),
      aSnapshot(source.id, "Two"),
      aSnapshot(source.id, "Three"),
    ];
    const { user } = start(anApplication(acme.id, { sources: [source] }), { snapshots });
    await loaded();
    await within(compareCard()).findByRole("list", { name: "Changes from Version 2 to Version 3" });

    await user.click(within(compareCard()).getByRole("button", { name: /Before$/ }));
    await user.click(await screen.findByRole("option", { name: /^Version 1,/ }));
    const diff = await within(compareCard()).findByRole("list", {
      name: "Changes from Version 1 to Version 3",
    });
    expect(within(diff).getByText("One")).toBeVisible();

    await user.click(within(compareCard()).getByRole("button", { name: /After$/ }));
    await user.click(await screen.findByRole("option", { name: /^Version 1,/ }));
    expect(await within(compareCard()).findByText("Choose two different versions.")).toBeVisible();
  });

  it("folds long unchanged stretches behind a button", async () => {
    const source = aSource();
    const body = Array.from({ length: 30 }, (_, index) => `Same ${index + 1}`).join("\n");
    const snapshots = [
      aSnapshot(source.id, `Old start\n${body}`),
      aSnapshot(source.id, `New start\n${body}`),
    ];
    const { user } = start(anApplication(acme.id, { sources: [source] }), { snapshots });
    await loaded();
    const diff = await within(compareCard()).findByRole("list", { name: /^Changes from/ });
    expect(within(diff).queryByText("Same 20")).toBeNull();
    await user.click(within(diff).getByRole("button", { name: "Show 27 unchanged lines" }));
    expect(within(diff).getByText("Same 20")).toBeVisible();
    expect(within(diff).queryByRole("button")).toBeNull();
  });

  it("asks for a second version before comparing", async () => {
    const source = aSource();
    start(anApplication(acme.id, { sources: [source] }), { snapshots: [aSnapshot(source.id, "Only one")] });
    await loaded();
    expect(await within(compareCard()).findByText(/Once there are two versions/)).toBeVisible();
  });

  it("switches between sources, shows an offline one's date and compares across sources", async () => {
    const link = aSource();
    const scanner = aSource({
      kind: "SCANNER",
      originalUrl: "https://board.example.org/p/1",
      online: false,
      offlineSince: "2026-09-20T08:00:00Z",
    });
    const snapshots = [aSnapshot(link.id, "From the link"), aSnapshot(scanner.id, "From the scanner")];
    const { user } = start(anApplication(acme.id, { sources: [link, scanner] }), { snapshots });
    await loaded();
    expect(await within(textCard()).findByText("From the link")).toBeVisible();
    expect(screen.queryByText(/offline since/)).toBeNull();

    await user.click(screen.getByRole("button", { name: /Source shown$/ }));
    await user.click(await screen.findByRole("option", { name: "Found by a scanner (board.example.org)" }));
    expect(await within(textCard()).findByText("From the scanner")).toBeVisible();
    expect(screen.getByText("offline since Sep 20, 2026")).toBeVisible();

    await user.click(within(compareCard()).getByRole("button", { name: /Before$/ }));
    const options = await screen.findByRole("listbox", { name: "Before" });
    expect(within(options).getByText("Added from a link (jobs.example.com)")).toBeVisible();
    await user.click(within(options).getAllByRole("option", { name: /^Version 1,/ })[0] as HTMLElement);
    await user.click(within(compareCard()).getByRole("button", { name: /After$/ }));
    await user.click(
      within(await screen.findByRole("listbox", { name: "After" })).getAllByRole("option", {
        name: /^Version 1,/,
      })[1] as HTMLElement,
    );
    expect(
      await within(compareCard()).findByRole("list", {
        name: "Changes from Version 1 (Added from a link (jobs.example.com)) to Version 1 (Found by a scanner (board.example.org))",
      }),
    ).toBeVisible();
  });

  it("shows the frozen badge once the application moves to Applied", async () => {
    const source = aSource();
    const snapshots = [aSnapshot(source.id, "The text")];
    const application = anApplication(acme.id, { sources: [source], status: "PREPARING" });
    const { state, user } = start(application, { snapshots }, "overview");
    await user.click(await screen.findByRole("tab", { name: "Description" }));
    await within(await screen.findByRole("region", { name: "Text of version 1" })).findByText("The text");
    expect(screen.queryByText("Frozen when you applied")).toBeNull();

    await user.click(screen.getByRole("tab", { name: "Overview" }));
    const status = await screen.findByRole("region", { name: "Status" });
    await user.click(within(status).getByRole("button", { name: /Change status$/ }));
    await user.click(await screen.findByRole("option", { name: "Applied" }));
    // The server freezes the latest version with the move.
    state.snapshots = state.snapshots.map((snapshot) => ({ ...snapshot, frozenAt: "2026-10-01T09:00:00Z" }));
    await user.click(
      within(await screen.findByRole("dialog")).getByRole("button", { name: "Change status" }),
    );
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());

    await user.click(screen.getByRole("tab", { name: "Description" }));
    expect(await within(versionsCard()).findByText("Frozen when you applied")).toBeVisible();
  });
});
