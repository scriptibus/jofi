// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { createMemoryHistory } from "@tanstack/react-router";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { setupServer } from "msw/node";
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { getLocale, overwriteGetLocale } from "../../../paraglide/runtime.js";
import { aListedApplication, fakeApplicationListBackend } from "../../../test/fakeApplicationListBackend";
import { fakeAuthBackend } from "../../../test/fakeAuthBackend";
import { aCompany, fakeCompanyBackend } from "../../../test/fakeCompanyBackend";
import { type FakeImportState, fakeImportBackend } from "../../../test/fakeImportBackend";
import { App, createApp } from "../../App";

// The real interval is 1.5 seconds; a poll every 20 ms keeps the tests quick without any fake timers.
vi.mock("./importModel", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./importModel")>()),
  POLL_INTERVAL_MS: 20,
}));

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const acme = aCompany({ name: "ACME GmbH" });
const APPLICATION_ID = "00000000-0000-4000-8000-0000000000aa";
const LINK = "https://jobs.example/postings/42";

function start(imports: Partial<FakeImportState> = {}) {
  const auth = fakeAuthBackend({ authenticated: true });
  const list = fakeApplicationListBackend({
    applications: [aListedApplication(acme.id, { title: "Backend Engineer", status: "APPLIED" })],
  });
  const companies = fakeCompanyBackend({ companies: [acme] });
  const fake = fakeImportBackend({
    outcome: { status: "SUCCEEDED", applicationId: APPLICATION_ID },
    ...imports,
  });
  server.use(...fake.handlers, ...list.handlers, ...companies.handlers, ...auth.handlers);
  const app = createApp(createMemoryHistory({ initialEntries: ["/applications"] }));
  render(<App app={app} />);
  return { ...fake, app, router: app.router, user: userEvent.setup() };
}

type User = ReturnType<typeof userEvent.setup>;

async function openDialog(user: User) {
  await user.click(await screen.findByRole("button", { name: "Import posting" }));
  return screen.findByRole("dialog", { name: "Import a job posting" });
}

async function importLink(user: User, link = LINK) {
  const dialog = await openDialog(user);
  await user.type(within(dialog).getByLabelText("Link to the posting"), link);
  await user.click(within(dialog).getByRole("button", { name: "Import" }));
  return dialog;
}

describe("Import dialog: a link", () => {
  it("opens with the link field focused and imports once the user presses Import", async () => {
    const { state, user } = start();
    const dialog = await openDialog(user);

    expect(within(dialog).getByLabelText("Link to the posting")).toHaveFocus();
    expect(state.started).toEqual([]);

    await user.type(within(dialog).getByLabelText("Link to the posting"), `  ${LINK} `);
    await user.click(within(dialog).getByRole("button", { name: "Import" }));

    expect(await within(dialog).findByText("Posting imported")).toBeVisible();
    expect(state.started).toEqual([{ kind: "url", body: { url: LINK } }]);
  });

  it("shows the running import, then links to the new application and refreshes the lists", async () => {
    const { app, state, user } = start({ pendingPolls: 3 });
    const refresh = vi.spyOn(app.queryClient, "invalidateQueries");
    const dialog = await importLink(user);

    expect(await within(dialog).findByText("Reading the posting…")).toBeVisible();
    expect(within(dialog).getByRole("status")).toHaveTextContent("You can close this window");

    const open = await within(dialog).findByRole("link", { name: "Open the application" });
    expect(open).toHaveAttribute("href", `/applications/${APPLICATION_ID}`);
    expect(state.reads.length).toBeGreaterThan(1);
    await waitFor(() => expect(refresh).toHaveBeenCalled());
    const keys = refresh.mock.calls.map(([filters]) => JSON.stringify(filters?.queryKey));
    expect(keys.some((key) => key?.includes("/api/applications"))).toBe(true);
  });

  it("moves focus to the status when the form gives way to it", async () => {
    const { user } = start({ pendingPolls: 5 });
    const dialog = await importLink(user);

    const status = await within(dialog).findByRole("region", { name: "Import a job posting" });
    expect(status).toHaveFocus();
  });

  it("says so when the link was imported before, and creates nothing new", async () => {
    const { user } = start({ knownLinks: { [LINK]: APPLICATION_ID } });
    const dialog = await importLink(user);

    expect(await within(dialog).findByText("Already imported")).toBeVisible();
    expect(within(dialog).getByRole("link", { name: "Open the application" })).toHaveAttribute(
      "href",
      `/applications/${APPLICATION_ID}`,
    );
  });

  it("disables the submit control while the request is on its way, so a double submit sends one request", async () => {
    let release = () => {};
    const startGate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const { state, user } = start({ startGate });
    const dialog = await openDialog(user);
    const field = within(dialog).getByLabelText("Link to the posting");
    await user.type(field, LINK);

    // Two Enter presses and two clicks before the first answer.
    await user.type(field, "{Enter}{Enter}");
    const submit = await within(dialog).findByRole("button", { name: "Importing…" });
    expect(submit).toBeDisabled();
    await user.dblClick(submit);
    release();

    expect(await within(dialog).findByText("Posting imported")).toBeVisible();
    expect(state.started).toHaveLength(1);
  });

  it.each([
    ["NOT_ALLOWED", /does not open links to LinkedIn, StepStone or Indeed.*paste it here instead/],
    ["UNREACHABLE", /could not reach that page/],
    ["TIMEOUT", /took too long to answer/],
    ["TOO_LARGE", /too large to read/],
    ["NOT_HTML", /does not lead to a web page/],
    ["LOGIN_REQUIRED", /behind a login/],
    ["NO_TEXT", /no readable text/],
  ])("explains %s and offers the text instead", async (code, message) => {
    const { state, user } = start({ refusedLinks: { [LINK]: code } });
    const dialog = await importLink(user);

    const alert = await within(dialog).findByRole("alert");
    expect(alert).toHaveTextContent(message);
    expect(within(alert).getByRole("button", { name: "Paste the text instead" })).toBeVisible();
    expect(within(dialog).getByLabelText("Link to the posting")).toBeInvalid();
    expect(state.started).toEqual([]);
  });

  it("does not offer the text for a link that is no link", async () => {
    const { user } = start({ refusedLinks: { [LINK]: "INVALID_URL" } });
    const dialog = await importLink(user);

    expect(await within(dialog).findByRole("alert")).toHaveTextContent("not a link Jofi can open");
    expect(within(dialog).queryByRole("button", { name: "Paste the text instead" })).not.toBeInTheDocument();
  });

  it("switches to the text field, keeping the dialog, when the user chooses to paste the text", async () => {
    const { user } = start({ refusedLinks: { [LINK]: "NOT_ALLOWED" } });
    const dialog = await importLink(user);

    await user.click(await within(dialog).findByRole("button", { name: "Paste the text instead" }));

    expect(within(dialog).getByLabelText("Text of the posting")).toHaveFocus();
    expect(within(dialog).queryByRole("alert")).not.toBeInTheDocument();
    expect(within(dialog).getByRole("radio", { name: "Pasted text" })).toBeChecked();
  });

  it("drops the error when the user edits the link", async () => {
    const { user } = start({ refusedLinks: { [LINK]: "UNREACHABLE" } });
    const dialog = await importLink(user);
    await within(dialog).findByRole("alert");

    await user.type(within(dialog).getByLabelText("Link to the posting"), "x");

    expect(within(dialog).queryByRole("alert")).not.toBeInTheDocument();
  });
});

describe("Import dialog: pasted text", () => {
  async function importText(user: User, text: string) {
    const dialog = await openDialog(user);
    await user.click(within(dialog).getByRole("radio", { name: "Pasted text" }));
    await user.click(within(dialog).getByLabelText("Text of the posting"));
    await user.paste(text);
    await user.click(within(dialog).getByRole("button", { name: "Import" }));
    return dialog;
  }

  it("sends the text as pasted and shows the result", async () => {
    const { state, user } = start();
    const dialog = await importText(user, "Senior Kotlin Developer\n\nACME GmbH, Berlin");

    expect(await within(dialog).findByText("Posting imported")).toBeVisible();
    expect(state.started).toEqual([
      { kind: "text", body: { description: "Senior Kotlin Developer\n\nACME GmbH, Berlin" } },
    ]);
  });

  it("shows markup in the text as text and never as markup", async () => {
    const { user } = start();
    const dialog = await importText(user, '<img src=x onerror="alert(1)"> <b>bold</b>');

    await within(dialog).findByText("Posting imported");
    expect(dialog.querySelector("img, b")).toBeNull();
  });

  it.each([
    ["REQUIRED", "Paste the text of the posting."],
    ["TOO_LONG", "The text is too long. Jofi reads up to 100,000 characters."],
    ["INVALID_CHARACTER", "The text contains a character Jofi cannot store."],
  ])("explains the text violation %s", async (code, message) => {
    const { user } = start({ textViolation: code });
    const dialog = await importText(user, "x");

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(message);
    expect(within(dialog).getByLabelText("Text of the posting")).toBeInvalid();
    expect(within(dialog).queryByRole("button", { name: "Paste the text instead" })).not.toBeInTheDocument();
  });
});

describe("Import dialog: problems that are not about the input", () => {
  it("sends the user to the AI setup when no model reads postings", async () => {
    const { user } = start({ startConflict: "ai-not-configured" });
    const dialog = await importLink(user);

    expect(await within(dialog).findByRole("alert")).toHaveTextContent("No AI model is set up");
    expect(within(dialog).getByRole("link", { name: "Set up AI" })).toBeVisible();
  });

  it("asks the user to try again shortly when the link is being imported right now", async () => {
    const { user } = start({ startConflict: "import-in-progress" });
    const dialog = await importLink(user);

    expect(await within(dialog).findByRole("alert")).toHaveTextContent("being imported right now");
    expect(within(dialog).getByRole("button", { name: "Import" })).toBeEnabled();
  });
});

describe("Import dialog: a failed import", () => {
  it.each([
    ["AI_NOT_CONFIGURED", /No AI model is set up/, false, true],
    ["AI_AUTHENTICATION_FAILED", /did not accept the API key/, false, true],
    ["AI_UNAVAILABLE", /could not be reached or is busy/, true, false],
    ["AI_REJECTED", /refused to read this posting/, false, true],
    ["UNREADABLE_ANSWER", /answer could not be used/, true, false],
    ["NOT_A_POSTING", /does not look like a job posting/, false, false],
    ["NOT_QUEUED", /could not be started/, true, false],
    ["NOT_COMPLETED", /did not finish/, true, false],
  ])("explains %s", async (failure, message, retryable, settings) => {
    const { user } = start({ outcome: { status: "FAILED", failure } });
    const dialog = await importLink(user);

    expect(await within(dialog).findByText("The import failed")).toBeVisible();
    expect(within(dialog).getByRole("alert")).toHaveTextContent(message);
    expect(!!within(dialog).queryByRole("button", { name: "Try again" })).toBe(retryable);
    expect(!!within(dialog).queryByRole("link", { name: "Set up AI" })).toBe(settings);
  });

  it("retries a failed import and follows it to the application", async () => {
    const { state, user } = start({
      outcome: { status: "FAILED", failure: "AI_UNAVAILABLE" },
      retryOutcome: { status: "SUCCEEDED", applicationId: APPLICATION_ID },
    });
    const dialog = await importLink(user);

    await user.click(await within(dialog).findByRole("button", { name: "Try again" }));

    expect(await within(dialog).findByText("Posting imported")).toBeVisible();
    expect(state.retried).toHaveLength(1);
    expect(state.started).toHaveLength(1);
  });

  it("goes back to the form with the input kept", async () => {
    const { user } = start({ outcome: { status: "FAILED", failure: "NOT_A_POSTING" } });
    const dialog = await importLink(user);

    await user.click(await within(dialog).findByRole("button", { name: "Edit" }));

    expect(within(dialog).getByLabelText("Link to the posting")).toHaveValue(LINK);
  });

  it("offers to check again when the status cannot be read, and shows the result then", async () => {
    const { state, user } = start({ statusFailsWith: 400 });
    const dialog = await importLink(user);

    expect(await within(dialog).findByText("The import's status could not be loaded")).toBeVisible();

    state.statusFailsWith = null;
    await user.click(within(dialog).getByRole("button", { name: "Check again" }));
    expect(await within(dialog).findByText("Posting imported")).toBeVisible();
  });

  it("starts another import from the result", async () => {
    const { state, user } = start();
    const dialog = await importLink(user);
    await within(dialog).findByText("Posting imported");

    await user.click(within(dialog).getByRole("button", { name: "Import another" }));
    await user.type(within(dialog).getByLabelText("Link to the posting"), "https://jobs.example/postings/43");
    await user.click(within(dialog).getByRole("button", { name: "Import" }));

    await waitFor(() => expect(state.started).toHaveLength(2));
  });
});

describe("Import dialog: closing", () => {
  it("closes with Escape and starts empty the next time", async () => {
    const { user } = start();
    const dialog = await openDialog(user);
    await user.type(within(dialog).getByLabelText("Link to the posting"), LINK);

    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    const again = await openDialog(user);
    expect(within(again).getByLabelText("Link to the posting")).toHaveValue("");
  });

  it("closes from Cancel and from Close on the result, returning focus to the button", async () => {
    const { user } = start();
    const dialog = await importLink(user);
    await within(dialog).findByText("Posting imported");

    await user.click(within(dialog).getByRole("button", { name: "Close" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    await waitFor(() => expect(screen.getByRole("button", { name: "Import posting" })).toHaveFocus());
  });
});

describe("Import dialog in German", () => {
  it("explains a refused LinkedIn link in German", async () => {
    const original = getLocale;
    overwriteGetLocale(() => "de");
    try {
      const { user } = start({ refusedLinks: { [LINK]: "NOT_ALLOWED" } });
      await user.click(await screen.findByRole("button", { name: "Anzeige importieren" }));
      const dialog = await screen.findByRole("dialog", { name: "Stellenanzeige importieren" });
      await user.type(within(dialog).getByLabelText("Link zur Anzeige"), LINK);
      await user.click(within(dialog).getByRole("button", { name: "Importieren" }));

      expect(await within(dialog).findByRole("alert")).toHaveTextContent(
        "Jofi öffnet keine Links zu LinkedIn, StepStone oder Indeed.",
      );
      expect(within(dialog).getByRole("button", { name: "Stattdessen Text einfügen" })).toBeVisible();
    } finally {
      overwriteGetLocale(original);
    }
  });
});
