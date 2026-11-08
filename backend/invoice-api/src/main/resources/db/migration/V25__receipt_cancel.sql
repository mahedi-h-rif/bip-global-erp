-- Payments are cancelled and reposted, never deleted (BIP, meeting 7 Nov): the receipt stays visible, marked
-- cancelled, while its amounts come off the invoices. The allocations it had are kept as a snapshot.
ALTER TABLE receipt ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'POSTED';   -- POSTED, CANCELLED
ALTER TABLE receipt ADD COLUMN cancelled_at TIMESTAMPTZ;
ALTER TABLE receipt ADD COLUMN cancelled_by VARCHAR(255);
ALTER TABLE receipt ADD COLUMN cancel_reason VARCHAR(500);
-- "invoiceId:amount:number" entries separated by ";", as they were when the receipt was cancelled.
ALTER TABLE receipt ADD COLUMN cancelled_allocations TEXT;
