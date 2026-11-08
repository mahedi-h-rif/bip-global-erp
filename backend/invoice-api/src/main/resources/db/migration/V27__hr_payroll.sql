-- Phase D: HR & payroll (quotation item 1): employees with ID expiry, leave ledger, holidays, salary advances,
-- monthly payroll runs with payslips.

CREATE TABLE employee (
    id                BIGSERIAL PRIMARY KEY,
    organization_id   BIGINT         NOT NULL REFERENCES organization (id),
    code              VARCHAR(30)    NOT NULL,
    name              VARCHAR(255)   NOT NULL,
    name_ar           VARCHAR(255),
    nationality       VARCHAR(2)     NOT NULL DEFAULT 'SA',
    id_number         VARCHAR(40),            -- national ID / iqama
    id_expiry         DATE,
    passport_number   VARCHAR(40),
    passport_expiry   DATE,
    job_title         VARCHAR(150),
    department        VARCHAR(100),
    join_date         DATE           NOT NULL,
    end_date          DATE,
    status            VARCHAR(20)    NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE, TERMINATED
    phone             VARCHAR(40),
    email             VARCHAR(200),
    bank_name         VARCHAR(100),
    iban              VARCHAR(40),
    basic             NUMERIC(18, 2) NOT NULL DEFAULT 0,
    housing           NUMERIC(18, 2) NOT NULL DEFAULT 0,
    transport         NUMERIC(18, 2) NOT NULL DEFAULT 0,
    other_allowance   NUMERIC(18, 2) NOT NULL DEFAULT 0,
    gosi_pct          NUMERIC(5, 2)  NOT NULL DEFAULT 0,   -- employee share, % of basic + housing (simplified)
    annual_leave_days NUMERIC(5, 1)  NOT NULL DEFAULT 21,
    notes             VARCHAR(2000),
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_employee_code ON employee (organization_id, lower(code));

CREATE TABLE holiday (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT       NOT NULL REFERENCES organization (id),
    holiday_date    DATE         NOT NULL,
    name            VARCHAR(200) NOT NULL,
    UNIQUE (organization_id, holiday_date)
);

CREATE TABLE leave_entry (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT       NOT NULL REFERENCES organization (id),
    employee_id     BIGINT       NOT NULL REFERENCES employee (id) ON DELETE CASCADE,
    leave_type      VARCHAR(20)  NOT NULL,    -- ANNUAL, SICK, UNPAID, OTHER
    start_date      DATE         NOT NULL,
    end_date        DATE         NOT NULL,
    days            NUMERIC(6, 1) NOT NULL,   -- working days, excluding company holidays
    notes           VARCHAR(1000),
    created_by      VARCHAR(255),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (end_date >= start_date)
);
CREATE INDEX idx_leave_employee ON leave_entry (employee_id, start_date);

CREATE TABLE salary_advance (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT         NOT NULL REFERENCES organization (id),
    employee_id     BIGINT         NOT NULL REFERENCES employee (id) ON DELETE CASCADE,
    advance_date    DATE           NOT NULL,
    amount          NUMERIC(18, 2) NOT NULL CHECK (amount > 0),
    installment     NUMERIC(18, 2) NOT NULL CHECK (installment > 0),   -- deducted per payroll until repaid
    notes           VARCHAR(1000),
    created_by      VARCHAR(255),
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE TABLE payroll_run (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT       NOT NULL REFERENCES organization (id),
    number          VARCHAR(40)  NOT NULL,     -- PAY-2026-11
    year            INT          NOT NULL,
    month           INT          NOT NULL CHECK (month BETWEEN 1 AND 12),
    status          VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',   -- DRAFT, APPROVED, PAID
    payment_method  VARCHAR(30),
    approved_by     VARCHAR(255),
    approved_at     TIMESTAMPTZ,
    paid_by         VARCHAR(255),
    paid_at         TIMESTAMPTZ,
    created_by      VARCHAR(255),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (organization_id, year, month)
);

CREATE TABLE payroll_line (
    id                      BIGSERIAL PRIMARY KEY,
    payroll_run_id          BIGINT         NOT NULL REFERENCES payroll_run (id) ON DELETE CASCADE,
    employee_id             BIGINT         NOT NULL REFERENCES employee (id),
    basic                   NUMERIC(18, 2) NOT NULL DEFAULT 0,
    housing                 NUMERIC(18, 2) NOT NULL DEFAULT 0,
    transport               NUMERIC(18, 2) NOT NULL DEFAULT 0,
    other_allowance         NUMERIC(18, 2) NOT NULL DEFAULT 0,
    overtime                NUMERIC(18, 2) NOT NULL DEFAULT 0,
    additions               NUMERIC(18, 2) NOT NULL DEFAULT 0,
    gosi                    NUMERIC(18, 2) NOT NULL DEFAULT 0,
    advance_deduction       NUMERIC(18, 2) NOT NULL DEFAULT 0,
    unpaid_leave_days       NUMERIC(6, 1)  NOT NULL DEFAULT 0,
    unpaid_leave_deduction  NUMERIC(18, 2) NOT NULL DEFAULT 0,
    other_deductions        NUMERIC(18, 2) NOT NULL DEFAULT 0,
    days_worked             NUMERIC(6, 1)  NOT NULL DEFAULT 30,
    net                     NUMERIC(18, 2) NOT NULL DEFAULT 0,
    notes                   VARCHAR(1000),
    UNIQUE (payroll_run_id, employee_id)
);
