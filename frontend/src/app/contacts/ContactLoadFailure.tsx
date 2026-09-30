// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { m } from "../../paraglide/messages.js";
import { Button, EmptyState, TextLink } from "../../ui";
import { FailureMessage } from "../companies/CompanyLoadFailure";
import { PageHeader } from "../pages/PlaceholderPage";
import { describeContactError, isContactNotFound } from "./contactProblems";

/** A contact page whose contact could not be loaded: gone (404), or another failure to retry. */
export function ContactLoadFailure({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  if (isContactNotFound(error)) {
    return (
      <>
        <PageHeader title={m.contact_not_found_heading()} />
        <EmptyState title={m.contact_not_found_heading()}>
          {m.contact_error_not_found()} <TextLink to="/contacts">{m.contact_back_to_list()}</TextLink>
        </EmptyState>
      </>
    );
  }
  return (
    <>
      <PageHeader title={m.error_heading()} />
      <FailureMessage failure={describeContactError(error)} />
      <Button className="self-start" onPress={onRetry}>
        {m.error_retry()}
      </Button>
    </>
  );
}
