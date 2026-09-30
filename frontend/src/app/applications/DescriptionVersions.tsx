// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { ReactNode } from "react";
import { type DescriptionSnapshotSummaryResponse, useGetDescriptionSnapshot } from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { getLocale } from "../../paraglide/runtime.js";
import { Button, FrozenIcon, RadioList } from "../../ui";
import { FailureMessage } from "../companies/CompanyLoadFailure";
import { describeApplicationError } from "./applicationProblems";
import { formatInstant } from "./format";
import { snapshotReasonLabels } from "./labels";

/** "Version 3": versions count per source from the oldest, as the list shows them. */
export const versionName = (number: number) => m.application_description_version({ number });

/** Marks the version the user applied for (ADR-0046) in words and with a lock, never by colour alone. */
export function FrozenBadge() {
  return (
    <span className="inline-flex w-fit items-center gap-1 rounded bg-accent-soft px-2 py-0.5 font-semibold text-fg">
      <FrozenIcon className="size-4" aria-hidden="true" />
      {m.application_description_frozen()}
    </span>
  );
}

/** When, why and how long: "Sep 30, 2026, 10:00 AM · Recorded by hand · 1,234 characters". */
function VersionFacts({ version }: { version: DescriptionSnapshotSummaryResponse }) {
  const shown = new Intl.NumberFormat(getLocale()).format(version.length);
  return (
    <>
      <time dateTime={version.capturedAt}>{formatInstant(version.capturedAt)}</time>
      {` · ${snapshotReasonLabels[version.reason]()} · `}
      {m.application_description_length({ count: version.length, shown })}
    </>
  );
}

/** A failed load with its own message and a retry button. */
export function LoadFailure({
  error,
  message,
  onRetry,
}: {
  error: unknown;
  message: string;
  onRetry: () => void;
}) {
  return (
    <>
      <FailureMessage failure={{ ...describeApplicationError(error), message }} />
      <Button variant="secondary" className="self-start" onPress={onRetry}>
        {m.error_retry()}
      </Button>
    </>
  );
}

/** One source's versions, newest first, to pick the one to read. [versions] come oldest first. */
export function VersionList({
  versions,
  shown,
  onShow,
}: {
  versions: readonly DescriptionSnapshotSummaryResponse[];
  shown: string | null;
  onShow: (id: string) => void;
}) {
  if (versions.length === 0)
    return <p className="text-muted">{m.application_description_versions_empty()}</p>;
  const options = versions
    .map((version, index) => ({
      value: version.id,
      label: versionName(index + 1),
      details: (
        <span className="flex flex-col gap-1">
          <span>
            <VersionFacts version={version} />
          </span>
          {version.frozenAt ? <FrozenBadge /> : null}
        </span>
      ),
    }))
    .reverse();
  return (
    <RadioList
      label={m.application_description_versions()}
      options={options}
      value={shown}
      hideLabel
      onChange={onShow}
    />
  );
}

/**
 * The text of one version. Posting text is untrusted data: it is shown as plain text with its line breaks,
 * never as Markdown or HTML, in the posting's language when the application knows it.
 */
export function VersionText({
  applicationId,
  version,
  number,
  lang,
}: {
  applicationId: string;
  version: DescriptionSnapshotSummaryResponse;
  number: number;
  lang: string | undefined;
}) {
  const snapshot = useGetDescriptionSnapshot(applicationId, version.id, {
    query: { meta: { errorHandledLocally: true }, staleTime: Number.POSITIVE_INFINITY },
  });
  let body: ReactNode;
  if (snapshot.isError)
    body = (
      <LoadFailure
        error={snapshot.error}
        message={m.application_description_text_failed()}
        onRetry={() => void snapshot.refetch()}
      />
    );
  else if (snapshot.data === undefined) body = <p role="status">{m.loading()}</p>;
  else
    body = (
      <div lang={lang} className="whitespace-pre-wrap break-words">
        {snapshot.data.description}
      </div>
    );
  return (
    <>
      <div className="flex flex-col gap-1">
        <h2 id="application-description-text-heading" className="text-h2">
          {m.application_description_text_heading({ number })}
        </h2>
        <p className="text-muted">
          <VersionFacts version={version} />
        </p>
        {version.frozenAt ? <FrozenBadge /> : null}
      </div>
      {body}
    </>
  );
}
