-- An invoice issued outside ZATCA: numbered from the normal invoice series, no VAT, no QR, never submitted.
ALTER TABLE invoice ADD COLUMN without_zatca BOOLEAN NOT NULL DEFAULT FALSE;
