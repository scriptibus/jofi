// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { m } from "../../paraglide/messages.js";
import { AddIcon, Button, DeleteIcon, MoveDownIcon, MoveUpIcon, SegmentedControl, TextField } from "../../ui";
import type { FieldErrors } from "../auth/useFieldErrors";
import {
  CHANNEL_KINDS,
  type ChannelFormValues,
  type ChannelKind,
  channelField,
  channelKindLabel,
  channelValueProblem,
  MAX_CHANNELS,
  newChannel,
} from "./contact";

const valueLabels: Record<ChannelKind, () => string> = {
  EMAIL: m.contact_channel_value_email,
  PHONE: m.contact_channel_value_phone,
  WEB: m.contact_channel_value_web,
  OTHER: m.contact_channel_value_other,
};

const valueInputModes: Record<ChannelKind, "email" | "tel" | "url" | "text"> = {
  EMAIL: "email",
  PHONE: "tel",
  WEB: "url",
  OTHER: "text",
};

export interface ChannelsEditorProps {
  channels: ChannelFormValues[];
  onChange: (channels: ChannelFormValues[]) => void;
  fieldErrors: FieldErrors;
}

/**
 * The contact's channels in the user's order: add, remove and move rows; each has a kind, a value and
 * an optional label. Field names follow the server's violations (`channels[2].value`), so a server
 * error shows next to its row; moving or removing rows drops those errors, since positions change.
 */
export function ChannelsEditor({ channels, onChange, fieldErrors }: ChannelsEditorProps) {
  const dropChannelErrors = () => {
    const kept = Object.entries(fieldErrors.errors).filter(([field]) => !field.startsWith("channels"));
    fieldErrors.set(Object.fromEntries(kept));
  };
  const update = (position: number, change: Partial<ChannelFormValues>) =>
    onChange(channels.map((channel, index) => (index === position ? { ...channel, ...change } : channel)));
  const move = (position: number, offset: number) => {
    const next = [...channels];
    const [moved] = next.splice(position, 1);
    if (moved === undefined) return;
    next.splice(position + offset, 0, moved);
    dropChannelErrors();
    onChange(next);
  };
  const remove = (position: number) => {
    dropChannelErrors();
    onChange(channels.filter((_, index) => index !== position));
  };
  const listError = fieldErrors.errors.channels;

  return (
    <fieldset className="flex flex-col gap-3" aria-describedby="contact-channels-hint">
      <legend className="font-semibold text-body">{m.contact_field_channels()}</legend>
      <p id="contact-channels-hint" className="text-muted">
        {m.contact_field_channels_hint({ max: MAX_CHANNELS })}
      </p>
      {listError ? <p className="font-medium text-bad">{listError}</p> : null}
      {channels.length > 0 ? (
        <ol className="flex flex-col gap-3">
          {channels.map((channel, position) => (
            <li key={channel.key}>
              <ChannelRow
                channel={channel}
                position={position}
                count={channels.length}
                fieldErrors={fieldErrors}
                onChange={(change) => update(position, change)}
                onMove={(offset) => move(position, offset)}
                onRemove={() => remove(position)}
              />
            </li>
          ))}
        </ol>
      ) : null}
      <Button
        variant="secondary"
        className="self-start"
        isDisabled={channels.length >= MAX_CHANNELS}
        onPress={() => onChange([...channels, newChannel()])}
      >
        <AddIcon className="size-4" aria-hidden="true" />
        {m.contact_channel_add()}
      </Button>
    </fieldset>
  );
}

interface ChannelRowProps {
  channel: ChannelFormValues;
  position: number;
  count: number;
  fieldErrors: FieldErrors;
  onChange: (change: Partial<ChannelFormValues>) => void;
  onMove: (offset: number) => void;
  onRemove: () => void;
}

function ChannelRow({ channel, position, count, fieldErrors, onChange, onMove, onRemove }: ChannelRowProps) {
  const valueName = channelField(position, "value");
  const labelName = channelField(position, "label");
  const number = position + 1;
  return (
    <fieldset className="flex flex-col gap-3 rounded border border-line p-4">
      <legend className="px-1 font-data text-eyebrow text-muted uppercase">
        {m.contact_channel_group({ position: number })}
      </legend>
      <SegmentedControl<ChannelKind>
        label={m.contact_channel_kind()}
        value={channel.kind}
        onChange={fieldErrors.clearing(valueName, (kind: ChannelKind) => onChange({ kind }))}
        options={CHANNEL_KINDS.map((kind) => ({ value: kind, label: channelKindLabel(kind) }))}
      />
      <div className="grid gap-3 sm:grid-cols-2">
        <TextField
          name={valueName}
          label={valueLabels[channel.kind]()}
          inputMode={valueInputModes[channel.kind]}
          value={channel.value}
          onChange={fieldErrors.clearing(valueName, (value: string) => onChange({ value }))}
          validate={(value) => channelValueProblem(channel.kind, value)}
        />
        <TextField
          name={labelName}
          label={m.contact_channel_label()}
          description={m.contact_channel_label_hint()}
          value={channel.label}
          onChange={fieldErrors.clearing(labelName, (label: string) => onChange({ label }))}
          maxLength={100}
        />
      </div>
      <div className="flex flex-wrap gap-2">
        <Button
          variant="secondary"
          aria-label={m.contact_channel_move_up({ position: number })}
          isDisabled={position === 0}
          onPress={() => onMove(-1)}
        >
          <MoveUpIcon className="size-4" aria-hidden="true" />
        </Button>
        <Button
          variant="secondary"
          aria-label={m.contact_channel_move_down({ position: number })}
          isDisabled={position === count - 1}
          onPress={() => onMove(1)}
        >
          <MoveDownIcon className="size-4" aria-hidden="true" />
        </Button>
        <Button
          variant="secondary"
          aria-label={m.contact_channel_remove_named({ position: number })}
          onPress={onRemove}
        >
          <DeleteIcon className="size-4" aria-hidden="true" />
          {m.contact_channel_remove()}
        </Button>
      </div>
    </fieldset>
  );
}
