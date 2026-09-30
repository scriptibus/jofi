// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { File as NodeFile } from "node:buffer";
import { createMemoryHistory } from "@tanstack/react-router";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { setupServer } from "msw/node";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { type FakeAuthState, fakeAuthBackend } from "../../test/fakeAuthBackend";
import { BACKUP_FILE_NAME, type FakeBackupState, fakeBackupBackend } from "../../test/fakeBackupBackend";
import { App, createApp } from "../App";

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => {
  server.resetHandlers();
  vi.restoreAllMocks();
});
afterAll(() => server.close());

const PASSWORD = "correct horse battery staple";

let downloads: { name: string; size: number }[];
beforeEach(() => {
  downloads = [];
  const blobs = new Map<string, Blob>();
  vi.spyOn(URL, "createObjectURL").mockImplementation((blob) => {
    const url = `blob:test/${blobs.size}`;
    blobs.set(url, blob as Blob);
    return url;
  });
  vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => undefined);
  vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (this: HTMLAnchorElement) {
    downloads.push({ name: this.download, size: blobs.get(this.href)?.size ?? -1 });
  });
});

function start(auth: Partial<FakeAuthState> = {}, backup: Partial<FakeBackupState> = {}) {
  const fakeAuth = fakeAuthBackend({ authenticated: true, ...auth });
  const fakeBackup = fakeBackupBackend(fakeAuth.state, backup);
  server.use(...fakeBackup.handlers, ...fakeAuth.handlers);
  const app = createApp(createMemoryHistory({ initialEntries: ["/settings"] }));
  render(<App app={app} />);
  return { auth: fakeAuth.state, backup: fakeBackup.state, router: app.router, user: userEvent.setup() };
}

async function openExport(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole("button", { name: "Download backup…" }));
  return screen.getByRole("dialog", { name: "Download a backup" });
}

async function upload(user: ReturnType<typeof userEvent.setup>, content = "PK zip") {
  await screen.findByRole("button", { name: "Choose backup file…" });
  const input = document.querySelector<HTMLInputElement>("input[type=file]");
  if (input === null) throw new Error("no file input");
  // Node's File: jsdom's own cannot be a fetch body (Node's fetch sends it in the real browser's place).
  const file = new NodeFile([content], "my-backup.zip", { type: "application/zip" });
  await user.upload(input, file as unknown as File);
}

describe("Settings > Backup: export", () => {
  it("warns that a backup grants full access", async () => {
    start();
    const heading = await screen.findByRole("heading", { level: 2, name: "Backup" });
    expect(heading).toBeVisible();
    const notes = screen.getAllByRole("note");
    expect(notes[0]).toHaveTextContent("A backup grants full access");
    expect(notes[0]).toHaveTextContent("Store it like a password");
  });

  it("asks for the password, refuses a wrong one, then downloads the zip under the server's name", async () => {
    const { user } = start();
    const dialog = await openExport(user);

    await user.type(within(dialog).getByLabelText("Current password"), "wrong password");
    await user.click(within(dialog).getByRole("button", { name: "Download" }));
    expect(await within(dialog).findByText("The current password is wrong.")).toBeVisible();
    expect(downloads).toEqual([]);

    const field = within(dialog).getByLabelText("Current password");
    await user.clear(field);
    await user.type(field, PASSWORD);
    await user.click(within(dialog).getByRole("button", { name: "Download" }));

    expect(
      await screen.findByText(`Backup downloaded as ${BACKUP_FILE_NAME}. Keep it somewhere safe.`),
    ).toBeVisible();
    expect(downloads).toEqual([{ name: BACKUP_FILE_NAME, size: 6 }]);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("forgets the password when the dialog is cancelled", async () => {
    const { user } = start();
    let dialog = await openExport(user);
    await user.type(within(dialog).getByLabelText("Current password"), PASSWORD);
    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();

    dialog = await openExport(user);
    expect(within(dialog).getByLabelText("Current password")).toHaveValue("");
  });

  it("waits out the backoff with the button disabled", async () => {
    const { user } = start({ throttleSeconds: 30 });
    const dialog = await openExport(user);
    await user.type(within(dialog).getByLabelText("Current password"), PASSWORD);
    await user.click(within(dialog).getByRole("button", { name: "Download" }));
    expect(await within(dialog).findByText(/^Too many failed attempts/)).toBeVisible();
    expect(within(dialog).getByRole("button", { name: "Download" })).toBeDisabled();
  });

  it("says when another backup job is running", async () => {
    const { user } = start({}, { busy: true });
    const dialog = await openExport(user);
    await user.type(within(dialog).getByLabelText("Current password"), PASSWORD);
    await user.click(within(dialog).getByRole("button", { name: "Download" }));
    expect(
      await within(dialog).findByText(/Another backup download, upload or restore is running/),
    ).toBeVisible();
  });
});

describe("Settings > Backup: restore", () => {
  it("shows the uploaded backup, confirms with the server's effect, then logs out with a reason", async () => {
    const { user, backup, auth, router } = start();
    expect(await screen.findByText("Restoring replaces all data")).toBeVisible();
    expect(screen.getByText(/Only restore backups you made yourself/)).toBeVisible();

    await upload(user);
    const summary = await screen.findByRole("region", { name: "Uploaded backup" });
    expect(within(summary).getByText("0.1.0")).toBeVisible();
    expect(within(summary).getByText("20260930064000")).toBeVisible();
    expect(within(summary).getByText("1,234")).toBeVisible();
    expect(within(summary).getByText("Included")).toBeVisible();
    expect(within(summary).getByText("Not needed")).toBeVisible();

    await user.type(screen.getByLabelText("Current password, to confirm the restore"), PASSWORD);
    await user.click(screen.getByRole("button", { name: "Restore this backup…" }));
    let dialog = await screen.findByRole("alertdialog", { name: "Replace all data?" });
    expect(dialog).toHaveTextContent(/1,234 database rows and 5 files, with the key for the stored API keys/);
    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
    expect(backup.restoreCalls).toEqual(["first"]);

    await user.click(screen.getByRole("button", { name: "Restore this backup…" }));
    dialog = await screen.findByRole("alertdialog", { name: "Replace all data?" });
    const confirm = within(dialog).getByRole("button", { name: "Replace all data" });
    await waitFor(() => expect(confirm).toBeEnabled());
    await user.click(confirm);

    expect(await screen.findByText(/The backup was restored and every session has ended/)).toBeVisible();
    expect(router.state.location.pathname).toBe("/login");
    expect(backup.restoreCalls).toEqual(["first", "first", "confirmed"]);
    expect(auth.authenticated).toBe(false);
  });

  it("names a migrated backup's original schema", async () => {
    const { user } = start(
      {},
      {
        staged: {
          id: "7a1c3e9d-2b4f-4c1a-9e8d-0f1e2d3c4b5a",
          createdAt: "2026-01-01T08:00:00Z",
          appVersion: "0.0.9",
          schemaVersion: "20260930064000",
          migratedFrom: "20260101000000",
          rows: 3,
          files: 0,
          includesKeyset: false,
        },
      },
    );
    await upload(user);
    const summary = await screen.findByRole("region", { name: "Uploaded backup" });
    expect(within(summary).getByText(/older schema 20260101000000, migrated/)).toBeVisible();
    expect(within(summary).getByText("Not included")).toBeVisible();
  });

  it.each([
    ["schema-newer", /made with a newer Jofi. Upgrade Jofi first/],
    ["not-a-backup", /not a Jofi backup/],
    ["too-large", /larger than this Jofi accepts/],
    ["something-new", /cannot be restored \(reason: something-new\)/],
  ])("explains a refused upload (%s)", async (reason, text) => {
    const { user } = start({}, { refuse: { content: "bad", reason } });
    await upload(user, "bad");
    expect(await screen.findByText(text)).toBeVisible();
    expect(screen.queryByRole("region", { name: "Uploaded backup" })).not.toBeInTheDocument();
  });

  it("says when an upload meets a running backup job", async () => {
    const { user } = start({}, { busy: true });
    await upload(user);
    expect(await screen.findByText(/Another backup download, upload or restore is running/)).toBeVisible();
  });

  it("refuses a wrong password before asking anything", async () => {
    const { user, backup } = start();
    await upload(user);
    await screen.findByRole("region", { name: "Uploaded backup" });
    await user.type(screen.getByLabelText("Current password, to confirm the restore"), "wrong password");
    await user.click(screen.getByRole("button", { name: "Restore this backup…" }));
    expect(await screen.findByText("The current password is wrong.")).toBeVisible();
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    expect(backup.restoreCalls).toEqual([]);
  });

  it.each([
    [500, "backup-restore-incomplete", /Restart Jofi: it finishes undoing the restore/],
    [404, "backup-not-found", /no longer waiting to be restored/],
  ])("explains a failed restore (%s %s)", async (status, code, text) => {
    const { user, router } = start({}, { restoreFails: { status, code } });
    await upload(user);
    await screen.findByRole("region", { name: "Uploaded backup" });
    await user.type(screen.getByLabelText("Current password, to confirm the restore"), PASSWORD);
    await user.click(screen.getByRole("button", { name: "Restore this backup…" }));
    const dialog = await screen.findByRole("alertdialog", { name: "Replace all data?" });
    const confirm = within(dialog).getByRole("button", { name: "Replace all data" });
    await waitFor(() => expect(confirm).toBeEnabled());
    await user.click(confirm);

    expect(await screen.findByText(text)).toBeVisible();
    expect(router.state.location.pathname).toBe("/settings");
    expect(screen.getByRole("button", { name: "Restore this backup…" })).toBeEnabled();
  });
});
