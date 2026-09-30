// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { m } from "../../paraglide/messages.js";
import { Alert, Button, EmptyState, TextLink } from "../../ui";
import { PageHeader } from "../pages/PlaceholderPage";
import type { ErrorDescription } from "../problems";
import { describeCompanyError, isNotFound } from "./companyProblems";

/** A failure that belongs to no single field, with the server's own detail for unknown problems. */
export function FailureMessage({ failure }: { failure: ErrorDescription | null }) {
  if (failure === null) return null;
  return (
    <Alert tone="error" title={m.error_heading()}>
      <p>{failure.message}</p>
      {failure.detail ? (
        <p className="text-muted">
          {m.error_details()}: <span lang="en">{failure.detail}</span>
        </p>
      ) : null}
    </Alert>
  );
}

/** A company page whose company could not be loaded: gone (404), or another failure to retry. */
export function CompanyLoadFailure({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  if (isNotFound(error)) {
    return (
      <>
        <PageHeader title={m.company_not_found_heading()} />
        <EmptyState title={m.company_not_found_heading()}>
          {m.company_error_not_found()} <TextLink to="/companies">{m.company_back_to_list()}</TextLink>
        </EmptyState>
      </>
    );
  }
  return (
    <>
      <PageHeader title={m.error_heading()} />
      <FailureMessage failure={describeCompanyError(error)} />
      <Button className="self-start" onPress={onRetry}>
        {m.error_retry()}
      </Button>
    </>
  );
}
