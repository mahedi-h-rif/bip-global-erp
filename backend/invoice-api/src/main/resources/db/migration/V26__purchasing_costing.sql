-- Phase B: purchasing (suppliers → purchase order → goods receipt (+ landed costs) → supplier invoice → payment)
-- Phase C: moving-average cost on every stock movement (the inventory audit report).

CREATE TABLE supplier (
    id                 BIGSERIAL PRIMARY KEY,
    organization_id    BIGINT        NOT NULL REFERENCES organization (id),
    code               VARCHAR(30)   NOT NULL,
    name               VARCHAR(300)  NOT NULL,
    name_ar            VARCHAR(300),
    vat_number         VARCHAR(30),
    cr_number          VARCHAR(30),
    address            VARCHAR(1000),
    city               VARCHAR(100),
    country            VARCHAR(2)    NOT NULL DEFAULT 'SA',
    phone              VARCHAR(40),
    email              VARCHAR(200),
    contact_person     VARCHAR(200),
    currency           VARCHAR(3)    NOT NULL DEFAULT 'SAR',
    payment_terms_days INT           NOT NULL DEFAULT 30,
    active             BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_supplier_code ON supplier (organization_id, lower(code));

CREATE TABLE purchase_order (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT         NOT NULL REFERENCES organization (id),
    number          VARCHAR(40)    NOT NULL,
    supplier_id     BIGINT         NOT NULL REFERENCES supplier (id),
    supplier_ref    VARCHAR(100),                       -- supplier's quotation / proforma no.
    order_date      DATE           NOT NULL,
    delivery_date   DATE,
    warehouse_id    BIGINT         REFERENCES warehouse (id),
    currency        VARCHAR(3)     NOT NULL DEFAULT 'SAR',
    exchange_rate   NUMERIC(18, 6) NOT NULL DEFAULT 1,    -- SAR per 1 unit of currency
    status          VARCHAR(30)    NOT NULL DEFAULT 'PENDING', -- PENDING, APPROVED, PARTIALLY_RECEIVED, RECEIVED, CANCELLED
    remarks         VARCHAR(4000),
    total_excl_vat  NUMERIC(18, 2) NOT NULL DEFAULT 0,  -- in the order currency
    vat_total       NUMERIC(18, 2) NOT NULL DEFAULT 0,
    total_incl_vat  NUMERIC(18, 2) NOT NULL DEFAULT 0,
    approved_by     VARCHAR(255),
    approved_at     TIMESTAMPTZ,
    created_by      VARCHAR(255),
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_purchase_order_number ON purchase_order (organization_id, number);

CREATE TABLE purchase_order_line (
    id                BIGSERIAL PRIMARY KEY,
    purchase_order_id BIGINT         NOT NULL REFERENCES purchase_order (id) ON DELETE CASCADE,
    position          INT            NOT NULL,
    product_id        BIGINT         REFERENCES product (id),
    name              VARCHAR(1000)  NOT NULL,
    quantity          NUMERIC(18, 3) NOT NULL CHECK (quantity > 0),
    unit_code         VARCHAR(10)    NOT NULL DEFAULT 'PCE',
    unit_cost         NUMERIC(18, 6) NOT NULL,
    vat_rate          NUMERIC(5, 2)  NOT NULL DEFAULT 15
);

-- Attachments on a requisition (supplier quotation, proforma, specs).
CREATE TABLE purchase_attachment (
    id                BIGSERIAL PRIMARY KEY,
    purchase_order_id BIGINT       NOT NULL REFERENCES purchase_order (id) ON DELETE CASCADE,
    file_name         VARCHAR(255) NOT NULL,
    content_type      VARCHAR(100) NOT NULL,
    data              BYTEA        NOT NULL,
    created_by        VARCHAR(255),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE goods_receipt (
    id                BIGSERIAL PRIMARY KEY,
    organization_id   BIGINT         NOT NULL REFERENCES organization (id),
    number            VARCHAR(40)    NOT NULL,
    purchase_order_id BIGINT         REFERENCES purchase_order (id),
    supplier_id       BIGINT         NOT NULL REFERENCES supplier (id),
    supplier_ref      VARCHAR(100),                     -- supplier's delivery note no.
    receipt_date      DATE           NOT NULL,
    warehouse_id      BIGINT         NOT NULL REFERENCES warehouse (id),
    currency          VARCHAR(3)     NOT NULL DEFAULT 'SAR',
    exchange_rate     NUMERIC(18, 6) NOT NULL DEFAULT 1,
    status            VARCHAR(20)    NOT NULL DEFAULT 'POSTED', -- POSTED, CANCELLED
    landed_cost_total NUMERIC(18, 2) NOT NULL DEFAULT 0,  -- SAR, spread over the lines by value
    remarks           VARCHAR(4000),
    total_excl_vat    NUMERIC(18, 2) NOT NULL DEFAULT 0,  -- document currency
    vat_total         NUMERIC(18, 2) NOT NULL DEFAULT 0,
    total_incl_vat    NUMERIC(18, 2) NOT NULL DEFAULT 0,
    created_by        VARCHAR(255),
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_goods_receipt_number ON goods_receipt (organization_id, number);

CREATE TABLE goods_receipt_line (
    id                  BIGSERIAL PRIMARY KEY,
    goods_receipt_id    BIGINT         NOT NULL REFERENCES goods_receipt (id) ON DELETE CASCADE,
    position            INT            NOT NULL,
    purchase_order_line_id BIGINT      REFERENCES purchase_order_line (id),
    product_id          BIGINT         REFERENCES product (id),
    name                VARCHAR(1000)  NOT NULL,
    quantity            NUMERIC(18, 3) NOT NULL CHECK (quantity > 0),
    unit_code           VARCHAR(10)    NOT NULL DEFAULT 'PCE',
    unit_cost           NUMERIC(18, 6) NOT NULL,          -- document currency
    vat_rate            NUMERIC(5, 2)  NOT NULL DEFAULT 15,
    landed_unit_cost    NUMERIC(18, 6)                    -- SAR incl. share of landed costs: what enters stock
);

-- Costs paid after purchase (transport, customs, clearing): added to the received items' cost.
CREATE TABLE goods_receipt_cost (
    id               BIGSERIAL PRIMARY KEY,
    goods_receipt_id BIGINT         NOT NULL REFERENCES goods_receipt (id) ON DELETE CASCADE,
    description      VARCHAR(300)   NOT NULL,
    amount           NUMERIC(18, 2) NOT NULL CHECK (amount >= 0)   -- SAR, excl. VAT
);

CREATE TABLE supplier_invoice (
    id                  BIGSERIAL PRIMARY KEY,
    organization_id     BIGINT         NOT NULL REFERENCES organization (id),
    number              VARCHAR(40)    NOT NULL,
    supplier_id         BIGINT         NOT NULL REFERENCES supplier (id),
    supplier_invoice_no VARCHAR(100),
    invoice_date        DATE           NOT NULL,
    due_date            DATE,
    currency            VARCHAR(3)     NOT NULL DEFAULT 'SAR',
    exchange_rate       NUMERIC(18, 6) NOT NULL DEFAULT 1,
    status              VARCHAR(20)    NOT NULL DEFAULT 'OPEN',   -- OPEN, CANCELLED (paid/unpaid is computed)
    remarks             VARCHAR(4000),
    total_excl_vat      NUMERIC(18, 2) NOT NULL DEFAULT 0,        -- document currency
    vat_total           NUMERIC(18, 2) NOT NULL DEFAULT 0,
    total_incl_vat      NUMERIC(18, 2) NOT NULL DEFAULT 0,
    total_sar           NUMERIC(18, 2) NOT NULL DEFAULT 0,        -- what is owed, in SAR
    vat_sar             NUMERIC(18, 2) NOT NULL DEFAULT 0,        -- input VAT, in SAR
    created_by          VARCHAR(255),
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_supplier_invoice_number ON supplier_invoice (organization_id, number);

CREATE TABLE supplier_invoice_line (
    id                    BIGSERIAL PRIMARY KEY,
    supplier_invoice_id   BIGINT         NOT NULL REFERENCES supplier_invoice (id) ON DELETE CASCADE,
    position              INT            NOT NULL,
    goods_receipt_line_id BIGINT         REFERENCES goods_receipt_line (id),
    product_id            BIGINT         REFERENCES product (id),
    name                  VARCHAR(1000)  NOT NULL,
    quantity              NUMERIC(18, 3) NOT NULL CHECK (quantity > 0),
    unit_cost             NUMERIC(18, 6) NOT NULL,
    vat_rate              NUMERIC(5, 2)  NOT NULL DEFAULT 15
);

CREATE TABLE supplier_payment (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT         NOT NULL REFERENCES organization (id),
    number          VARCHAR(40)    NOT NULL,
    supplier_id     BIGINT         NOT NULL REFERENCES supplier (id),
    payment_date    DATE           NOT NULL,
    method          VARCHAR(30)    NOT NULL,
    reference       VARCHAR(255),
    amount          NUMERIC(18, 2) NOT NULL CHECK (amount > 0),   -- SAR
    note            VARCHAR(1000),
    status          VARCHAR(20)    NOT NULL DEFAULT 'POSTED',
    cancel_reason   VARCHAR(500),
    created_by      VARCHAR(255),
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_supplier_payment_number ON supplier_payment (organization_id, number);

CREATE TABLE supplier_payment_allocation (
    id                  BIGSERIAL PRIMARY KEY,
    supplier_payment_id BIGINT         NOT NULL REFERENCES supplier_payment (id) ON DELETE CASCADE,
    supplier_invoice_id BIGINT         NOT NULL REFERENCES supplier_invoice (id),
    amount              NUMERIC(18, 2) NOT NULL CHECK (amount > 0)
);

-- Phase C: moving-average cost. Every movement is valued; the product keeps its current average.
ALTER TABLE stock_movement ADD COLUMN unit_cost NUMERIC(18, 6);
ALTER TABLE stock_movement ADD COLUMN value NUMERIC(18, 2);
ALTER TABLE product ADD COLUMN avg_cost NUMERIC(18, 6);
UPDATE product SET avg_cost = cost_price WHERE cost_price IS NOT NULL;

-- Item master (video §1.4 / Digi POS product form).
ALTER TABLE product ADD COLUMN name_ar VARCHAR(1000);
ALTER TABLE product ADD COLUMN category VARCHAR(100);
ALTER TABLE product ADD COLUMN brand VARCHAR(100);
ALTER TABLE product ADD COLUMN barcode VARCHAR(60);
ALTER TABLE product ADD COLUMN is_sales_item BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE product ADD COLUMN is_purchase_item BOOLEAN NOT NULL DEFAULT TRUE;
CREATE INDEX idx_product_barcode ON product (organization_id, barcode);
