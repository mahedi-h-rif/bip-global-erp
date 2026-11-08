-- Phase A: SAP-style document flow. Quotation → Sales Order → Delivery → Invoice → Receipt, every step linked,
-- partial quantities tracked per line, and a relationship graph over doc_link.

-- Generic edge between two business documents (types: QUOTATION, SALES_ORDER, DELIVERY_NOTE, INVOICE, RECEIPT,
-- PURCHASE_ORDER, GOODS_RECEIPT, SUPPLIER_INVOICE, SUPPLIER_PAYMENT).
CREATE TABLE doc_link (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT      NOT NULL REFERENCES organization (id),
    from_type       VARCHAR(30) NOT NULL,
    from_id         BIGINT      NOT NULL,
    to_type         VARCHAR(30) NOT NULL,
    to_id           BIGINT      NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (from_type, from_id, to_type, to_id)
);
CREATE INDEX idx_doc_link_from ON doc_link (from_type, from_id);
CREATE INDEX idx_doc_link_to ON doc_link (to_type, to_id);

CREATE TABLE sales_order (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT         NOT NULL REFERENCES organization (id),
    number          VARCHAR(40)    NOT NULL,
    quotation_id    BIGINT         REFERENCES quotation (id),
    customer_id     BIGINT         REFERENCES customer (id),
    client_name     VARCHAR(500),
    customer_ref    VARCHAR(100),             -- the customer's PO number, carried to deliveries and invoices
    sales_employee  VARCHAR(200),
    warehouse_id    BIGINT         REFERENCES warehouse (id),
    order_date      DATE           NOT NULL,
    delivery_date   DATE,
    status          VARCHAR(30)    NOT NULL DEFAULT 'OPEN',  -- OPEN, PARTIALLY_DELIVERED, CLOSED, CANCELLED
    remarks         VARCHAR(4000),
    total_excl_vat  NUMERIC(18, 2) NOT NULL DEFAULT 0,
    vat_total       NUMERIC(18, 2) NOT NULL DEFAULT 0,
    total_incl_vat  NUMERIC(18, 2) NOT NULL DEFAULT 0,
    created_by      VARCHAR(255),
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_sales_order_number ON sales_order (organization_id, number);

CREATE TABLE sales_order_line (
    id                BIGSERIAL PRIMARY KEY,
    sales_order_id    BIGINT         NOT NULL REFERENCES sales_order (id) ON DELETE CASCADE,
    position          INT            NOT NULL,
    quotation_line_id BIGINT         REFERENCES quotation_line (id),
    product_id        BIGINT         REFERENCES product (id),
    name              VARCHAR(1000)  NOT NULL,
    quantity          NUMERIC(18, 3) NOT NULL CHECK (quantity > 0),
    unit_code         VARCHAR(10)    NOT NULL DEFAULT 'PCE',
    unit_price        NUMERIC(18, 6) NOT NULL,
    discount_pct      NUMERIC(5, 2)  NOT NULL DEFAULT 0,
    vat_rate          NUMERIC(5, 2)  NOT NULL DEFAULT 15
);

-- Deliveries made from a sales order (instead of, or besides, from an invoice).
ALTER TABLE delivery_note ADD COLUMN sales_order_id BIGINT REFERENCES sales_order (id);
ALTER TABLE delivery_note ADD COLUMN customer_ref VARCHAR(100);
ALTER TABLE delivery_note ADD COLUMN sales_employee VARCHAR(200);
ALTER TABLE delivery_note_line ADD COLUMN sales_order_line_id BIGINT REFERENCES sales_order_line (id);
ALTER TABLE delivery_note_line ADD COLUMN unit_price NUMERIC(18, 6);
ALTER TABLE delivery_note_line ADD COLUMN vat_rate NUMERIC(5, 2);
CREATE INDEX idx_delivery_note_so ON delivery_note (sales_order_id);

-- An invoice line made from a delivery line: its stock already left on the delivery.
ALTER TABLE invoice_line ADD COLUMN delivery_note_line_id BIGINT REFERENCES delivery_note_line (id);
ALTER TABLE invoice ADD COLUMN customer_ref VARCHAR(100);
ALTER TABLE invoice ADD COLUMN sales_employee VARCHAR(200);

-- Incoming payment (receipt) that settles one or more invoices; each allocation is an invoice_payment row.
CREATE TABLE receipt (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT         NOT NULL REFERENCES organization (id),
    number          VARCHAR(40)    NOT NULL,
    customer_id     BIGINT         NOT NULL REFERENCES customer (id),
    receipt_date    DATE           NOT NULL,
    method          VARCHAR(30)    NOT NULL,
    reference       VARCHAR(255),
    amount          NUMERIC(18, 2) NOT NULL CHECK (amount > 0),
    note            VARCHAR(1000),
    created_by      VARCHAR(255),
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_receipt_number ON receipt (organization_id, number);
ALTER TABLE invoice_payment ADD COLUMN receipt_id BIGINT REFERENCES receipt (id) ON DELETE CASCADE;
