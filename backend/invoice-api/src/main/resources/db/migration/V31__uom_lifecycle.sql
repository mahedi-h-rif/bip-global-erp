-- Phase J: units of measure (SAP B1 UoM group / Odoo UoM category) and document lifecycle to market standard.

-- J1 Alternative units of an item. The item's own unit_code is its base (inventory) unit, factor 1.
-- factor = how many base units one of this unit holds (dozen = 12, carton = 24 …). Stock always posts in base units.
CREATE TABLE product_uom (
    id                  BIGSERIAL PRIMARY KEY,
    product_id          BIGINT         NOT NULL REFERENCES product (id) ON DELETE CASCADE,
    unit_code           VARCHAR(10)    NOT NULL,
    factor              NUMERIC(18, 6) NOT NULL CHECK (factor > 0),
    price               NUMERIC(18, 6) CHECK (price >= 0),   -- own selling price; NULL = base price × factor
    barcode             VARCHAR(60),
    is_sales_default    BOOLEAN        NOT NULL DEFAULT FALSE,
    is_purchase_default BOOLEAN        NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX uq_product_uom ON product_uom (product_id, unit_code);
CREATE INDEX idx_product_uom_barcode ON product_uom (barcode);

-- Every document line keeps the unit it was entered in and that unit's factor at the time.
ALTER TABLE quotation_line            ADD COLUMN uom_factor NUMERIC(18, 6) NOT NULL DEFAULT 1;
ALTER TABLE sales_order_line          ADD COLUMN uom_factor NUMERIC(18, 6) NOT NULL DEFAULT 1;
ALTER TABLE delivery_note_line        ADD COLUMN uom_factor NUMERIC(18, 6) NOT NULL DEFAULT 1;
ALTER TABLE invoice_line              ADD COLUMN uom_factor NUMERIC(18, 6) NOT NULL DEFAULT 1;
ALTER TABLE purchase_request_line     ADD COLUMN uom_factor NUMERIC(18, 6) NOT NULL DEFAULT 1;
ALTER TABLE purchase_order_line       ADD COLUMN uom_factor NUMERIC(18, 6) NOT NULL DEFAULT 1;
ALTER TABLE goods_receipt_line        ADD COLUMN uom_factor NUMERIC(18, 6) NOT NULL DEFAULT 1;
ALTER TABLE goods_return_line         ADD COLUMN uom_factor NUMERIC(18, 6) NOT NULL DEFAULT 1;
ALTER TABLE goods_return_line         ADD COLUMN unit_code VARCHAR(10) NOT NULL DEFAULT 'PCE';
ALTER TABLE supplier_invoice_line     ADD COLUMN uom_factor NUMERIC(18, 6) NOT NULL DEFAULT 1;
ALTER TABLE supplier_invoice_line     ADD COLUMN unit_code VARCHAR(10) NOT NULL DEFAULT 'PCE';
ALTER TABLE supplier_credit_note_line ADD COLUMN uom_factor NUMERIC(18, 6) NOT NULL DEFAULT 1;
ALTER TABLE supplier_credit_note_line ADD COLUMN unit_code VARCHAR(10) NOT NULL DEFAULT 'PCE';
UPDATE goods_return_line r SET unit_code = g.unit_code FROM goods_receipt_line g WHERE g.id = r.goods_receipt_line_id;
UPDATE supplier_invoice_line s SET unit_code = g.unit_code FROM goods_receipt_line g WHERE g.id = s.goods_receipt_line_id;

-- J2 Invoice straight from a sales order (services, or goods taken at the counter): the line keeps its order line.
ALTER TABLE invoice_line ADD COLUMN sales_order_line_id BIGINT REFERENCES sales_order_line (id);
CREATE INDEX idx_invoice_line_so_line ON invoice_line (sales_order_line_id);
CREATE INDEX idx_dn_line_so_line ON delivery_note_line (sales_order_line_id);
CREATE INDEX idx_grn_line_po_line ON goods_receipt_line (purchase_order_line_id);

-- Closing by hand: the rest of the order will not be delivered / received. Kept apart from the computed status.
ALTER TABLE sales_order    ADD COLUMN closed_reason VARCHAR(500);
ALTER TABLE sales_order    ADD COLUMN closed_by VARCHAR(255);
ALTER TABLE sales_order    ADD COLUMN closed_at TIMESTAMPTZ;
ALTER TABLE purchase_order ADD COLUMN closed_reason VARCHAR(500);
ALTER TABLE purchase_order ADD COLUMN closed_by VARCHAR(255);
ALTER TABLE purchase_order ADD COLUMN closed_at TIMESTAMPTZ;
