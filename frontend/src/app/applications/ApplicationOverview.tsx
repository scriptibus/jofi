// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { ReactNode } from "react";
import {
  type ApplicationResponse,
  type ApplicationSourceResponse,
  type OfferDto,
  useGetCompany,
} from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { ExternalLink, Markdown, TextLink } from "../../ui";
import { isWebAddress } from "../companies/company";
import { sectionCard } from "../companies/RelatedRecords";
import {
  formatDate,
  formatInstantDate,
  formatPay,
  formatPayBand,
  formatPercent,
  formatScore,
  languageName,
} from "./format";
import {
  confidenceLabels,
  declineCategoryLabels,
  employmentTypeLabels,
  formOfAddressLabels,
  howAppliedLabels,
  paySourceLabels,
  type Status,
  seniorityLabels,
  sourceKindLabels,
  statusLabels,
  toneLabels,
} from "./labels";

/** The status as a word in a frame (read-only here; the status control comes with #103). */
export function StatusBadge({ status }: { status: Status }) {
  return (
    <span className="inline-flex w-fit items-center gap-1.5 rounded border border-line bg-sunken px-2 py-0.5 font-semibold text-body">
      <span className="sr-only">{m.application_status_label()}: </span>
      {statusLabels[status]()}
    </span>
  );
}

export function CompanyLink({ companyId }: { companyId: string }) {
  const company = useGetCompany(companyId, { query: { meta: { errorHandledLocally: true } } });
  if (company.isError) return <span className="text-muted">{m.company_not_found_heading()}</span>;
  return (
    <TextLink to="/companies/$companyId" params={{ companyId }}>
      {company.data?.name ?? m.loading()}
    </TextLink>
  );
}

function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1">
      <dt className="font-data text-eyebrow text-muted uppercase">{label}</dt>
      <dd className="break-words">{children}</dd>
    </div>
  );
}

function Section({ id, title, children }: { id: string; title: string; children: ReactNode }) {
  return (
    <section aria-labelledby={id} className={sectionCard}>
      <h2 id={id} className="text-h2">
        {title}
      </h2>
      {children}
    </section>
  );
}

const none = () => <span className="text-muted">{m.company_value_none()}</span>;

/** A value, or "Not set". */
function orNone<T>(value: T | null | undefined, show: (value: T) => ReactNode = String): ReactNode {
  return value === null || value === undefined ? none() : show(value);
}

/** The Overview tab: every detail of the application, read-only, in cards. */
export function ApplicationOverview({ application }: { application: ApplicationResponse }) {
  return (
    <div className="grid gap-6 lg:grid-cols-2">
      <JobFacts application={application} />
      <StatusSection application={application} />
      <PaySection application={application} />
      <LanguageSection application={application} />
      <ScoresSection application={application} />
      <SourcesSection sources={application.sources} />
      <ApplyingSection application={application} />
      {application.offer ? <OfferSection offer={application.offer} /> : null}
    </div>
  );
}

function JobFacts({ application }: { application: ApplicationResponse }) {
  return (
    <Section id="application-facts-heading" title={m.company_facts_heading()}>
      <dl className="flex flex-col gap-4">
        <Fact label={m.contact_field_company()}>
          <CompanyLink companyId={application.companyId} />
        </Fact>
        <Fact label={m.application_field_location()}>{orNone(application.location)}</Fact>
        <Fact label={m.application_field_remote_share()}>
          {orNone(application.remoteShare, (share) => formatPercent(share))}
        </Fact>
        <Fact label={m.application_field_employment_type()}>
          {orNone(application.employmentType, (type) => employmentTypeLabels[type]())}
        </Fact>
        <Fact label={m.application_field_seniority()}>
          {orNone(application.seniority, (level) => seniorityLabels[level]())}
        </Fact>
      </dl>
    </Section>
  );
}

function StatusSection({ application }: { application: ApplicationResponse }) {
  const reason = application.declineReason;
  return (
    <Section id="application-status-heading" title={m.application_status_label()}>
      <StatusBadge status={application.status} />
      {reason ? (
        <dl className="flex flex-col gap-4">
          <Fact label={m.application_decline_reason()}>{declineCategoryLabels[reason.category]()}</Fact>
          {reason.text ? (
            <Fact label={m.application_decline_text()}>
              <Markdown>{reason.text}</Markdown>
            </Fact>
          ) : null}
        </dl>
      ) : null}
    </Section>
  );
}

function PaySection({ application }: { application: ApplicationResponse }) {
  const band = application.payBand;
  return (
    <Section id="application-pay-heading" title={m.application_pay_heading()}>
      {band ? (
        <dl className="flex flex-col gap-4">
          <Fact label={m.application_pay_gross()}>
            <span className="font-data">{formatPayBand(band)}</span>
          </Fact>
          <Fact label={m.application_pay_source()}>{paySourceLabels[band.source]()}</Fact>
          {band.source === "ESTIMATED" ? (
            <>
              <Fact label={m.application_pay_confidence()}>
                {orNone(band.estimateConfidence, (confidence) => confidenceLabels[confidence]())}
              </Fact>
              <Fact label={m.application_pay_basis()}>
                <span className="whitespace-pre-line">{orNone(band.estimateBasis)}</span>
              </Fact>
            </>
          ) : null}
        </dl>
      ) : (
        <p className="text-muted">{m.application_pay_empty()}</p>
      )}
    </Section>
  );
}

/** A BCP 47 tag with its name in the user's language: "German (de)". */
function Language({ tag }: { tag: string }) {
  const name = languageName(tag);
  return (
    <>
      {name ?? tag} {name ? <span className="font-data text-muted">({tag})</span> : null}
    </>
  );
}

function LanguageSection({ application }: { application: ApplicationResponse }) {
  const { postingLanguage, applicationLanguage, formOfAddress, tone } = application.languageAndTone;
  let applyIn: ReactNode = none();
  if (applicationLanguage) applyIn = <Language tag={applicationLanguage} />;
  else if (postingLanguage) applyIn = m.application_language_as_posting();
  return (
    <Section id="application-language-heading" title={m.application_language_heading()}>
      <dl className="flex flex-col gap-4">
        <Fact label={m.application_field_posting_language()}>
          {orNone(postingLanguage, (tag) => (
            <Language tag={tag} />
          ))}
        </Fact>
        <Fact label={m.application_field_application_language()}>{applyIn}</Fact>
        <Fact label={m.application_field_form_of_address()}>
          {orNone(formOfAddress, (address) => formOfAddressLabels[address]())}
        </Fact>
        <Fact label={m.application_field_tone()}>{orNone(tone, (value) => toneLabels[value]())}</Fact>
      </dl>
    </Section>
  );
}

/** Want and Fit (spec §6.1); until scoring exists (M2) they say when they come. */
function ScoresSection({ application }: { application: ApplicationResponse }) {
  const score = (value: number | null | undefined) =>
    value === null || value === undefined ? (
      <span className="text-muted">{m.application_score_pending()}</span>
    ) : (
      <span className="font-data">{formatScore(value)}</span>
    );
  return (
    <Section id="application-scores-heading" title={m.application_scores_heading()}>
      <dl className="flex flex-col gap-4">
        <Fact label={m.application_score_want()}>{score(application.wantScore)}</Fact>
        <Fact label={m.application_score_fit()}>{score(application.fitScore)}</Fact>
      </dl>
    </Section>
  );
}

/** The posting's link: http(s) only (the server checks it too); anything else stays plain text. */
function SourceLink({ url }: { url: string }) {
  if (!isWebAddress(url)) return <span className="break-all">{url}</span>;
  return (
    <ExternalLink href={url} className="self-start break-all">
      {url}
    </ExternalLink>
  );
}

function SourcesSection({ sources }: { sources: ApplicationSourceResponse[] }) {
  return (
    <Section id="application-sources-heading" title={m.application_sources_heading()}>
      {sources.length === 0 ? (
        <p className="text-muted">{m.application_sources_empty()}</p>
      ) : (
        <ul className="flex flex-col divide-y divide-line">
          {sources.map((source) => (
            <li key={source.id} className="flex flex-col gap-1 py-2">
              <span className="font-semibold">{sourceKindLabels[source.kind]()}</span>
              {source.originalUrl ? <SourceLink url={source.originalUrl} /> : null}
              <span className="text-muted">
                {m.application_source_found({ date: formatInstantDate(source.discoveredAt) })}
                {source.offlineSince
                  ? ` · ${m.application_source_offline({ date: formatInstantDate(source.offlineSince) })}`
                  : null}
              </span>
            </li>
          ))}
        </ul>
      )}
    </Section>
  );
}

function ApplyingSection({ application }: { application: ApplicationResponse }) {
  return (
    <Section id="application-applying-heading" title={m.application_applying_heading()}>
      <dl className="flex flex-col gap-4">
        <Fact label={m.application_field_deadline()}>{orNone(application.deadline, formatDate)}</Fact>
        <Fact label={m.application_field_how_applied()}>
          {orNone(application.howApplied, (how) => howAppliedLabels[how]())}
        </Fact>
        <Fact label={m.application_field_portal_notes()}>
          {application.portalNotes ? (
            <Markdown>{application.portalNotes}</Markdown>
          ) : (
            <span className="text-muted">{m.application_portal_notes_empty()}</span>
          )}
        </Fact>
      </dl>
    </Section>
  );
}

function OfferSection({ offer }: { offer: OfferDto }) {
  const text = (value: string | null | undefined) =>
    orNone(value, (content) => <span className="whitespace-pre-line">{content}</span>);
  return (
    <Section id="application-offer-heading" title={m.application_offer_heading()}>
      <dl className="grid gap-4 sm:grid-cols-2">
        <Fact label={m.application_offer_salary()}>
          {orNone(offer.salary, (salary) => (
            <span className="font-data">{formatPay(salary)}</span>
          ))}
        </Fact>
        <Fact label={m.application_offer_bonus()}>{text(offer.bonus)}</Fact>
        <Fact label={m.application_offer_benefits()}>{text(offer.benefits)}</Fact>
        <Fact label={m.application_field_remote_share()}>
          {orNone(offer.remoteShare, (share) => formatPercent(share))}
        </Fact>
        <Fact label={m.application_offer_vacation_days()}>{orNone(offer.vacationDays)}</Fact>
        <Fact label={m.application_offer_notice_period()}>{text(offer.noticePeriod)}</Fact>
        <Fact label={m.application_offer_start_date()}>{orNone(offer.startDate, formatDate)}</Fact>
        <Fact label={m.application_offer_answer_by()}>{orNone(offer.answerBy, formatDate)}</Fact>
      </dl>
    </Section>
  );
}
