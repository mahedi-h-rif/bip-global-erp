-- Video §1.3 (SAP B1 purchasing menu): Purchase Request → Purchase Order, Goods Return from a goods receipt,
-- A/P Credit Memo against a supplier invoice.

CREATE TABLE purchase_request (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT        NOT NULL REFERENCES organization (id),
    number          VARCHAR(40)   NOT NULL,
    requester       VARCHAR(200)  NOT NULL,
    department      VARCHAR(100),
    request_date    DATE          NOT NULL,
    required_date   DATE,
    warehouse_id    BIGINT        REFERENCES warehouse (id),
    status          VARCHAR(20)   NOT NULL DEFAULT 'OPEN',   -- OPEN, ORDERED, CANCELLED
    remarks         VARCHAR(4000),
    created_by      VARCHAR(255),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_purchase_request_number ON purchase_request (organization_id, number);

CREATE TABLE purchase_request_line (
    id                  BIGSERIAL PRIMARY KEY,
    purchase_request_id BIGINT         NOT NULL REFERENCES purchase_request (id) ON DELETE CASCADE,
    position            INT            NOT NULL,
    product_id          BIGINT         REFERENCES product (id),
    name                VARCHAR(1000)  NOT NULL,
    quantity            NUMERIC(18, 3) NOT NULL CHECK (quantity > 0),
    unit_code           VARCHAR(10)    NOT NULL DEFAULT 'PCE'
);

ALTER TABLE purchase_order ADD COLUMN purchase_request_id BIGINT REFERENCES purchase_request (id);

CREATE TABLE goods_return (
    id               BIGSERIAL PRIMARY KEY,
    organization_id  BIGINT         NOT NULL REFERENCES organization (id),
    number           VARCHAR(40)    NOT NULL,
    goods_receipt_id BIGINT         NOT NULL REFERENCES goods_receipt (id),
    supplier_id      BIGINT         NOT NULL REFERENCES supplier (id),
    return_date      DATE           NOT NULL,
    warehouse_id     BIGINT         NOT NULL REFERENCES warehouse (id),
    reason           VARCHAR(1000),
    status           VARCHAR(20)    NOT NULL DEFAULT 'POSTED',  -- POSTED, CANCELLED
    total_excl_vat   NUMERIC(18, 2) NOT NULL DEFAULT 0,          -- document currency of the receipt
    vat_total        NUMERIC(18, 2) NOT NULL DEFAULT 0,
    total_incl_vat   NUMERIC(18, 2) NOT NULL DEFAULT 0,
    created_by       VARCHAR(255),
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_goods_return_number ON goods_return (organization_id, number);

CREATE TABLE goods_return_line (
    id                    BIGSERIAL PRIMARY KEY,
    goods_return_id       BIGINT         NOT NULL REFERENCES goods_return (id) ON DELETE CASCADE,
    position              INT            NOT NULL,
    goods_receipt_line_id BIGINT         NOT NULL REFERENCES goods_receipt_line (id),
    product_id            BIGINT         REFERENCES product (id),
    name                  VARCHAR(1000)  NOT NULL,
    quantity              NUMERIC(18, 3) NOT NULL CHECK (quantity > 0),
    unit_cost             NUMERIC(18, 6) NOT NULL,
    vat_rate              NUMERIC(5, 2)  NOT NULL DEFAULT 15
);

CREATE TABLE supplier_credit_note (
    id                  BIGSERIAL PRIMARY KEY,
    organization_id     BIGINT         NOT NULL REFERENCES organization (id),
    number              VARCHAR(40)    NOT NULL,
    supplier_id         BIGINT         NOT NULL REFERENCES supplier (id),
    supplier_invoice_id BIGINT         NOT NULL REFERENCES supplier_invoice (id),
    goods_return_id     BIGINT         REFERENCES goods_return (id),
    supplier_ref        VARCHAR(100),                  -- the supplier's credit note no.
    credit_date         DATE           NOT NULL,
    status              VARCHAR(20)    NOT NULL DEFAULT 'POSTED',   -- POSTED, CANCELLED
    remarks             VARCHAR(4000),
    total_excl_vat      NUMERIC(18, 2) NOT NULL DEFAULT 0,   -- invoice currency
    vat_total           NUMERIC(18, 2) NOT NULL DEFAULT 0,
    total_incl_vat      NUMERIC(18, 2) NOT NULL DEFAULT 0,
    total_sar           NUMERIC(18, 2) NOT NULL DEFAULT 0,   -- reduces what is owed on the invoice
    vat_sar             NUMERIC(18, 2) NOT NULL DEFAULT 0,   -- reduces input VAT
    created_by          VARCHAR(255),
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_supplier_credit_note_number ON supplier_credit_note (organization_id, number);
CREATE INDEX idx_supplier_credit_note_invoice ON supplier_credit_note (supplier_invoice_id);

CREATE TABLE supplier_credit_note_line (
    id                      BIGSERIAL PRIMARY KEY,
    supplier_credit_note_id BIGINT         NOT NULL REFERENCES supplier_credit_note (id) ON DELETE CASCADE,
    position                INT            NOT NULL,
    product_id              BIGINT         REFERENCES product (id),
    name                    VARCHAR(1000)  NOT NULL,
    quantity                NUMERIC(18, 3) NOT NULL CHECK (quantity > 0),
    unit_cost               NUMERIC(18, 6) NOT NULL,
    vat_rate                NUMERIC(5, 2)  NOT NULL DEFAULT 15
);
