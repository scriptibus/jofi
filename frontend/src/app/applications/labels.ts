// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type {
  ApplicationResponseEmploymentType,
  ApplicationResponseHowApplied,
  ApplicationResponseSeniority,
  ApplicationResponseStatus,
  ApplicationSourceResponseKind,
  ChangeActorDtoKind,
  DeclineReasonDtoCategory,
  LanguageAndToneDtoFormOfAddress,
  LanguageAndToneDtoTone,
  PayBandDtoEstimateConfidence,
  PayBandDtoPeriod,
  PayBandDtoSource,
} from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";

// The words for the application enums (backend `ApplicationEnums`), in the user's language.

export type Status = ApplicationResponseStatus;
export type EmploymentType = NonNullable<ApplicationResponseEmploymentType>;
export type Seniority = NonNullable<ApplicationResponseSeniority>;
export type HowApplied = NonNullable<ApplicationResponseHowApplied>;
export type PayPeriod = PayBandDtoPeriod;
export type PaySource = PayBandDtoSource;
export type Confidence = NonNullable<PayBandDtoEstimateConfidence>;
export type FormOfAddress = NonNullable<LanguageAndToneDtoFormOfAddress>;
export type Tone = NonNullable<LanguageAndToneDtoTone>;
export type SourceKind = ApplicationSourceResponseKind;
export type DeclineCategory = DeclineReasonDtoCategory;
export type ActorKind = ChangeActorDtoKind;

export const statusLabels: Record<Status, () => string> = {
  DISCOVERED: m.application_status_discovered,
  SHORTLISTED: m.application_status_shortlisted,
  PREPARING: m.application_status_preparing,
  APPLIED: m.application_status_applied,
  INTERVIEWING: m.application_status_interviewing,
  OFFER: m.application_status_offer,
  ACCEPTED: m.application_status_accepted,
  REJECTED: m.application_status_rejected,
  WITHDRAWN: m.application_status_withdrawn,
  DECLINED: m.application_status_declined,
  GHOSTED: m.application_status_ghosted,
};

export const employmentTypeLabels: Record<EmploymentType, () => string> = {
  FULL_TIME: m.application_employment_full_time,
  PART_TIME: m.application_employment_part_time,
  CONTRACT: m.application_employment_contract,
  FREELANCE: m.application_employment_freelance,
  INTERNSHIP: m.application_employment_internship,
  WORKING_STUDENT: m.application_employment_working_student,
  APPRENTICESHIP: m.application_employment_apprenticeship,
  OTHER: m.application_other,
};

export const seniorityLabels: Record<Seniority, () => string> = {
  ENTRY: m.application_seniority_entry,
  JUNIOR: m.application_seniority_junior,
  MID: m.application_seniority_mid,
  SENIOR: m.application_seniority_senior,
  LEAD: m.application_seniority_lead,
  PRINCIPAL: m.application_seniority_principal,
  EXECUTIVE: m.application_seniority_executive,
};

export const howAppliedLabels: Record<HowApplied, () => string> = {
  PORTAL: m.application_how_portal,
  EMAIL: m.application_how_email,
  REFERRAL: m.application_how_referral,
  OTHER: m.application_other,
};

/** "Year" as a choice in the form. */
export const periodLabels: Record<PayPeriod, () => string> = {
  HOUR: m.application_period_hour,
  DAY: m.application_period_day,
  MONTH: m.application_period_month,
  YEAR: m.application_period_year,
};

/** "per year" after an amount. */
export const perPeriodLabels: Record<PayPeriod, () => string> = {
  HOUR: m.application_per_hour,
  DAY: m.application_per_day,
  MONTH: m.application_per_month,
  YEAR: m.application_per_year,
};

export const paySourceLabels: Record<PaySource, () => string> = {
  POSTING: m.application_pay_source_posting,
  RECRUITER: m.application_pay_source_recruiter,
  ESTIMATED: m.application_pay_source_estimated,
};

export const confidenceLabels: Record<Confidence, () => string> = {
  LOW: m.application_confidence_low,
  MEDIUM: m.application_confidence_medium,
  HIGH: m.application_confidence_high,
};

export const formOfAddressLabels: Record<FormOfAddress, () => string> = {
  DU: m.application_address_du,
  SIE: m.application_address_sie,
  NEUTRAL: m.application_address_neutral,
};

export const toneLabels: Record<Tone, () => string> = {
  PERSONAL: m.application_tone_personal,
  PROFESSIONAL: m.application_tone_professional,
};

export const sourceKindLabels: Record<SourceKind, () => string> = {
  SCANNER: m.application_source_scanner,
  URL: m.application_source_url,
  MANUAL_CHAT: m.application_source_chat,
};

export const declineCategoryLabels: Record<DeclineCategory, () => string> = {
  SALARY: m.application_decline_salary,
  LOCATION: m.application_decline_location,
  REMOTE_POLICY: m.application_decline_remote_policy,
  ROLE: m.application_decline_role,
  COMPANY: m.application_decline_company,
  SKILLS: m.application_decline_skills,
  TIMING: m.application_decline_timing,
  OTHER_OFFER: m.application_decline_other_offer,
  POSITION_FILLED: m.application_decline_position_filled,
  NO_REASON_GIVEN: m.application_decline_no_reason,
  OTHER: m.application_other,
};

/** Who made a change, as the object of "by …" ("by you", "von der KI"). */
export const actorLabels: Record<ActorKind, () => string> = {
  USER: m.application_actor_user,
  AI: m.application_actor_ai,
  SCANNER: m.application_actor_scanner,
  SYSTEM: m.application_actor_system,
  EXTERNAL_CLIENT: m.application_actor_external,
};

/** The options of `labels` in declaration order, for a Select or SegmentedControl. */
export function optionsOf<K extends string>(labels: Record<K, () => string>) {
  return (Object.keys(labels) as K[]).map((id) => ({ id, label: labels[id]() }));
}
