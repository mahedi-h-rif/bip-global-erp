-- Products, payment tracking (dues) and customer reminders.

CREATE TABLE product (
    id                   BIGSERIAL PRIMARY KEY,
    organization_id      BIGINT        NOT NULL REFERENCES organization (id),
    name                 VARCHAR(1000) NOT NULL,
    unit_price           NUMERIC(18, 6) NOT NULL,
    active               BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_product_org ON product (organization_id, active);
CREATE UNIQUE INDEX uq_product_active_name ON product (organization_id, lower(name)) WHERE active;

-- The product a line was picked from. The line keeps its own name and price, so issued invoices never change.
ALTER TABLE invoice_line ADD COLUMN product_id BIGINT REFERENCES product (id);
CREATE INDEX idx_invoice_line_product ON invoice_line (product_id);

-- Internal payment terms; not part of the ZATCA XML.
ALTER TABLE invoice ADD COLUMN due_date DATE;
CREATE INDEX idx_invoice_original ON invoice (original_invoice_id);

CREATE TABLE invoice_payment (
    id                   BIGSERIAL PRIMARY KEY,
    organization_id      BIGINT        NOT NULL REFERENCES organization (id),
    invoice_id           BIGINT        NOT NULL REFERENCES invoice (id),
    amount               NUMERIC(18, 2) NOT NULL CHECK (amount > 0),
    paid_on              DATE          NOT NULL,
    method               VARCHAR(30)   NOT NULL,
    reference            VARCHAR(255),
    note                 VARCHAR(1000),
    created_by           VARCHAR(255),
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_payment_invoice ON invoice_payment (invoice_id);
CREATE INDEX idx_payment_org ON invoice_payment (organization_id, paid_on);

ALTER TABLE customer ADD COLUMN reminders_enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE customer ADD COLUMN last_reminder_at TIMESTAMPTZ;

CREATE TABLE notification_log (
    id                   BIGSERIAL PRIMARY KEY,
    organization_id      BIGINT        NOT NULL REFERENCES organization (id),
    customer_id          BIGINT        REFERENCES customer (id) ON DELETE SET NULL,
    channel              VARCHAR(20)   NOT NULL,
    trigger_type         VARCHAR(20)   NOT NULL,
    recipient            VARCHAR(255),
    status               VARCHAR(20)   NOT NULL,
    due_amount           NUMERIC(18, 2),
    message              TEXT,
    error                VARCHAR(2000),
    created_by           VARCHAR(255),
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_notification_org_time ON notification_log (organization_id, created_at DESC);
