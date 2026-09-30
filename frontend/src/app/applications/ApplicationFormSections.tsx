// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { m } from "../../paraglide/messages.js";
import { SegmentedControl, TextArea, TextField } from "../../ui";
import { FIELD_NAMES, hasPayBand, isCurrencyCode } from "./application";
import { isLanguageTag, languageName } from "./format";
import { AmountField, ChoiceSegments, ChoiceSelect, FieldGroup, type SectionProps } from "./formFields";
import {
  confidenceLabels,
  formOfAddressLabels,
  optionsOf,
  type PayPeriod,
  type PaySource,
  paySourceLabels,
  periodLabels,
  toneLabels,
} from "./labels";

/** Days in a leap year: the most vacation days the server accepts. */
const MAX_VACATION_DAYS = 366;
const MAX_PERCENT = 100;

const currencyProblem = (value: string) =>
  isCurrencyCode(value) ? null : m.application_violation_invalid_currency();

function PeriodChoice({
  label,
  value,
  onChange,
}: {
  label: string;
  value: PayPeriod;
  onChange: (value: PayPeriod) => void;
}) {
  return (
    <SegmentedControl<PayPeriod>
      label={label}
      value={value}
      onChange={onChange}
      options={optionsOf(periodLabels).map(({ id, label: text }) => ({ value: id, label: text }))}
    />
  );
}

export function PayBandFields({ values, field }: SectionProps) {
  const estimated = values.paySource === "ESTIMATED";
  const belowMin = (max: number) =>
    !Number.isNaN(max) && !Number.isNaN(values.payMin) && max < values.payMin
      ? m.application_violation_min_above_max()
      : null;
  return (
    <FieldGroup legend={m.application_pay_heading()} hint={m.application_pay_hint()}>
      <div className="grid gap-4 sm:grid-cols-2">
        <AmountField
          name={FIELD_NAMES.payMin}
          label={m.application_pay_min()}
          value={values.payMin}
          onChange={field("payMin")}
          decimals={2}
        />
        <AmountField
          name={FIELD_NAMES.payMax}
          label={m.application_pay_max()}
          value={values.payMax}
          onChange={field("payMax")}
          decimals={2}
          validate={belowMin}
        />
      </div>
      <TextField
        name={FIELD_NAMES.payCurrency}
        label={m.application_currency()}
        description={m.application_currency_hint()}
        value={values.payCurrency}
        onChange={field("payCurrency")}
        maxLength={3}
        mono
        validate={(value) => (hasPayBand(values) ? currencyProblem(value) : null)}
      />
      <PeriodChoice label={m.application_period()} value={values.payPeriod} onChange={field("payPeriod")} />
      <SegmentedControl<PaySource>
        label={m.application_pay_source()}
        value={values.paySource}
        onChange={field("paySource")}
        options={optionsOf(paySourceLabels).map(({ id, label }) => ({ value: id, label }))}
      />
      {estimated ? (
        <>
          <TextArea
            name={FIELD_NAMES.payBasis}
            label={m.application_pay_basis()}
            description={m.application_pay_basis_hint()}
            value={values.payBasis}
            onChange={field("payBasis")}
            rows={3}
          />
          <ChoiceSelect
            name={FIELD_NAMES.payConfidence}
            label={m.application_pay_confidence()}
            labels={confidenceLabels}
            value={values.payConfidence}
            onChange={field("payConfidence")}
          />
        </>
      ) : null}
    </FieldGroup>
  );
}

/** A language tag field that names the language it recognises ("de-CH: Swiss High German"). */
function LanguageField({
  name,
  label,
  hint,
  value,
  onChange,
}: {
  name: string;
  label: string;
  hint: string;
  value: string;
  onChange: (value: string) => void;
}) {
  const language = value.trim() === "" ? undefined : languageName(value.trim());
  return (
    <TextField
      name={name}
      label={label}
      description={language ? m.application_language_recognised({ language }) : hint}
      value={value}
      onChange={onChange}
      maxLength={35}
      mono
      validate={(tag) =>
        tag.trim() === "" || isLanguageTag(tag) ? null : m.application_violation_invalid_language()
      }
    />
  );
}

export function LanguageFields({ values, field }: SectionProps) {
  return (
    <FieldGroup legend={m.application_language_heading()}>
      <div className="grid gap-4 sm:grid-cols-2">
        <LanguageField
          name={FIELD_NAMES.postingLanguage}
          label={m.application_field_posting_language()}
          hint={m.application_language_hint()}
          value={values.postingLanguage}
          onChange={field("postingLanguage")}
        />
        <LanguageField
          name={FIELD_NAMES.applicationLanguage}
          label={m.application_field_application_language()}
          hint={m.application_language_follow_hint()}
          value={values.applicationLanguage}
          onChange={field("applicationLanguage")}
        />
      </div>
      <ChoiceSegments
        label={m.application_field_form_of_address()}
        labels={formOfAddressLabels}
        value={values.formOfAddress}
        onChange={field("formOfAddress")}
        lang={{ DU: "de", SIE: "de" }}
      />
      <ChoiceSegments
        label={m.application_field_tone()}
        labels={toneLabels}
        value={values.tone}
        onChange={field("tone")}
      />
    </FieldGroup>
  );
}

export function OfferFields({ values, field }: SectionProps) {
  const hasSalary = !Number.isNaN(values.offerSalary);
  return (
    <FieldGroup legend={m.application_offer_heading()} hint={m.application_offer_hint()}>
      <div className="grid gap-4 sm:grid-cols-2">
        <AmountField
          name={FIELD_NAMES.offerSalary}
          label={m.application_offer_salary()}
          value={values.offerSalary}
          onChange={field("offerSalary")}
          decimals={2}
        />
        <TextField
          name={FIELD_NAMES.offerCurrency}
          label={m.application_currency()}
          value={values.offerCurrency}
          onChange={field("offerCurrency")}
          maxLength={3}
          mono
          validate={(value) => (hasSalary ? currencyProblem(value) : null)}
        />
      </div>
      <PeriodChoice
        label={m.application_offer_period()}
        value={values.offerPeriod}
        onChange={field("offerPeriod")}
      />
      <TextArea
        name={FIELD_NAMES.offerBonus}
        label={m.application_offer_bonus()}
        value={values.offerBonus}
        onChange={field("offerBonus")}
        rows={2}
      />
      <TextArea
        name={FIELD_NAMES.offerBenefits}
        label={m.application_offer_benefits()}
        value={values.offerBenefits}
        onChange={field("offerBenefits")}
        rows={3}
      />
      <div className="grid gap-4 sm:grid-cols-2">
        <AmountField
          name={FIELD_NAMES.offerRemoteShare}
          label={m.application_field_remote_share_percent()}
          value={values.offerRemoteShare}
          onChange={field("offerRemoteShare")}
          max={MAX_PERCENT}
          decimals={0}
        />
        <AmountField
          name={FIELD_NAMES.offerVacationDays}
          label={m.application_offer_vacation_days()}
          value={values.offerVacationDays}
          onChange={field("offerVacationDays")}
          max={MAX_VACATION_DAYS}
          decimals={0}
        />
      </div>
      <TextField
        name={FIELD_NAMES.offerNoticePeriod}
        label={m.application_offer_notice_period()}
        value={values.offerNoticePeriod}
        onChange={field("offerNoticePeriod")}
        maxLength={200}
      />
      <div className="grid gap-4 sm:grid-cols-2">
        <TextField
          name={FIELD_NAMES.offerStartDate}
          type="date"
          label={m.application_offer_start_date()}
          value={values.offerStartDate}
          onChange={field("offerStartDate")}
        />
        <TextField
          name={FIELD_NAMES.offerAnswerBy}
          type="date"
          label={m.application_offer_answer_by()}
          value={values.offerAnswerBy}
          onChange={field("offerAnswerBy")}
        />
      </div>
    </FieldGroup>
  );
}
