-- SPDX-FileCopyrightText: 2026 Jofi contributors
-- SPDX-License-Identifier: AGPL-3.0-or-later

-- The `applications` context's aggregate root (spec §6.1, #76) and its contact links. Check constraints
-- mirror the domain (`ApplicationDetails`, `PayBand`, `LanguageAndTone`, `DeclineReason`, `OfferDetails`,
-- `Application`) and enum names, but are never stricter and use no locale-dependent logic (ADR-0041):
-- whitespace is ASCII `[ \t\n\r\f\v]`, never `\s`, and letter ranges are ASCII code points. Every
-- constraint is named, so repositories can map violations by name. The status pipeline (#77), sources
-- (#78), interviews (#79) and tasks (#80) come with their own migrations.

CREATE TABLE application (
    id                      uuid          CONSTRAINT application_pk PRIMARY KEY,
    -- A company with applications cannot be deleted (ADR-0041): `HasApplications` in the companies context.
    company_id              uuid          NOT NULL
        CONSTRAINT application_company_fk REFERENCES company (id) ON DELETE RESTRICT,
    title                   text          NOT NULL CONSTRAINT application_title_valid
        CHECK (title ~ '[^ \t\n\r\f\v]' AND title !~ '^[ \t\n\r\f\v]|[ \t\n\r\f\v]$' AND char_length(title) <= 300),
    location                text          CONSTRAINT application_location_valid CHECK (
        location ~ '[^ \t\n\r\f\v]' AND location !~ '^[ \t\n\r\f\v]|[ \t\n\r\f\v]$' AND char_length(location) <= 200),
    -- Percent of remote work.
    remote_share            smallint      CONSTRAINT application_remote_share_valid CHECK (remote_share BETWEEN 0 AND 100),
    employment_type         text          CONSTRAINT application_employment_type_valid CHECK (employment_type IN (
        'FULL_TIME', 'PART_TIME', 'CONTRACT', 'FREELANCE', 'INTERNSHIP', 'WORKING_STUDENT', 'APPRENTICESHIP', 'OTHER')),
    seniority               text          CONSTRAINT application_seniority_valid
        CHECK (seniority IN ('ENTRY', 'JUNIOR', 'MID', 'SENIOR', 'LEAD', 'PRINCIPAL', 'EXECUTIVE')),
    deadline                date,
    how_applied             text          CONSTRAINT application_how_applied_valid
        CHECK (how_applied IN ('PORTAL', 'EMAIL', 'REFERRAL', 'OTHER')),
    -- Markdown written by the user.
    portal_notes            text          CONSTRAINT application_portal_notes_valid CHECK (
        portal_notes ~ '[^ \t\n\r\f\v]' AND portal_notes !~ '^[ \t\n\r\f\v]|[ \t\n\r\f\v]$'
        AND char_length(portal_notes) <= 50000),

    -- Pay band: amounts with two decimals (the domain's `Amount`), at least one of min and max, an ISO
    -- 4217 currency, a period and a source; an estimate names its basis and confidence.
    pay_min                 numeric(12, 2) CONSTRAINT application_pay_min_valid CHECK (pay_min >= 0),
    pay_max                 numeric(12, 2) CONSTRAINT application_pay_max_valid CHECK (pay_max >= 0),
    pay_currency            text          CONSTRAINT application_pay_currency_valid CHECK (pay_currency ~ '^[A-Z]{3}$'),
    pay_period              text          CONSTRAINT application_pay_period_valid
        CHECK (pay_period IN ('HOUR', 'DAY', 'MONTH', 'YEAR')),
    pay_source              text          CONSTRAINT application_pay_source_valid
        CHECK (pay_source IN ('POSTING', 'RECRUITER', 'ESTIMATED')),
    pay_estimate_basis      text          CONSTRAINT application_pay_estimate_basis_valid CHECK (
        pay_estimate_basis ~ '[^ \t\n\r\f\v]' AND pay_estimate_basis !~ '^[ \t\n\r\f\v]|[ \t\n\r\f\v]$'
        AND char_length(pay_estimate_basis) <= 2000),
    pay_estimate_confidence text          CONSTRAINT application_pay_estimate_confidence_valid
        CHECK (pay_estimate_confidence IN ('LOW', 'MEDIUM', 'HIGH')),

    -- Language & tone: BCP 47 tags as entered; a null application language follows the posting's.
    posting_language        text          CONSTRAINT application_posting_language_valid CHECK (
        posting_language ~ '^[A-Za-z]{2,3}(-[A-Za-z0-9]{1,8})*$' AND char_length(posting_language) <= 35),
    application_language    text          CONSTRAINT application_application_language_valid CHECK (
        application_language ~ '^[A-Za-z]{2,3}(-[A-Za-z0-9]{1,8})*$' AND char_length(application_language) <= 35),
    form_of_address         text          CONSTRAINT application_form_of_address_valid
        CHECK (form_of_address IN ('DU', 'SIE', 'NEUTRAL')),
    tone                    text          CONSTRAINT application_tone_valid CHECK (tone IN ('PERSONAL', 'PROFESSIONAL')),

    decline_category        text          CONSTRAINT application_decline_category_valid CHECK (decline_category IN (
        'SALARY', 'LOCATION', 'REMOTE_POLICY', 'ROLE', 'COMPANY', 'SKILLS', 'TIMING', 'OTHER_OFFER',
        'POSITION_FILLED', 'NO_REASON_GIVEN', 'OTHER')),
    -- Markdown written by the user.
    decline_reason          text          CONSTRAINT application_decline_reason_valid CHECK (
        decline_reason ~ '[^ \t\n\r\f\v]' AND decline_reason !~ '^[ \t\n\r\f\v]|[ \t\n\r\f\v]$'
        AND char_length(decline_reason) <= 5000),

    -- Offer details (the comparison is M6); the salary is an amount, a currency and a period together.
    offer_salary            numeric(12, 2) CONSTRAINT application_offer_salary_valid CHECK (offer_salary >= 0),
    offer_salary_currency   text          CONSTRAINT application_offer_salary_currency_valid
        CHECK (offer_salary_currency ~ '^[A-Z]{3}$'),
    offer_salary_period     text          CONSTRAINT application_offer_salary_period_valid
        CHECK (offer_salary_period IN ('HOUR', 'DAY', 'MONTH', 'YEAR')),
    offer_bonus             text          CONSTRAINT application_offer_bonus_valid CHECK (
        offer_bonus ~ '[^ \t\n\r\f\v]' AND offer_bonus !~ '^[ \t\n\r\f\v]|[ \t\n\r\f\v]$'
        AND char_length(offer_bonus) <= 5000),
    offer_benefits          text          CONSTRAINT application_offer_benefits_valid CHECK (
        offer_benefits ~ '[^ \t\n\r\f\v]' AND offer_benefits !~ '^[ \t\n\r\f\v]|[ \t\n\r\f\v]$'
        AND char_length(offer_benefits) <= 5000),
    offer_remote_share      smallint      CONSTRAINT application_offer_remote_share_valid
        CHECK (offer_remote_share BETWEEN 0 AND 100),
    offer_vacation_days     smallint      CONSTRAINT application_offer_vacation_days_valid
        CHECK (offer_vacation_days BETWEEN 0 AND 366),
    offer_notice_period     text          CONSTRAINT application_offer_notice_period_valid CHECK (
        offer_notice_period ~ '[^ \t\n\r\f\v]' AND offer_notice_period !~ '^[ \t\n\r\f\v]|[ \t\n\r\f\v]$'
        AND char_length(offer_notice_period) <= 200),
    offer_start_date        date,
    offer_answer_by         date,

    -- A scanner-created entry the user has not opened yet.
    unread                  boolean       NOT NULL DEFAULT false,
    -- Placeholders for scoring (M2): 0 to 5 with one decimal.
    want_score              numeric(2, 1) CONSTRAINT application_want_score_valid CHECK (want_score BETWEEN 0 AND 5),
    fit_score               numeric(2, 1) CONSTRAINT application_fit_score_valid CHECK (fit_score BETWEEN 0 AND 5),
    -- Optimistic locking: every change increments it, and a change based on an older version is rejected.
    version                 bigint        NOT NULL DEFAULT 0 CONSTRAINT application_version_valid CHECK (version >= 0),
    created_at              timestamptz   NOT NULL,
    updated_at              timestamptz   NOT NULL,

    CONSTRAINT application_pay_band_complete CHECK (
        num_nulls(pay_currency, pay_period, pay_source) IN (0, 3)
        AND (pay_currency IS NULL) = (pay_min IS NULL AND pay_max IS NULL)),
    CONSTRAINT application_pay_band_ordered CHECK (pay_min <= pay_max),
    CONSTRAINT application_pay_estimate_complete CHECK (
        num_nulls(pay_estimate_basis, pay_estimate_confidence) IN (0, 2)
        AND (pay_source IS NOT DISTINCT FROM 'ESTIMATED') = (pay_estimate_basis IS NOT NULL)),
    CONSTRAINT application_decline_reason_needs_category
        CHECK (decline_reason IS NULL OR decline_category IS NOT NULL),
    CONSTRAINT application_offer_salary_complete
        CHECK (num_nulls(offer_salary, offer_salary_currency, offer_salary_period) IN (0, 3)),
    CONSTRAINT application_updated_after_created CHECK (updated_at >= created_at)
);

-- The applications of a company: list filter, application counts and the `ON DELETE RESTRICT` check.
CREATE INDEX application_company_idx ON application (company_id);

-- Fuzzy title search and duplicate detection (spec §8.4); pg_trgm ignores case, so no lower() is needed.
CREATE INDEX application_title_trgm_idx ON application USING gin (title gin_trgm_ops);

-- The few unread entries (inbox badge, filter).
CREATE INDEX application_unread_idx ON application (id) WHERE unread;

-- Contacts linked to an application (#90). A link goes with either side (ADR-0041: link tables to
-- `contact` cascade, so deleting a contact or its company is never blocked). The domain limits an
-- application to 50 links; the table does not, so it is never stricter.
CREATE TABLE application_contact (
    application_id uuid NOT NULL
        CONSTRAINT application_contact_application_fk REFERENCES application (id) ON DELETE CASCADE,
    contact_id     uuid NOT NULL
        CONSTRAINT application_contact_contact_fk REFERENCES contact (id) ON DELETE CASCADE,
    CONSTRAINT application_contact_pk PRIMARY KEY (application_id, contact_id)
);

-- The applications of a contact: "linked applications per contact" and the contact delete's cascade.
CREATE INDEX application_contact_contact_idx ON application_contact (contact_id);
