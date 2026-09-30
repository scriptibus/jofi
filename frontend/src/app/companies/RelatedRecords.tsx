// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { ReactNode } from "react";
import { ApiProblemError } from "../../api/fetcher";
import { useSearchApplications, useSearchContacts } from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { describeError } from "../problems";
import { FailureMessage } from "./CompanyLoadFailure";

/** Enough for one company's related records on one page; the server allows at most 200. */
const RELATED_PAGE_SIZE = 200;

export const sectionCard = "flex flex-col gap-4 rounded border border-line bg-surface p-6 shadow-card";

// Answered while the endpoint is only a contract (the use cases land with #89 and #82).
const notImplemented = (error: unknown) => error instanceof ApiProblemError && error.status === 501;
const quietly = { meta: { errorHandledLocally: true }, retry: false } as const;

interface RelatedSectionProps {
  id: string;
  title: string;
  query: { isPending: boolean; error: unknown };
  /** Shown while the server has no such endpoint yet. */
  notYet: string;
  empty: string;
  items: { id: string; primary: string; secondary?: string | null | undefined }[] | undefined;
}

function RelatedSection({ id, title, query, notYet, empty, items }: RelatedSectionProps) {
  let content: ReactNode;
  if (query.error) {
    content = notImplemented(query.error) ? (
      <p className="text-muted">{notYet}</p>
    ) : (
      <FailureMessage failure={describeError(query.error)} />
    );
  } else if (query.isPending || items === undefined) {
    content = <p role="status">{m.loading()}</p>;
  } else if (items.length === 0) {
    content = <p className="text-muted">{empty}</p>;
  } else {
    content = (
      <ul className="flex flex-col divide-y divide-line">
        {items.map((item) => (
          <li key={item.id} className="flex flex-col py-2">
            <span className="font-semibold">{item.primary}</span>
            {item.secondary ? <span className="text-muted">{item.secondary}</span> : null}
          </li>
        ))}
      </ul>
    );
  }
  return (
    <section aria-labelledby={id} className={sectionCard}>
      <h2 id={id} className="text-h2">
        {title}
      </h2>
      {content}
    </section>
  );
}

/** The company's contacts, read-only; editing them is the contacts pages' job (#109). */
export function CompanyContacts({ companyId }: { companyId: string }) {
  const contacts = useSearchContacts({ companyId, size: RELATED_PAGE_SIZE }, { query: quietly });
  return (
    <RelatedSection
      id="company-contacts-heading"
      title={m.company_contacts_heading()}
      query={contacts}
      notYet={m.company_contacts_not_yet()}
      empty={m.company_contacts_empty()}
      items={contacts.data?.contacts.map((contact) => ({
        id: contact.id,
        primary: contact.name,
        secondary: contact.role,
      }))}
    />
  );
}

/** The company's applications, read-only, until the applications pages link them. */
export function CompanyApplications({ companyId, count }: { companyId: string; count: number }) {
  const applications = useSearchApplications({ companyId, size: RELATED_PAGE_SIZE }, { query: quietly });
  return (
    <RelatedSection
      id="company-applications-heading"
      title={m.company_applications_heading()}
      query={applications}
      notYet={m.company_applications_not_yet({ count })}
      empty={m.company_applications_empty()}
      items={applications.data?.applications.map((application) => ({
        id: application.id,
        primary: application.title,
        secondary: application.location,
      }))}
    />
  );
}
