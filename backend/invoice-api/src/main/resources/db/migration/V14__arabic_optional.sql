-- Arabic names are optional everywhere. The company needs an Arabic or an English legal name (or both);
-- ZATCA's registration name uses the Arabic one when present, otherwise the English one.
ALTER TABLE organization ALTER COLUMN legal_name_ar DROP NOT NULL;
UPDATE organization SET legal_name_ar = NULL WHERE btrim(legal_name_ar) = '';
UPDATE organization SET legal_name_en = NULL WHERE btrim(legal_name_en) = '';
UPDATE customer SET name_ar = NULL WHERE btrim(name_ar) = '';
