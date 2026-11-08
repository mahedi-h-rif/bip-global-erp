-- ERP: warehouses, per-warehouse stock with a movement ledger, transfers and delivery notes.

CREATE TABLE warehouse (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT       NOT NULL REFERENCES organization (id),
    code            VARCHAR(20)  NOT NULL,
    name            VARCHAR(200) NOT NULL,
    location        VARCHAR(500),
    is_default      BOOLEAN      NOT NULL DEFAULT FALSE,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_warehouse_code ON warehouse (organization_id, lower(code));

-- Stock is opt-in per product: a logistics company mostly sells services, which never carry stock.
ALTER TABLE product ADD COLUMN track_stock BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE product ADD COLUMN cost_price NUMERIC(18, 6);
ALTER TABLE product ADD COLUMN low_stock_threshold NUMERIC(18, 3);
ALTER TABLE product ADD COLUMN unit_code VARCHAR(10) NOT NULL DEFAULT 'PCE';

CREATE TABLE stock_level (
    warehouse_id BIGINT         NOT NULL REFERENCES warehouse (id),
    product_id   BIGINT         NOT NULL REFERENCES product (id),
    quantity     NUMERIC(18, 3) NOT NULL DEFAULT 0,
    PRIMARY KEY (warehouse_id, product_id)
);

-- Every change to stock_level writes one row here, so the level can always be explained.
CREATE TABLE stock_movement (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT         NOT NULL REFERENCES organization (id),
    product_id      BIGINT         NOT NULL REFERENCES product (id),
    warehouse_id    BIGINT         NOT NULL REFERENCES warehouse (id),
    quantity        NUMERIC(18, 3) NOT NULL,           -- signed: + in, - out
    balance_after   NUMERIC(18, 3) NOT NULL,
    kind            VARCHAR(20)    NOT NULL,           -- OPENING, ADJUST, SALE, RETURN, TRANSFER_IN, TRANSFER_OUT, DELIVERY, RECEIPT
    ref_type        VARCHAR(30),
    ref_id          BIGINT,
    ref_number      VARCHAR(60),
    note            VARCHAR(1000),
    created_by      VARCHAR(255),
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_stock_movement_product ON stock_movement (organization_id, product_id, created_at);
CREATE INDEX idx_stock_movement_ref ON stock_movement (ref_type, ref_id);

-- The warehouse an invoice ships from (stock is deducted when it is issued).
ALTER TABLE invoice ADD COLUMN warehouse_id BIGINT REFERENCES warehouse (id);

CREATE TABLE stock_transfer (
    id                BIGSERIAL PRIMARY KEY,
    organization_id   BIGINT       NOT NULL REFERENCES organization (id),
    number            VARCHAR(40)  NOT NULL,
    from_warehouse_id BIGINT       NOT NULL REFERENCES warehouse (id),
    to_warehouse_id   BIGINT       NOT NULL REFERENCES warehouse (id),
    transfer_date     DATE         NOT NULL,
    note              VARCHAR(1000),
    created_by        VARCHAR(255),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (from_warehouse_id <> to_warehouse_id)
);
CREATE UNIQUE INDEX uq_stock_transfer_number ON stock_transfer (organization_id, number);

CREATE TABLE stock_transfer_line (
    id          BIGSERIAL PRIMARY KEY,
    transfer_id BIGINT         NOT NULL REFERENCES stock_transfer (id) ON DELETE CASCADE,
    product_id  BIGINT         NOT NULL REFERENCES product (id),
    quantity    NUMERIC(18, 3) NOT NULL CHECK (quantity > 0)
);

CREATE TABLE delivery_note (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT        NOT NULL REFERENCES organization (id),
    number          VARCHAR(40)   NOT NULL,
    invoice_id      BIGINT        REFERENCES invoice (id),
    customer_id     BIGINT        REFERENCES customer (id),
    client_name     VARCHAR(500),
    delivery_address VARCHAR(1000),
    warehouse_id    BIGINT        REFERENCES warehouse (id),
    delivery_date   DATE          NOT NULL,
    status          VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',   -- DRAFT, DELIVERED, CANCELLED
    driver_name     VARCHAR(200),
    driver_phone    VARCHAR(40),
    vehicle_no      VARCHAR(60),
    received_by     VARCHAR(200),
    received_at     TIMESTAMPTZ,
    note            VARCHAR(2000),
    created_by      VARCHAR(255),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_delivery_note_number ON delivery_note (organization_id, number);
CREATE INDEX idx_delivery_note_invoice ON delivery_note (invoice_id);

CREATE TABLE delivery_note_line (
    id               BIGSERIAL PRIMARY KEY,
    delivery_note_id BIGINT         NOT NULL REFERENCES delivery_note (id) ON DELETE CASCADE,
    position         INT            NOT NULL,
    invoice_line_id  BIGINT         REFERENCES invoice_line (id),
    product_id       BIGINT         REFERENCES product (id),
    name             VARCHAR(1000)  NOT NULL,
    quantity         NUMERIC(18, 3) NOT NULL CHECK (quantity > 0),
    unit_code        VARCHAR(10)    NOT NULL DEFAULT 'PCE'
);
