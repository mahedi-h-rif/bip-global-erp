-- Cancelled invoices (a full credit note was issued) and closed ones (the unpaid rest is written off).
ALTER TABLE invoice ADD COLUMN cancelled_at timestamptz;
ALTER TABLE invoice ADD COLUMN cancel_reason varchar(500);
ALTER TABLE invoice ADD COLUMN cancel_note_id bigint REFERENCES invoice (id);
ALTER TABLE invoice ADD COLUMN closed_at timestamptz;
ALTER TABLE invoice ADD COLUMN close_reason varchar(500);
-- On a credit note: the goods were not given back, so issuing it must not put them back in stock.
ALTER TABLE invoice ADD COLUMN no_stock_return boolean NOT NULL DEFAULT false;
-- A refund on cancellation is recorded as a negative payment, so every "paid" total nets it out.
ALTER TABLE invoice_payment DROP CONSTRAINT IF EXISTS invoice_payment_amount_check;
ALTER TABLE invoice_payment ADD CONSTRAINT invoice_payment_amount_check CHECK (amount <> 0);
