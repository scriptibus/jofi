// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import {
  type FunnelDto,
  type PipelineOverviewResponse,
  useGetPipelineOverview,
} from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { AddIcon, ShareBar, TextLink } from "../../ui";
import { statusLabels } from "../applications/labels";
import { APPLIED_STATUSES, formatCount, formatRate, occupiedStatuses } from "./dashboard";
import { Widget, WidgetContent, widgetQuery } from "./Widget";

/** Where things stand now (ADR-0052): applications per status, each a link to the list filtered by it. */
export function PipelineWidget() {
  const query = useGetPipelineOverview({ query: widgetQuery });
  return (
    <Widget
      id="dashboard-pipeline"
      title={m.dashboard_pipeline_heading()}
      description={m.dashboard_pipeline_description()}
      footer={<TextLink to="/applications">{m.dashboard_pipeline_all()}</TextLink>}
    >
      <WidgetContent query={query} failed={m.dashboard_pipeline_failed()}>
        {(overview) => <Pipeline overview={overview} />}
      </WidgetContent>
    </Widget>
  );
}

function Pipeline({ overview }: { overview: PipelineOverviewResponse }) {
  const occupied = occupiedStatuses(overview.byStatus);
  if (occupied.length === 0)
    return (
      <div className="flex flex-col items-start gap-3">
        <p className="text-muted">{m.dashboard_pipeline_empty()}</p>
        <TextLink to="/applications/new" className="inline-flex items-center gap-2">
          <AddIcon className="size-4" aria-hidden="true" />
          {m.applications_new()}
        </TextLink>
      </div>
    );
  const largest = Math.max(...occupied.map(({ count }) => count));
  return (
    <>
      {overview.unread > 0 ? (
        <TextLink to="/applications" search={{ unread: true }} className="self-start">
          {m.dashboard_pipeline_unread({ count: formatCount(overview.unread) })}
        </TextLink>
      ) : null}
      <ul className="flex flex-col gap-3">
        {occupied.map(({ status, count }) => (
          <li key={status} className="flex flex-col gap-1">
            <div className="flex items-baseline justify-between gap-3">
              <TextLink to="/applications" search={{ status: [status] }}>
                {statusLabels[status]()}
              </TextLink>
              <span className="font-data">{formatCount(count)}</span>
            </div>
            <ShareBar value={count} max={largest} />
          </li>
        ))}
      </ul>
    </>
  );
}

/** What happened (ADR-0052): applied → interview → offer over the status history, and the response rate. */
export function FunnelWidget() {
  const query = useGetPipelineOverview({ query: widgetQuery });
  return (
    <Widget
      id="dashboard-funnel"
      title={m.dashboard_funnel_heading()}
      description={m.dashboard_funnel_description()}
      footer={
        <TextLink to="/applications" search={{ status: [...APPLIED_STATUSES] }}>
          {m.dashboard_funnel_applied_link()}
        </TextLink>
      }
    >
      <WidgetContent query={query} failed={m.dashboard_pipeline_failed()}>
        {({ funnel }) => <Funnel funnel={funnel} />}
      </WidgetContent>
    </Widget>
  );
}

function Funnel({ funnel }: { funnel: FunnelDto }) {
  if (funnel.applied === 0) return <p className="text-muted">{m.dashboard_funnel_empty()}</p>;
  const stages = [
    { label: m.dashboard_funnel_applied(), count: funnel.applied, rate: null },
    {
      label: m.dashboard_funnel_interview(),
      count: funnel.interviewed,
      rate: formatRate(funnel.interviewRate),
    },
    { label: m.dashboard_funnel_offer(), count: funnel.offered, rate: formatRate(funnel.offerRate) },
  ];
  const response = formatRate(funnel.responseRate);
  return (
    <>
      <dl className="flex flex-col gap-3">
        {stages.map(({ label, count, rate }) => (
          <div key={label} className="flex flex-col gap-1">
            <div className="flex items-baseline justify-between gap-3">
              <dt>{label}</dt>
              <dd className="font-data">
                {formatCount(count)}
                {rate ? <span className="text-muted"> · {m.dashboard_funnel_rate({ rate })}</span> : null}
              </dd>
            </div>
            <ShareBar value={count} max={funnel.applied} />
          </div>
        ))}
      </dl>
      <p className="flex flex-col gap-1 border-line border-t pt-4">
        <span className="font-data text-h2">{response ?? m.dashboard_rate_none()}</span>
        <span className="text-muted">
          {m.dashboard_funnel_response({
            responded: formatCount(funnel.responded),
            applied: formatCount(funnel.applied),
          })}
        </span>
      </p>
    </>
  );
}
