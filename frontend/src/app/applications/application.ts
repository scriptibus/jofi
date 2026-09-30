// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type {
  ApplicationDetailsRequest,
  ApplicationResponse,
  OfferDto,
  PayBandDto,
} from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { fieldErrorsOf, VIOLATION_MESSAGES } from "../companies/company";
import type {
  Confidence,
  EmploymentType,
  FormOfAddress,
  HowApplied,
  PayPeriod,
  PaySource,
  Seniority,
  Tone,
} from "./labels";

/** Operation of the application delete's confirmation (backend `Application.DELETE_OPERATION`, ADR-0039). */
export const DELETE_OPERATION = "applications.delete";

/** The currency a new pay band or salary starts with; the user can change it. */
const DEFAULT_CURRENCY = "EUR";

/**
 * The application form's state: text as typed (`""` for "not set"), numbers as the number fields hold
 * them (`NaN` for empty), dates as `YYYY-MM-DD`. Flat, so every field has one name.
 */
export interface ApplicationFormValues {
  title: string;
  companyId: string;
  location: string;
  remoteShare: number;
  employmentType: EmploymentType | "";
  seniority: Seniority | "";
  deadline: string;
  howApplied: HowApplied | "";
  portalNotes: string;
  payMin: number;
  payMax: number;
  payCurrency: string;
  payPeriod: PayPeriod;
  paySource: PaySource;
  payBasis: string;
  payConfidence: Confidence | "";
  postingLanguage: string;
  applicationLanguage: string;
  formOfAddress: FormOfAddress | "";
  tone: Tone | "";
  offerSalary: number;
  offerCurrency: string;
  offerPeriod: PayPeriod;
  offerBonus: string;
  offerBenefits: string;
  offerRemoteShare: number;
  offerVacationDays: number;
  offerNoticePeriod: string;
  offerStartDate: string;
  offerAnswerBy: string;
}

/**
 * Each form field's name in the request, as the server names it in a 400's violations
 * (backend `ApplicationProblems.apiName`), so a server error shows next to its field.
 */
export const FIELD_NAMES: Record<keyof ApplicationFormValues, string> = {
  title: "title",
  companyId: "companyId",
  location: "location",
  remoteShare: "remoteShare",
  employmentType: "employmentType",
  seniority: "seniority",
  deadline: "deadline",
  howApplied: "howApplied",
  portalNotes: "portalNotes",
  payMin: "payBand.min",
  payMax: "payBand.max",
  payCurrency: "payBand.currency",
  payPeriod: "payBand.period",
  paySource: "payBand.source",
  payBasis: "payBand.estimateBasis",
  payConfidence: "payBand.estimateConfidence",
  postingLanguage: "languageAndTone.postingLanguage",
  applicationLanguage: "languageAndTone.applicationLanguage",
  formOfAddress: "languageAndTone.formOfAddress",
  tone: "languageAndTone.tone",
  offerSalary: "offer.salary.amount",
  offerCurrency: "offer.salary.currency",
  offerPeriod: "offer.salary.period",
  offerBonus: "offer.bonus",
  offerBenefits: "offer.benefits",
  offerRemoteShare: "offer.remoteShare",
  offerVacationDays: "offer.vacationDays",
  offerNoticePeriod: "offer.noticePeriod",
  offerStartDate: "offer.startDate",
  offerAnswerBy: "offer.answerBy",
};

const number = (value: number | null | undefined) => value ?? Number.NaN;

export function formValues(application?: ApplicationResponse, companyId?: string): ApplicationFormValues {
  const band = application?.payBand;
  const language = application?.languageAndTone;
  const offer = application?.offer;
  return {
    title: application?.title ?? "",
    companyId: application?.companyId ?? companyId ?? "",
    location: application?.location ?? "",
    remoteShare: number(application?.remoteShare),
    employmentType: application?.employmentType ?? "",
    seniority: application?.seniority ?? "",
    deadline: application?.deadline ?? "",
    howApplied: application?.howApplied ?? "",
    portalNotes: application?.portalNotes ?? "",
    payMin: number(band?.min),
    payMax: number(band?.max),
    payCurrency: band?.currency ?? DEFAULT_CURRENCY,
    payPeriod: band?.period ?? "YEAR",
    paySource: band?.source ?? "POSTING",
    payBasis: band?.estimateBasis ?? "",
    payConfidence: band?.estimateConfidence ?? "",
    postingLanguage: language?.postingLanguage ?? "",
    applicationLanguage: language?.applicationLanguage ?? "",
    formOfAddress: language?.formOfAddress ?? "",
    tone: language?.tone ?? "",
    offerSalary: number(offer?.salary?.amount),
    offerCurrency: offer?.salary?.currency ?? DEFAULT_CURRENCY,
    offerPeriod: offer?.salary?.period ?? "YEAR",
    offerBonus: offer?.bonus ?? "",
    offerBenefits: offer?.benefits ?? "",
    offerRemoteShare: number(offer?.remoteShare),
    offerVacationDays: number(offer?.vacationDays),
    offerNoticePeriod: offer?.noticePeriod ?? "",
    offerStartDate: offer?.startDate ?? "",
    offerAnswerBy: offer?.answerBy ?? "",
  };
}

/** Trimmed text, or null when empty: the server stores no blank or padded text (ADR-0041). */
function optional(text: string): string | null {
  const trimmed = text.trim();
  return trimmed === "" ? null : trimmed;
}

const amount = (value: number) => (Number.isNaN(value) ? null : value);
const choice = <T extends string>(value: T | "") => (value === "" ? null : value);

/**
 * Whether the form describes a pay band: an amount, or the basis of an estimate. Otherwise there is none,
 * whatever currency, period or source the (hidden defaults of the) form hold.
 */
export function hasPayBand(values: ApplicationFormValues): boolean {
  const estimated = values.paySource === "ESTIMATED" && values.payBasis.trim() !== "";
  return !Number.isNaN(values.payMin) || !Number.isNaN(values.payMax) || estimated;
}

function payBand(values: ApplicationFormValues): PayBandDto | null {
  if (!hasPayBand(values)) return null;
  const estimated = values.paySource === "ESTIMATED";
  return {
    min: amount(values.payMin),
    max: amount(values.payMax),
    currency: values.payCurrency.trim().toUpperCase(),
    period: values.payPeriod,
    source: values.paySource,
    estimateBasis: estimated ? optional(values.payBasis) : null,
    estimateConfidence: estimated ? choice(values.payConfidence) : null,
  };
}

/** The offer, or null when no detail of it is filled in (the server counts an empty offer as none). */
function offer(values: ApplicationFormValues): OfferDto | null {
  const details: OfferDto = {
    salary: Number.isNaN(values.offerSalary)
      ? null
      : {
          amount: values.offerSalary,
          currency: values.offerCurrency.trim().toUpperCase(),
          period: values.offerPeriod,
        },
    bonus: optional(values.offerBonus),
    benefits: optional(values.offerBenefits),
    remoteShare: amount(values.offerRemoteShare),
    vacationDays: amount(values.offerVacationDays),
    noticePeriod: optional(values.offerNoticePeriod),
    startDate: optional(values.offerStartDate),
    answerBy: optional(values.offerAnswerBy),
  };
  return Object.values(details).every((value) => value === null) ? null : details;
}

/** The full details for create and update: PUT replaces everything, so every field is sent. */
export function toDetailsRequest(values: ApplicationFormValues): ApplicationDetailsRequest {
  return {
    title: values.title.trim(),
    companyId: values.companyId,
    location: optional(values.location),
    remoteShare: amount(values.remoteShare),
    employmentType: choice(values.employmentType),
    seniority: choice(values.seniority),
    deadline: optional(values.deadline),
    howApplied: choice(values.howApplied),
    portalNotes: optional(values.portalNotes),
    payBand: payBand(values),
    languageAndTone: {
      postingLanguage: optional(values.postingLanguage),
      applicationLanguage: optional(values.applicationLanguage),
      formOfAddress: choice(values.formOfAddress),
      tone: choice(values.tone),
    },
    offer: offer(values),
  };
}

/** Looks like an ISO 4217 code (three letters); the server upper-cases it and has the final say. */
export function isCurrencyCode(text: string): boolean {
  return /^[A-Za-z]{3}$/.test(text.trim());
}

const applicationViolationMessages: Readonly<Record<string, () => string>> = {
  ...VIOLATION_MESSAGES,
  OUT_OF_RANGE: m.application_violation_out_of_range,
  TOO_PRECISE: m.application_violation_too_precise,
  INVALID_CURRENCY: m.application_violation_invalid_currency,
  INVALID_LANGUAGE: m.application_violation_invalid_language,
  MIN_ABOVE_MAX: m.application_violation_min_above_max,
  NOT_FOUND: m.contact_violation_company_not_found,
};

/** A 400's violations by the request's field names, e.g. `payBand.max`; undefined otherwise. */
export function applicationFieldErrorsOf(error: unknown): Record<string, string> | undefined {
  return fieldErrorsOf(error, applicationViolationMessages);
}
