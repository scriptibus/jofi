// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { ContactChannelDto } from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { AppLink, EmailIcon, ExternalLink, type Icon, OtherChannelIcon, PhoneIcon, WebIcon } from "../../ui";
import { mailtoHref, telHref, webHref } from "./channels";
import { type ChannelKind, channelKindLabel } from "./contact";

const kindIcons: Record<ChannelKind, Icon> = {
  EMAIL: EmailIcon,
  PHONE: PhoneIcon,
  WEB: WebIcon,
  OTHER: OtherChannelIcon,
};

function linkFor(channel: ContactChannelDto): { href: string; external: boolean } | undefined {
  if (channel.kind === "EMAIL") return optionalLink(mailtoHref(channel.value), false);
  if (channel.kind === "PHONE") return optionalLink(telHref(channel.value), false);
  if (channel.kind === "WEB") return optionalLink(webHref(channel.value), true);
  return undefined;
}

function optionalLink(href: string | undefined, external: boolean) {
  return href === undefined ? undefined : { href, external };
}

/**
 * The value as entered. Mail and phone links open the device's own apps (`AppLink`); a web link opens
 * another site (`ExternalLink`: new tab, `noopener noreferrer nofollow`) and is never fetched. OTHER, and
 * a value that does not fit its kind, stay plain text.
 */
function ChannelValue({ channel }: { channel: ContactChannelDto }) {
  const link = linkFor(channel);
  if (link === undefined) return <span className="break-all">{channel.value}</span>;
  const Link = link.external ? ExternalLink : AppLink;
  return (
    <Link href={link.href} className="self-start break-all">
      {channel.value}
    </Link>
  );
}

/** A contact's channels in the user's order: kind, optional label (plain text) and the value. */
export function ChannelList({ channels }: { channels: ContactChannelDto[] }) {
  if (channels.length === 0) return <p className="text-muted">{m.contact_channels_empty()}</p>;
  return (
    <ul className="flex flex-col divide-y divide-line">
      {channels.map((channel) => {
        const KindIcon = kindIcons[channel.kind];
        return (
          <li key={`${channel.kind}:${channel.value}`} className="flex items-start gap-3 py-2">
            <KindIcon className="mt-1 size-4 shrink-0 text-muted" aria-hidden="true" />
            <div className="flex min-w-0 flex-col gap-1">
              <span className="font-data text-eyebrow text-muted uppercase">
                {channelKindLabel(channel.kind)}
                {channel.label ? ` · ${channel.label}` : ""}
              </span>
              <ChannelValue channel={channel} />
            </div>
          </li>
        );
      })}
    </ul>
  );
}
