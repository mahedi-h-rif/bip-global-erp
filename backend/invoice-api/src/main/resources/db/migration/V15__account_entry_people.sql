-- Cash book: record who received each income, and who paid each expense plus its related Fatoora/invoice number.

ALTER TABLE account_income ADD COLUMN received_by VARCHAR(255);
ALTER TABLE account_expense ADD COLUMN paid_by VARCHAR(255);
ALTER TABLE account_expense ADD COLUMN invoice_number VARCHAR(50);
