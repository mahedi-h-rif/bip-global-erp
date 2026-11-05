-- Cash-book income and expenses record how the money moved (cash, card, ...), and every money entry keeps who
-- last changed it and when, next to who created it. Catalogue items get an incremental number (ITEM-000001).

ALTER TABLE account_income ADD COLUMN method VARCHAR(20) NOT NULL DEFAULT 'CASH';
ALTER TABLE account_income ADD COLUMN updated_by VARCHAR(255);
ALTER TABLE account_income ADD COLUMN updated_at TIMESTAMPTZ;

ALTER TABLE account_expense ADD COLUMN method VARCHAR(20) NOT NULL DEFAULT 'CASH';
ALTER TABLE account_expense ADD COLUMN updated_by VARCHAR(255);
ALTER TABLE account_expense ADD COLUMN updated_at TIMESTAMPTZ;

ALTER TABLE partner_withdrawal ADD COLUMN updated_by VARCHAR(255);
ALTER TABLE partner_withdrawal ADD COLUMN updated_at TIMESTAMPTZ;

ALTER TABLE invoice_payment ADD COLUMN updated_by VARCHAR(255);

ALTER TABLE worker_day ADD COLUMN updated_by VARCHAR(255);
ALTER TABLE worker_day ADD COLUMN updated_at TIMESTAMPTZ;

ALTER TABLE product ADD COLUMN number VARCHAR(30);
UPDATE product p SET number = 'ITEM-' || lpad(x.rn::text, 6, '0')
FROM (SELECT id, row_number() OVER (PARTITION BY organization_id ORDER BY id) AS rn FROM product) x
WHERE x.id = p.id;
CREATE UNIQUE INDEX uq_product_number ON product (organization_id, number);
