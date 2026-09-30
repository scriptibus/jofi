// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { getRouteApi, useNavigate } from "@tanstack/react-router";
import { type ReactNode, useState } from "react";
import type { ConfirmationEffect } from "../../api/confirmation";
import { type CompanyResponse, deleteCompany, useGetCompany } from "../../api/generated/jofi";
import { getLocale } from "../../paraglide/runtime.js";
import { m } from "../../paraglide/messages.js";
import {
  BackIcon,
  Button,
  DeleteIcon,
  EditIcon,
  ExternalLink,
  FavouriteIcon,
  Markdown,
  TextLink,
} from "../../ui";
import { PageHeader } from "../pages/PlaceholderPage";
import type { ErrorDescription } from "../problems";
import { useConfirmation } from "../useConfirmation";
import { DELETE_OPERATION, preferenceLabel, sizeLabel } from "./company";
import { forgetDeleted } from "./companyCache";
import { CompanyLoadFailure, FailureMessage } from "./CompanyLoadFailure";
import { describeCompanyError } from "./companyProblems";
import { PreferenceBadge } from "./PreferenceBadge";
import { PreferenceDialog } from "./PreferenceDialog";
import { CompanyApplications, CompanyContacts, sectionCard } from "./RelatedRecords";

const route = getRouteApi("/_app/companies/$companyId");

/** The delete question, from the server's effect: what the delete would really remove. */
export function describeDelete(effect: ConfirmationEffect): string {
  const contacts = effect.counts.contacts ?? 0;
  return contacts === 0
    ? m.company_delete_confirm_alone({ name: effect.name })
    : m.company_delete_confirm_with_contacts({ name: effect.name, contacts });
}

export function CompanyDetailPage() {
  const { companyId } = route.useParams();
  const company = useGetCompany(companyId, { query: { meta: { errorHandledLocally: true } } });
  if (company.data) {
    const reload = async () => (await company.refetch()).data;
    return <CompanyDetail company={company.data} onReload={reload} />;
  }
  if (company.isError) return <CompanyLoadFailure error={company.error} onRetry={() => void company.refetch()} />;
  return <p role="status">{m.loading()}</p>;
}

interface CompanyDetailProps {
  company: CompanyResponse;
  onReload: () => Promise<CompanyResponse | undefined>;
}

function CompanyDetail({ company, onReload }: CompanyDetailProps) {
  const [flagging, setFlagging] = useState(false);
  return (
    <>
      <TextLink to="/companies" className="inline-flex items-center gap-2 self-start">
        <BackIcon className="size-4" aria-hidden="true" />
        {m.company_back_to_list()}
      </TextLink>
      <div className="flex flex-col gap-3">
        <PageHeader title={company.name} eyebrow={m.company_eyebrow()} />
        <PreferenceBadge preference={company.preference} />
      </div>
      <div className="flex flex-wrap gap-3">
        <TextLink
          to="/companies/$companyId/edit"
          params={{ companyId: company.id }}
          className="inline-flex items-center gap-2 self-center"
        >
          <EditIcon className="size-4" aria-hidden="true" />
          {m.company_edit()}
        </TextLink>
        <Button variant="secondary" onPress={() => setFlagging(true)}>
          <FavouriteIcon className="size-4" aria-hidden="true" />
          {m.company_preference_change()}
        </Button>
        <DeleteCompany company={company} />
      </div>
      <PreferenceDialog
        company={company}
        isOpen={flagging}
        onClose={() => setFlagging(false)}
        onReload={onReload}
      />
      <div className="grid gap-6 lg:grid-cols-2">
        <CompanyFacts company={company} />
        <PreferenceSection company={company} />
        <NotesSection company={company} />
        <ProfileSection company={company} />
        <CompanyApplications companyId={company.id} count={company.applicationCount} />
        <CompanyContacts companyId={company.id} />
      </div>
    </>
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

function CompanyFacts({ company }: { company: CompanyResponse }) {
  const none = <span className="text-muted">{m.company_value_none()}</span>;
  // Website and careers page are http(s) only (checked by the server), so they can be links.
  const link = (href: string | null | undefined) => (href ? <ExternalLink href={href}>{href}</ExternalLink> : none);
  return (
    <section aria-labelledby="company-facts-heading" className={sectionCard}>
      <h2 id="company-facts-heading" className="text-h2">
        {m.company_facts_heading()}
      </h2>
      <dl className="flex flex-col gap-4">
        <Fact label={m.company_field_website()}>{link(company.website)}</Fact>
        <Fact label={m.company_field_careers_page()}>{link(company.careersPage)}</Fact>
        <Fact label={m.company_field_industry()}>{company.industry ?? none}</Fact>
        <Fact label={m.company_field_size()}>{company.size ? sizeLabel(company.size) : none}</Fact>
        <Fact label={m.company_field_locations()}>
          {company.locations.length > 0 ? company.locations.join(" · ") : none}
        </Fact>
      </dl>
    </section>
  );
}

function PreferenceSection({ company }: { company: CompanyResponse }) {
  return (
    <section aria-labelledby="company-preference-heading" className={sectionCard}>
      <h2 id="company-preference-heading" className="text-h2">
        {m.company_preference_heading()}
      </h2>
      <p className="font-semibold">{preferenceLabel(company.preference)}</p>
      {company.preferenceReason ? (
        <p className="whitespace-pre-line break-words">
          <span className="text-muted">{m.company_preference_reason_label()}: </span>
          {company.preferenceReason}
        </p>
      ) : null}
    </section>
  );
}

function NotesSection({ company }: { company: CompanyResponse }) {
  return (
    <section aria-labelledby="company-notes-heading" className={sectionCard}>
      <h2 id="company-notes-heading" className="text-h2">
        {m.company_field_research_notes()}
      </h2>
      {company.researchNotes ? (
        <Markdown>{company.researchNotes}</Markdown>
      ) : (
        <p className="text-muted">{m.company_notes_empty()}</p>
      )}
    </section>
  );
}

/** The AI-generated profile: untrusted text (it may quote web pages), so sanitised and marked as AI. */
function ProfileSection({ company }: { company: CompanyResponse }) {
  if (!company.profile) return null;
  const generatedAt = new Intl.DateTimeFormat(getLocale(), { dateStyle: "medium" }).format(
    new Date(company.profile.generatedAt),
  );
  return (
    <section aria-labelledby="company-profile-heading" className={sectionCard}>
      <h2 id="company-profile-heading" className="text-h2">
        {m.company_profile_heading()}
      </h2>
      <p className="text-muted">{m.company_profile_note({ date: generatedAt })}</p>
      <Markdown>{company.profile.markdown}</Markdown>
    </section>
  );
}

/** Delete with the server's two-step confirmation (ADR-0039); refused while applications exist. */
function DeleteCompany({ company }: { company: CompanyResponse }) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { confirmed, dialog } = useConfirmation();
  const [failure, setFailure] = useState<ErrorDescription | null>(null);
  const remove = useMutation({
    meta: { errorHandledLocally: true },
    mutationFn: () =>
      confirmed((options) => deleteCompany(company.id, options), {
        expect: { operation: DELETE_OPERATION, targets: [company.id] },
        describe: describeDelete,
        title: m.company_delete_title(),
        confirmLabel: m.company_delete_action(),
      }),
  });

  const start = () => {
    setFailure(null);
    remove.mutate(undefined, {
      onSuccess: (outcome) => {
        if (outcome.status !== "done") return;
        forgetDeleted(queryClient, company.id);
        void navigate({ to: "/companies" });
      },
      onError: (error) => setFailure(describeCompanyError(error)),
    });
  };

  return (
    <>
      <Button variant="secondary" onPress={start} isDisabled={remove.isPending}>
        <DeleteIcon className="size-4" aria-hidden="true" />
        {m.company_delete()}
      </Button>
      {failure ? (
        <div className="basis-full">
          <FailureMessage failure={failure} />
        </div>
      ) : null}
      {dialog}
    </>
  );
}
