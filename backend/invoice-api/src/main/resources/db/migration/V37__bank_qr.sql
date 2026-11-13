-- An uploaded payment QR code (e.g. the bank's own QR) shown with the bank details on documents.
ALTER TABLE organization ADD COLUMN bank_qr text;
ALTER TABLE organization ADD COLUMN bank_qr_content_type varchar(50);
