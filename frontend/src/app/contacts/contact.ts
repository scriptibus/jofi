// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type {
  ContactChannelDto,
  ContactChannelDtoKind,
  ContactDetailsRequest,
  ContactResponse,
} from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { fieldErrorsOf, isWebAddress, VIOLATION_MESSAGES } from "../companies/company";
import { mailtoHref } from "./channels";

export type ChannelKind = ContactChannelDtoKind;

export const CHANNEL_KINDS: readonly ChannelKind[] = ["EMAIL", "PHONE", "WEB", "OTHER"];

/** The server keeps at most this many channels per contact (backend `ContactDetails.MAX_CHANNELS`). */
export const MAX_CHANNELS = 20;

/** Operation of the contact delete's confirmation (backend `Contact.DELETE_OPERATION`, ADR-0039). */
export const DELETE_OPERATION = "contacts.delete";

const kindLabels: Record<ChannelKind, () => string> = {
  EMAIL: m.contact_channel_email,
  PHONE: m.contact_channel_phone,
  WEB: m.contact_channel_web,
  OTHER: m.contact_channel_other,
};

export function channelKindLabel(kind: ChannelKind): string {
  return kindLabels[kind]();
}

/** One channel row of the form; `key` only tells React which row is which while rows move. */
export interface ChannelFormValues {
  key: number;
  kind: ChannelKind;
  value: string;
  label: string;
}

/** The contact form's state: plain strings as typed, `""` for "not set". */
export interface ContactFormValues {
  name: string;
  role: string;
  /** The company's id, or `""` for none. */
  companyId: string;
  channels: ChannelFormValues[];
  relationshipNotes: string;
}

let nextKey = 0;

export function newChannel(kind: ChannelKind = "EMAIL", channel?: ContactChannelDto): ChannelFormValues {
  nextKey += 1;
  return { key: nextKey, kind, value: channel?.value ?? "", label: channel?.label ?? "" };
}

export function formValues(contact?: ContactResponse, companyId?: string): ContactFormValues {
  return {
    name: contact?.name ?? "",
    role: contact?.role ?? "",
    companyId: contact?.companyId ?? companyId ?? "",
    channels: contact?.channels.map((channel) => newChannel(channel.kind, channel)) ?? [],
    relationshipNotes: contact?.relationshipNotes ?? "",
  };
}

/** Trimmed text, or null when empty: the server stores no blank or padded text (ADR-0041). */
function optional(text: string): string | null {
  const trimmed = text.trim();
  return trimmed === "" ? null : trimmed;
}

/**
 * The full details for create and update: PUT replaces everything, so every field is sent. Every
 * channel row goes, blank ones too (the server drops those), so that a violation's position
 * (`channels[2].value`) is the row's position in the form.
 */
export function toDetailsRequest(values: ContactFormValues): ContactDetailsRequest {
  return {
    name: values.name.trim(),
    role: optional(values.role),
    companyId: values.companyId === "" ? null : values.companyId,
    channels: values.channels.map((channel) => ({
      kind: channel.kind,
      value: channel.value.trim(),
      label: optional(channel.label),
    })),
    relationshipNotes: optional(values.relationshipNotes),
  };
}

/** The server's rule for a channel value of `kind`, checked before sending; the server has the final say. */
export function channelValueProblem(kind: ChannelKind, value: string): string | null {
  const trimmed = value.trim();
  if (trimmed === "") return null;
  if (kind === "EMAIL" && mailtoHref(trimmed) === undefined) return m.contact_violation_invalid_email();
  if (kind === "PHONE" && !/\p{Nd}/u.test(trimmed)) return m.contact_violation_invalid_phone();
  if (kind === "WEB" && !isWebAddress(trimmed)) return m.company_violation_invalid_url();
  return null;
}

const contactViolationMessages: Readonly<Record<string, () => string>> = {
  ...VIOLATION_MESSAGES,
  INVALID_EMAIL: m.contact_violation_invalid_email,
  INVALID_PHONE: m.contact_violation_invalid_phone,
  NOT_FOUND: m.contact_violation_company_not_found,
};

/** A 400's violations by the request's field names, e.g. `channels[2].value`; undefined otherwise. */
export function contactFieldErrorsOf(error: unknown): Record<string, string> | undefined {
  return fieldErrorsOf(error, contactViolationMessages);
}

/** The form field name of a channel's value or label, matching the server's violation names. */
export function channelField(position: number, part: "value" | "label"): string {
  return `channels[${position}].${part}`;
}
