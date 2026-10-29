-- Customers are entered by their English name; the Arabic name is optional.
-- The ZATCA buyer name uses the Arabic name when present, otherwise the English one.
ALTER TABLE customer ALTER COLUMN name_ar DROP NOT NULL;
UPDATE customer SET name_en = name_ar WHERE name_en IS NULL OR btrim(name_en) = '';

-- One free-text address box. The structured national address stays for standard (B2B) invoices,
-- which ZATCA rejects without building number, postal code, street, district and city.
ALTER TABLE customer ADD COLUMN address TEXT;
