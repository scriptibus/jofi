// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import {
  type PrivacyClaimResponse,
  type PrivacyClaimResponseStatus,
  type ProviderPrivacyResponse,
  type ProviderResponseKind,
  useListProviderPrivacyInfo,
} from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { getLocale } from "../../paraglide/runtime.js";
import { Alert, ExternalLink } from "../../ui";
import { PROVIDER_KIND_LABELS } from "./tasks";

/** The Paraglide key the server names for its disclaimer (ADR-0045); its text lives in messages/*.json. */
const DISCLAIMER_KEY = "setup_provider_privacy_disclaimer";

const STATUS_LABELS: Record<PrivacyClaimResponseStatus, () => string> = {
  YES: m.ai_privacy_status_yes,
  ON_REQUEST: m.ai_privacy_status_on_request,
  CONDITIONAL: m.ai_privacy_status_conditional,
  NO: m.ai_privacy_status_no,
  DEPENDS_ON_ENDPOINT: m.ai_privacy_status_depends_on_endpoint,
  UNKNOWN: m.ai_privacy_status_unknown,
};

const CLAIMS = [
  { field: "zeroDataRetention", label: m.ai_privacy_zero_data_retention },
  { field: "noTraining", label: m.ai_privacy_no_training },
  { field: "dataLocation", label: m.ai_privacy_data_location },
] as const;

/** A `YYYY-MM-DD` date in the user's language, read as a calendar day (no time zone shift). */
export function formatDay(day: string): string {
  const date = new Date(`${day}T00:00:00Z`);
  if (Number.isNaN(date.getTime())) return day;
  return new Intl.DateTimeFormat(getLocale(), { dateStyle: "long", timeZone: "UTC" }).format(date);
}

function localized(text: { en: string; de: string }): string {
  return getLocale() === "de" ? text.de : text.en;
}

function disclaimerText(info: ProviderPrivacyResponse | undefined): string {
  if (info === undefined || info.disclaimer.key === DISCLAIMER_KEY)
    return m.setup_provider_privacy_disclaimer();
  return localized(info.disclaimer.text);
}

/**
 * Privacy information for a provider kind (spec §3.2, ADR-0045): for zero data retention, training on
 * API data and data location a status, a short summary and the provider's own pages as sources, with
 * the date they were read, a warning when that is long ago, and always the disclaimer. Quotes stay in
 * the provider's language (English). Without the info (still loading or failed) only what is certain
 * and the disclaimer show.
 */
export function ProviderPrivacyInfo({ kind }: { kind: ProviderResponseKind }) {
  const privacy = useListProviderPrivacyInfo({
    // Without it the card still shows what is certain and the disclaimer, so no global notice.
    query: { staleTime: Number.POSITIVE_INFINITY, meta: { errorHandledLocally: true } },
  });
  const entry = privacy.data?.providers.find((provider) => provider.kind === kind);
  return (
    <section aria-labelledby="privacy-heading" className="flex flex-col gap-3 rounded bg-sunken p-4">
      <h4 id="privacy-heading" className="font-semibold text-body">
        {m.ai_privacy_heading({ provider: PROVIDER_KIND_LABELS[kind]() })}
      </h4>
      {entry ? (
        <>
          <p className="text-muted">{m.ai_privacy_checked_on({ date: formatDay(entry.checkedOn) })}</p>
          {entry.stale ? (
            <Alert tone="warning" title={m.ai_privacy_stale_title()}>
              <p>{m.ai_privacy_stale({ months: privacy.data?.staleAfterMonths ?? 0 })}</p>
            </Alert>
          ) : null}
          <dl className="flex flex-col gap-4">
            {CLAIMS.map(({ field, label }) => (
              <Claim key={field} label={label()} claim={entry[field]} />
            ))}
          </dl>
        </>
      ) : (
        <p>{kind === "OPENAI_COMPATIBLE" ? m.ai_privacy_compatible() : m.ai_privacy_cloud()}</p>
      )}
      <Alert tone="warning" title={m.ai_privacy_disclaimer_title()}>
        <p>{disclaimerText(privacy.data)}</p>
      </Alert>
    </section>
  );
}

function Claim({ label, claim }: { label: string; claim: PrivacyClaimResponse }) {
  const sources = [...new Set(claim.evidence.map((evidence) => evidence.source))];
  return (
    <div className="flex flex-col gap-1.5">
      <dt className="flex flex-wrap items-center gap-2 font-semibold">
        {label}
        <span className="rounded border border-line bg-surface px-2 font-data text-eyebrow uppercase">
          {STATUS_LABELS[claim.status]()}
        </span>
      </dt>
      <dd className="flex flex-col gap-1.5">
        <p>{localized(claim.summary)}</p>
        {sources.length > 0 ? (
          <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
            <span className="text-muted">{m.ai_privacy_sources()}</span>
            {sources.map((source) => (
              <ExternalLink key={source} href={source} className="break-all font-normal">
                {source.replace(/^https:\/\//, "")}
              </ExternalLink>
            ))}
          </div>
        ) : null}
        <details className="text-muted">
          <summary className="cursor-default">{m.ai_privacy_quotes()}</summary>
          {claim.evidence.map((evidence) => (
            <blockquote
              key={evidence.quote}
              cite={evidence.source}
              lang="en"
              className="mt-2 border-line border-l-2 pl-3"
            >
              {evidence.quote}
            </blockquote>
          ))}
        </details>
      </dd>
    </div>
  );
}
