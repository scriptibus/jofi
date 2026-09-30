// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useRouter } from "@tanstack/react-router";
import { m } from "../../paraglide/messages.js";
import { Alert, Button, DonkeyLogo, EmptyState, TextLink } from "../../ui";
import { usePageTitle } from "../preferences";
import { describeError } from "../problems";
import { PageHeader } from "./PlaceholderPage";

export function NotFoundPage() {
  return (
    <>
      <PageHeader title={m.not_found_heading()} />
      <EmptyState title={m.not_found_heading()}>
        {m.not_found_body()} <TextLink to="/">{m.not_found_back()}</TextLink>
      </EmptyState>
    </>
  );
}

/** Shown while the session status loads on the first visit. */
export function PendingPage() {
  return (
    <div className="flex min-h-dvh items-center justify-center">
      <DonkeyLogo className="size-16" loading label={m.loading()} />
    </div>
  );
}

/**
 * When a route cannot load, typically because the server is unreachable (the service worker
 * still serves the shell offline) or answered with an error while checking the session.
 */
export function RouteErrorPage({ error }: { error: unknown }) {
  const router = useRouter();
  const description = describeError(error);
  usePageTitle(m.error_heading());
  return (
    <main id="main" className="mx-auto flex min-h-dvh max-w-md flex-col justify-center gap-6 px-4">
      <DonkeyLogo className="size-14" />
      <h1 className="text-h2">{m.error_heading()}</h1>
      <Alert tone="error">
        <p>{description.message}</p>
        {description.detail ? (
          <p className="text-muted">
            {m.error_details()}: <span lang="en">{description.detail}</span>
          </p>
        ) : null}
      </Alert>
      <Button className="self-start" onPress={() => void router.invalidate()}>
        {m.error_retry()}
      </Button>
    </main>
  );
}
