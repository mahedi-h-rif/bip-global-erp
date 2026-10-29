-- The company cash book: job income and expenses by day, and the partners' withdrawals.
-- source: MANUAL (typed in), IMPORT (from a monthly accounts sheet), IMPORT_YEAR (a month total from the
-- yearly sheet). Re-importing a month replaces only its earlier imported rows.

CREATE TABLE account_income (
    id                   BIGSERIAL PRIMARY KEY,
    organization_id      BIGINT         NOT NULL REFERENCES organization (id),
    entry_date           DATE           NOT NULL,
    customer_name        VARCHAR(255),
    mobile               VARCHAR(40),
    details              TEXT,
    amount               NUMERIC(18, 2) NOT NULL,
    paid                 NUMERIC(18, 2) NOT NULL DEFAULT 0,
    source               VARCHAR(20)    NOT NULL DEFAULT 'MANUAL',
    created_by           VARCHAR(255),
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_account_income_org ON account_income (organization_id, entry_date);

CREATE TABLE account_expense (
    id                   BIGSERIAL PRIMARY KEY,
    organization_id      BIGINT         NOT NULL REFERENCES organization (id),
    entry_date           DATE           NOT NULL,
    description          TEXT           NOT NULL,
    category             VARCHAR(30)    NOT NULL DEFAULT 'OTHER',
    amount               NUMERIC(18, 2) NOT NULL,
    source               VARCHAR(20)    NOT NULL DEFAULT 'MANUAL',
    created_by           VARCHAR(255),
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_account_expense_org ON account_expense (organization_id, entry_date);

CREATE TABLE partner (
    id                   BIGSERIAL PRIMARY KEY,
    organization_id      BIGINT         NOT NULL REFERENCES organization (id),
    name                 VARCHAR(255)   NOT NULL,
    active               BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- What a partner may draw in a year; the balance is this minus what was withdrawn.
CREATE TABLE partner_entitlement (
    id                   BIGSERIAL PRIMARY KEY,
    partner_id           BIGINT         NOT NULL REFERENCES partner (id) ON DELETE CASCADE,
    year                 INT            NOT NULL,
    amount               NUMERIC(18, 2) NOT NULL,
    UNIQUE (partner_id, year)
);

CREATE TABLE partner_withdrawal (
    id                   BIGSERIAL PRIMARY KEY,
    organization_id      BIGINT         NOT NULL REFERENCES organization (id),
    partner_id           BIGINT         NOT NULL REFERENCES partner (id) ON DELETE CASCADE,
    entry_date           DATE           NOT NULL,
    amount               NUMERIC(18, 2) NOT NULL,
    reference            VARCHAR(255),
    approved_by          VARCHAR(255),
    notes                TEXT,
    source               VARCHAR(20)    NOT NULL DEFAULT 'MANUAL',
    created_by           VARCHAR(255),
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_partner_withdrawal_org ON partner_withdrawal (organization_id, entry_date);
