-- Phase H (Odoo / Oracle benchmark, docs/ERP-BENCHMARK.md): fleet, trips, customer credit control, CRM pipeline,
-- recurring contract invoices.

CREATE TABLE vehicle (
    id                 BIGSERIAL PRIMARY KEY,
    organization_id    BIGINT        NOT NULL REFERENCES organization (id),
    plate              VARCHAR(30)   NOT NULL,
    name               VARCHAR(150)  NOT NULL,            -- make / model
    vehicle_type       VARCHAR(30)   NOT NULL DEFAULT 'TRUCK',  -- TRUCK, VAN, PICKUP, CAR, TRAILER, FORKLIFT
    capacity           VARCHAR(60),
    model_year         INT,
    driver_employee_id BIGINT        REFERENCES employee (id) ON DELETE SET NULL,
    istimara_expiry    DATE,                               -- registration
    insurance_expiry   DATE,
    mvpi_expiry        DATE,                               -- periodic inspection (Fahas)
    odometer           NUMERIC(12, 1),
    active             BOOLEAN       NOT NULL DEFAULT TRUE,
    notes              VARCHAR(1000),
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_vehicle_plate ON vehicle (organization_id, lower(plate));

CREATE TABLE vehicle_log (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT         NOT NULL REFERENCES organization (id),
    vehicle_id      BIGINT         NOT NULL REFERENCES vehicle (id) ON DELETE CASCADE,
    log_date        DATE           NOT NULL,
    kind            VARCHAR(20)    NOT NULL,            -- FUEL, MAINTENANCE, OTHER
    amount          NUMERIC(18, 2) NOT NULL CHECK (amount >= 0),
    liters          NUMERIC(10, 2),
    odometer        NUMERIC(12, 1),
    description     VARCHAR(500),
    expense_id      BIGINT,                             -- the account_expense row it posted
    created_by      VARCHAR(255),
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE TABLE trip (
    id                 BIGSERIAL PRIMARY KEY,
    organization_id    BIGINT        NOT NULL REFERENCES organization (id),
    number             VARCHAR(40)   NOT NULL,
    vehicle_id         BIGINT        NOT NULL REFERENCES vehicle (id),
    driver_employee_id BIGINT        REFERENCES employee (id),
    trip_date          DATE          NOT NULL,
    origin             VARCHAR(200),
    destination        VARCHAR(200),
    status             VARCHAR(20)   NOT NULL DEFAULT 'PLANNED',  -- PLANNED, IN_TRANSIT, COMPLETED, CANCELLED
    start_odometer     NUMERIC(12, 1),
    end_odometer       NUMERIC(12, 1),
    notes              VARCHAR(1000),
    created_by         VARCHAR(255),
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_trip_number ON trip (organization_id, number);

CREATE TABLE trip_delivery (
    trip_id          BIGINT NOT NULL REFERENCES trip (id) ON DELETE CASCADE,
    delivery_note_id BIGINT NOT NULL REFERENCES delivery_note (id),
    PRIMARY KEY (trip_id, delivery_note_id)
);

CREATE TABLE trip_cost (
    id          BIGSERIAL PRIMARY KEY,
    trip_id     BIGINT         NOT NULL REFERENCES trip (id) ON DELETE CASCADE,
    kind        VARCHAR(20)    NOT NULL,     -- FUEL, TOLL, DRIVER_ALLOWANCE, LOADING, OTHER
    amount      NUMERIC(18, 2) NOT NULL CHECK (amount > 0),
    description VARCHAR(300),
    expense_id  BIGINT
);

ALTER TABLE delivery_note ADD COLUMN vehicle_id BIGINT REFERENCES vehicle (id);

-- Customer credit control.
ALTER TABLE customer ADD COLUMN credit_limit NUMERIC(18, 2);            -- null / 0 = no limit
ALTER TABLE customer ADD COLUMN payment_terms_days INT;                  -- default due date

-- CRM pipeline.
CREATE TABLE lead (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT         NOT NULL REFERENCES organization (id),
    title           VARCHAR(300)   NOT NULL,
    company         VARCHAR(300),
    contact_name    VARCHAR(200),
    phone           VARCHAR(40),
    email           VARCHAR(200),
    source          VARCHAR(60),
    stage           VARCHAR(20)    NOT NULL DEFAULT 'NEW',   -- NEW, QUALIFIED, PROPOSAL, WON, LOST
    expected_value  NUMERIC(18, 2),
    expected_close  DATE,
    owner           VARCHAR(255),
    notes           VARCHAR(4000),
    lost_reason     VARCHAR(500),
    customer_id     BIGINT         REFERENCES customer (id),
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- Recurring contract billing: the scheduler makes a draft invoice each period.
CREATE TABLE recurring_invoice (
    id               BIGSERIAL PRIMARY KEY,
    organization_id  BIGINT        NOT NULL REFERENCES organization (id),
    customer_id      BIGINT        NOT NULL REFERENCES customer (id),
    name             VARCHAR(200)  NOT NULL,
    kind             VARCHAR(20)   NOT NULL DEFAULT 'STANDARD',
    lines            TEXT          NOT NULL,      -- JSON [{name, quantity, unitPrice, vatRate, productId}]
    interval_months  INT           NOT NULL DEFAULT 1 CHECK (interval_months BETWEEN 1 AND 12),
    next_date        DATE          NOT NULL,
    end_date         DATE,
    active           BOOLEAN       NOT NULL DEFAULT TRUE,
    last_invoice_id  BIGINT        REFERENCES invoice (id),
    created_by       VARCHAR(255),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);
