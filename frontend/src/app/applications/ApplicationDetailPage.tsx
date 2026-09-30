// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { getRouteApi, useNavigate } from "@tanstack/react-router";
import { useEffect, useRef, useState } from "react";
import type { ConfirmationEffect } from "../../api/confirmation";
import {
  type ApplicationResponse,
  deleteApplication,
  useGetApplication,
  useSetApplicationUnread,
} from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { getLocale } from "../../paraglide/runtime.js";
import { BackIcon, Button, DeleteIcon, EditIcon, EmailIcon, ReadIcon, Tabs, TextLink } from "../../ui";
import { FailureMessage } from "../companies/CompanyLoadFailure";
import { PageHeader } from "../pages/PlaceholderPage";
import type { ErrorDescription } from "../problems";
import { useConfirmation } from "../useConfirmation";
import { ApplicationLoadFailure } from "./ApplicationLoadFailure";
import { ApplicationOverview, CompanyLink, StatusBadge } from "./ApplicationOverview";
import { DELETE_OPERATION } from "./application";
import { forgetDeletedApplication, storeSavedApplication } from "./applicationCache";
import { describeApplicationError } from "./applicationProblems";
import { type ApplicationTab, TABS, tabLabels } from "./tabs";

const route = getRouteApi("/_app/applications/$applicationId");

/** The effect's counts of what goes with an application (backend `DeleteApplicationUseCase`), in words. */
const CASCADE: readonly [key: string, words: (inputs: { count: number }) => string][] = [
  ["contactLinks", m.application_delete_contact_links],
  ["statusChanges", m.application_delete_status_changes],
  ["sources", m.application_delete_sources],
  ["snapshots", m.application_delete_snapshots],
];

/** What goes with the application, e.g. "2 sources and 1 status change"; undefined for nothing. */
function cascadeList(counts: Record<string, number>): string | undefined {
  const parts = CASCADE.flatMap(([key, words]) => {
    const count = counts[key] ?? 0;
    return count > 0 ? [words({ count })] : [];
  });
  if (parts.length === 0) return undefined;
  return new Intl.ListFormat(getLocale(), { type: "conjunction" }).format(parts);
}

/** The delete question, from the server's effect: the application and what goes with it. */
export function describeApplicationDelete(effect: ConfirmationEffect): string {
  const cascade = cascadeList(effect.counts);
  return cascade === undefined
    ? m.application_delete_confirm_alone({ title: effect.name })
    : m.application_delete_confirm_with({ title: effect.name, items: cascade });
}

export function ApplicationDetailPage() {
  const { applicationId } = route.useParams();
  const application = useGetApplication(applicationId, { query: { meta: { errorHandledLocally: true } } });
  useMarkReadWhenOpened(application.data);
  if (application.data) return <ApplicationDetail application={application.data} />;
  if (application.isError)
    return <ApplicationLoadFailure error={application.error} onRetry={() => void application.refetch()} />;
  return <p role="status">{m.loading()}</p>;
}

/**
 * Opening an unread application marks it read, once per visit: after the user marks it unread again
 * here, it stays unread. Read/unread has its own endpoint, so the version (and an open edit) is untouched.
 */
function useMarkReadWhenOpened(application: ApplicationResponse | undefined) {
  const queryClient = useQueryClient();
  const { mutate } = useSetApplicationUnread();
  const seen = useRef<string | undefined>(undefined);
  useEffect(() => {
    if (application === undefined || seen.current === application.id) return;
    seen.current = application.id;
    if (!application.unread) return;
    mutate(
      { id: application.id, data: { unread: false } },
      { onSuccess: (saved) => storeSavedApplication(queryClient, saved) },
    );
  }, [application, mutate, queryClient]);
}

function ApplicationDetail({ application }: { application: ApplicationResponse }) {
  const { tab } = route.useSearch();
  const navigate = route.useNavigate();
  const select = (next: ApplicationTab) =>
    void navigate({ search: { tab: next === "overview" ? undefined : next }, replace: true });
  return (
    <>
      <TextLink to="/applications" className="inline-flex items-center gap-2 self-start">
        <BackIcon className="size-4" aria-hidden="true" />
        {m.application_back_to_list()}
      </TextLink>
      <div className="flex flex-col gap-3">
        <PageHeader title={application.title} eyebrow={m.application_eyebrow()} />
        <div className="flex flex-wrap items-center gap-3">
          <CompanyLink companyId={application.companyId} />
          <StatusBadge status={application.status} />
          {application.unread ? (
            <span className="rounded bg-accent-soft px-2 py-0.5 font-semibold text-body">
              {m.application_unread_badge()}
            </span>
          ) : null}
        </div>
      </div>
      <div className="flex flex-wrap gap-3">
        <TextLink
          to="/applications/$applicationId/edit"
          params={{ applicationId: application.id }}
          className="inline-flex items-center gap-2 self-center"
        >
          <EditIcon className="size-4" aria-hidden="true" />
          {m.company_edit()}
        </TextLink>
        <ToggleUnread application={application} />
        <DeleteApplication application={application} />
      </div>
      <Tabs<ApplicationTab>
        label={m.application_tabs_label()}
        tabs={TABS.map(({ id, ready }) => ({ id, label: tabLabels[id](), isDisabled: !ready }))}
        selected={tab ?? "overview"}
        onSelect={select}
      >
        <ApplicationOverview application={application} />
      </Tabs>
    </>
  );
}

/** Marks the application unread (to come back to it) or read again. */
function ToggleUnread({ application }: { application: ApplicationResponse }) {
  const queryClient = useQueryClient();
  const change = useSetApplicationUnread();
  const toggle = () =>
    change.mutate(
      { id: application.id, data: { unread: !application.unread } },
      { onSuccess: (saved) => storeSavedApplication(queryClient, saved) },
    );
  return (
    <Button variant="secondary" onPress={toggle} isDisabled={change.isPending}>
      {application.unread ? (
        <ReadIcon className="size-4" aria-hidden="true" />
      ) : (
        <EmailIcon className="size-4" aria-hidden="true" />
      )}
      {application.unread ? m.application_mark_read() : m.application_mark_unread()}
    </Button>
  );
}

/** Delete with the server's two-step confirmation (ADR-0039): sources, history and links go with it. */
function DeleteApplication({ application }: { application: ApplicationResponse }) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { confirmed, dialog } = useConfirmation();
  const [failure, setFailure] = useState<ErrorDescription | null>(null);
  const remove = useMutation({
    meta: { errorHandledLocally: true },
    mutationFn: () =>
      confirmed((options) => deleteApplication(application.id, options), {
        expect: { operation: DELETE_OPERATION, targets: [application.id] },
        describe: describeApplicationDelete,
        title: m.application_delete_title(),
        confirmLabel: m.application_delete_action(),
      }),
  });

  const start = () => {
    setFailure(null);
    remove.mutate(undefined, {
      onSuccess: (outcome) => {
        if (outcome.status !== "done") return;
        forgetDeletedApplication(queryClient, application.id);
        void navigate({ to: "/applications" });
      },
      onError: (error) => setFailure(describeApplicationError(error)),
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
