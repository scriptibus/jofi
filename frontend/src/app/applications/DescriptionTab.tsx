// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useQueries } from "@tanstack/react-query";
import { type ReactNode, useState } from "react";
import {
  type ApplicationResponse,
  type ApplicationSourceResponse,
  type DescriptionSnapshotSummaryResponse,
  getListDescriptionSnapshotsQueryOptions,
} from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { EmptyState, OfflineIcon, Select, type SelectGroup } from "../../ui";
import { sectionCard } from "../companies/RelatedRecords";
import { SourceLink } from "./ApplicationOverview";
import { CompareVersions, type Comparison } from "./DescriptionCompare";
import { LoadFailure, VersionList, VersionText, versionName } from "./DescriptionVersions";
import { defaultComparison } from "./descriptionDiff";
import { formatInstant, formatInstantDate } from "./format";
import { sourceKindLabels } from "./labels";
import { RecordDescription } from "./RecordDescription";

/** "Added from a link (jobs.example.com)": the kind, and the host when the source has a web address. */
function sourceName(source: ApplicationSourceResponse): string {
  const kind = sourceKindLabels[source.kind]();
  if (!source.originalUrl) return kind;
  try {
    return m.application_description_source_name({ kind, host: new URL(source.originalUrl).host });
  } catch {
    return kind;
  }
}

function Card({
  id,
  title,
  wide,
  children,
}: {
  id: string;
  title?: string;
  wide?: boolean;
  children: ReactNode;
}) {
  return (
    <section aria-labelledby={id} className={`${sectionCard} min-w-0 ${wide ? "lg:col-span-2" : ""}`}>
      {title ? (
        <h2 id={id} className="text-h2">
          {title}
        </h2>
      ) : null}
      {children}
    </section>
  );
}

/** The Description tab (spec §6.1, §6.3): each source's versions of the job description, read-only sources. */
export function DescriptionTab({ application }: { application: ApplicationResponse }) {
  const [first] = application.sources;
  if (first === undefined)
    return (
      <EmptyState title={m.application_description_no_sources_heading()}>
        {m.application_description_no_sources()}
      </EmptyState>
    );
  return <DescriptionHistory application={application} initialSource={first} />;
}

/** Every version of every source (for comparing), with each version's number within its source. */
function catalogOf(
  sources: readonly ApplicationSourceResponse[],
  lists: readonly DescriptionSnapshotSummaryResponse[][],
) {
  const names = new Map<string, string>();
  const groups: SelectGroup[] = sources.map((source, index) => {
    const title = sourceName(source);
    const options = (lists[index] ?? []).map((version, position) => {
      const name = versionName(position + 1);
      names.set(version.id, sources.length > 1 ? `${name} (${title})` : name);
      const date = formatInstant(version.capturedAt);
      return { id: version.id, label: m.application_description_compare_option({ version: name, date }) };
    });
    return { id: source.id, title, options };
  });
  return { names, groups: groups.filter((group) => group.options.length > 0) };
}

/** Every source's versions, loaded side by side: the compare pickers offer all of them. */
function useVersionLists(application: ApplicationResponse) {
  return useQueries({
    queries: application.sources.map((source) =>
      getListDescriptionSnapshotsQueryOptions(application.id, source.id, {
        query: { meta: { errorHandledLocally: true } },
      }),
    ),
  });
}

function DescriptionHistory({
  application,
  initialSource,
}: {
  application: ApplicationResponse;
  initialSource: ApplicationSourceResponse;
}) {
  const { sources } = application;
  const [sourceId, setSourceId] = useState(initialSource.id);
  const [shown, setShown] = useState<string | null>(null);
  const [chosen, setChosen] = useState<Comparison | null>(null);
  const lists = useVersionLists(application);
  const source = sources.find((each) => each.id === sourceId) ?? initialSource;
  const list = lists[sources.indexOf(source)];
  const versions = list?.data?.snapshots ?? [];
  const everyList = lists.map((query) => query.data?.snapshots ?? []);
  const catalog = catalogOf(sources, everyList);
  const shownIndex = versions.findIndex((version) => version.id === shown);
  const current = shownIndex >= 0 ? shownIndex : versions.length - 1;
  const shownVersion = versions[current];
  const others = everyList.filter((_, index) => sources[index] !== source).flat();
  const comparison =
    chosen && catalog.names.has(chosen.from) && catalog.names.has(chosen.to)
      ? chosen
      : defaultComparison(versions, others);

  const chooseSource = (id: string) => {
    setSourceId(id);
    setShown(null);
    setChosen(null);
  };
  const recorded = (id: string) => {
    setShown(id);
    setChosen(null);
  };

  return (
    <div className="grid gap-6 lg:grid-cols-2">
      <Card id="application-description-source-heading" title={m.application_description_source()}>
        {sources.length > 1 ? (
          <Select
            label={m.application_description_source()}
            placeholder={m.application_description_source()}
            groups={[
              { id: "sources", options: sources.map((each) => ({ id: each.id, label: sourceName(each) })) },
            ]}
            value={source.id}
            onChange={chooseSource}
          />
        ) : null}
        <SourceFacts source={source} />
        <RecordDescription
          key={source.id}
          applicationId={application.id}
          sourceId={source.id}
          versionCount={versions.length}
          onRecorded={recorded}
        />
      </Card>
      <Card id="application-description-versions-heading" title={m.application_description_versions()}>
        {list?.isError ? (
          <LoadFailure
            error={list.error}
            message={m.application_description_versions_failed()}
            onRetry={() => void list.refetch()}
          />
        ) : list?.data === undefined ? (
          <p role="status">{m.loading()}</p>
        ) : (
          <VersionList versions={versions} shown={shownVersion?.id ?? null} onShow={setShown} />
        )}
      </Card>
      {shownVersion ? (
        <Card id="application-description-text-heading" wide>
          <VersionText
            applicationId={application.id}
            version={shownVersion}
            number={current + 1}
            lang={application.languageAndTone.postingLanguage ?? undefined}
          />
        </Card>
      ) : null}
      <Card
        id="application-description-compare-heading"
        title={m.application_description_compare_heading()}
        wide
      >
        {comparison ? (
          <CompareVersions
            applicationId={application.id}
            groups={catalog.groups}
            names={catalog.names}
            comparison={comparison}
            onChange={setChosen}
          />
        ) : (
          <p className="text-muted">{m.application_description_compare_need_two()}</p>
        )}
      </Card>
    </div>
  );
}

/** Where the source is and since when it is offline, if it is: its versions stay readable here. */
function SourceFacts({ source }: { source: ApplicationSourceResponse }) {
  return (
    <div className="flex flex-col gap-1">
      <p className="font-semibold">{sourceKindLabels[source.kind]()}</p>
      {source.originalUrl ? <SourceLink url={source.originalUrl} /> : null}
      <p className="text-muted">
        {m.application_source_found({ date: formatInstantDate(source.discoveredAt) })}
      </p>
      {source.offlineSince ? (
        <p className="inline-flex items-center gap-2 font-semibold">
          <OfflineIcon className="size-4" aria-hidden="true" />
          {m.application_source_offline({ date: formatInstantDate(source.offlineSince) })}
        </p>
      ) : null}
    </div>
  );
}
