// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { createMemoryHistory } from "@tanstack/react-router";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { setupServer } from "msw/node";
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { fakeAuthBackend } from "../../test/fakeAuthBackend";
import { fakeImportBackend } from "../../test/fakeImportBackend";
import { App, createApp } from "../App";
import { parseSharedContent } from "./SharePage";

// The real interval is 1.5 seconds; a poll every 20 ms keeps the tests quick without any fake timers.
vi.mock("../applications/import/importModel", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../applications/import/importModel")>()),
  POLL_INTERVAL_MS: 20,
}));

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const GOOD_PASSWORD = "correct horse battery staple";

function start(path: string, authenticated = true) {
  const auth = fakeAuthBackend({ authenticated });
  const imports = fakeImportBackend({ pendingPolls: 0 });
  server.use(...imports.handlers, ...auth.handlers);
  const app = createApp(createMemoryHistory({ initialEntries: [path] }));
  render(<App app={app} />);
  return { ...imports, router: app.router, user: userEvent.setup() };
}

const shared = (params: Record<string, string>) => `/share?${new URLSearchParams(params).toString()}`;

describe("parseSharedContent", () => {
  it("keeps non-empty strings, trimmed, and nothing else", () => {
    expect(parseSharedContent({ title: " Job ", text: "  ", url: ["a", "b"], other: "x" })).toEqual({
      title: "Job",
    });
  });
});

describe("share target", () => {
  it("opens the import dialog with a shared link filled in, and imports nothing until the user confirms", async () => {
    const { state, user } = start(shared({ title: "Kotlin Developer", url: "https://jobs.example/42" }));

    const dialog = await screen.findByRole("dialog", { name: "Import a job posting" });
    expect(within(dialog).getByLabelText("Link to the posting")).toHaveValue("https://jobs.example/42");
    expect(state.started).toEqual([]);

    await user.click(within(dialog).getByRole("button", { name: "Import" }));

    expect(await within(dialog).findByText("Posting imported")).toBeVisible();
    expect(state.started).toEqual([{ kind: "url", body: { url: "https://jobs.example/42" } }]);
  });

  it("finds the link in the shared text, as Android shares it", async () => {
    start(shared({ text: "Look at this job https://jobs.example/42" }));

    const dialog = await screen.findByRole("dialog", { name: "Import a job posting" });
    expect(within(dialog).getByLabelText("Link to the posting")).toHaveValue("https://jobs.example/42");
  });

  it("imports shared text without a link as pasted text", async () => {
    const { state, user } = start(shared({ title: "Kotlin Developer", text: "ACME GmbH, Berlin" }));

    const dialog = await screen.findByRole("dialog", { name: "Import a job posting" });
    expect(within(dialog).getByLabelText("Text of the posting")).toHaveValue(
      "Kotlin Developer\n\nACME GmbH, Berlin",
    );
    await user.click(within(dialog).getByRole("button", { name: "Import" }));

    await within(dialog).findByText("Posting imported");
    expect(state.started).toEqual([
      { kind: "text", body: { description: "Kotlin Developer\n\nACME GmbH, Berlin" } },
    ]);
  });

  it("takes the shared parts out of the address bar once the page holds them", async () => {
    const { router } = start(shared({ text: "Secret salary talk", url: "https://jobs.example/42" }));

    await screen.findByRole("dialog", { name: "Import a job posting" });
    await waitFor(() => expect(router.state.location.search).toEqual({}));
    expect(router.state.location.pathname).toBe("/share");
    expect(router.state.location.href).toBe("/share");
  });

  it("shows hostile shared content as plain text", async () => {
    start(
      shared({
        title: '<img src=x onerror="alert(1)">',
        text: "javascript:alert(1)",
        url: "https://a.example/",
      }),
    );

    const dialog = await screen.findByRole("dialog", { name: "Import a job posting" });
    await userEvent.setup().keyboard("{Escape}");
    await waitFor(() => expect(dialog).not.toBeInTheDocument());
    expect(screen.getByText('<img src=x onerror="alert(1)">')).toBeVisible();
    expect(document.querySelector("img")).toBeNull();
    expect(screen.queryByRole("link", { name: /alert/ })).not.toBeInTheDocument();
  });

  it("keeps the shared content after the dialog is closed, and opens it again from the button", async () => {
    const { user } = start(shared({ url: "https://jobs.example/42" }));
    await screen.findByRole("dialog", { name: "Import a job posting" });
    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    expect(screen.getByRole("heading", { level: 1, name: "Shared with Jofi" })).toBeVisible();
    expect(screen.getByText("https://jobs.example/42")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "Review and import" }));

    const dialog = await screen.findByRole("dialog", { name: "Import a job posting" });
    expect(within(dialog).getByLabelText("Link to the posting")).toHaveValue("https://jobs.example/42");
  });

  it("opens no dialog when nothing was shared", async () => {
    start("/share");

    expect(
      await screen.findByText("Nothing was shared. Use your device's share sheet and pick Jofi."),
    ).toBeVisible();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Review and import" })).not.toBeInTheDocument();
  });

  it("asks a logged-out user to log in first, then brings them back to the shared content", async () => {
    const { state, router, user } = start(shared({ url: "https://jobs.example/42" }), false);

    expect(await screen.findByRole("heading", { level: 1, name: "Welcome back" })).toBeVisible();
    expect(state.started).toEqual([]);

    await user.type(screen.getByLabelText("Password"), GOOD_PASSWORD);
    await user.click(screen.getByRole("button", { name: "Log in" }));

    const dialog = await screen.findByRole("dialog", { name: "Import a job posting" });
    expect(within(dialog).getByLabelText("Link to the posting")).toHaveValue("https://jobs.example/42");
    expect(state.started).toEqual([]);
    await waitFor(() => expect(router.state.location.href).toBe("/share"));
  });
});
