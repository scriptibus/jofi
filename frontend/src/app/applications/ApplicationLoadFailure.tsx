// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { m } from "../../paraglide/messages.js";
import { Button, EmptyState, TextLink } from "../../ui";
import { FailureMessage } from "../companies/CompanyLoadFailure";
import { PageHeader } from "../pages/PlaceholderPage";
import { describeApplicationError, isApplicationNotFound } from "./applicationProblems";

/** An application page whose application could not be loaded: gone (404), or another failure to retry. */
export function ApplicationLoadFailure({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  if (isApplicationNotFound(error)) {
    return (
      <>
        <PageHeader title={m.application_not_found_heading()} />
        <EmptyState title={m.application_not_found_heading()}>
          {m.application_error_not_found()}{" "}
          <TextLink to="/applications">{m.application_back_to_list()}</TextLink>
        </EmptyState>
      </>
    );
  }
  return (
    <>
      <PageHeader title={m.error_heading()} />
      <FailureMessage failure={describeApplicationError(error)} />
      <Button className="self-start" onPress={onRetry}>
        {m.error_retry()}
      </Button>
    </>
  );
}
