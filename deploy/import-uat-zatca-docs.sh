#!/usr/bin/env bash
# One-off: copy the ZATCA-accepted invoices and notes issued on UAT (which was connected to real ZATCA) into
# production, with their customers, products, ZATCA responses and payments. Run on the server from
# /opt/bip-erp/deploy. Production is backed up first; the import itself is one transaction.
set -euo pipefail
cd "$(dirname "$0")"
PSQL="docker compose -p bip-erp exec -T db psql -U erp -v ON_ERROR_STOP=1"
STAMP=$(date +%Y%m%d-%H%M%S)

echo "== backup production"
docker compose -p bip-erp exec -T db pg_dump -U erp -Fc bip_prod > "backups/bip_prod-before-uat-import-$STAMP.dump"
ls -la "backups/bip_prod-before-uat-import-$STAMP.dump"

DOCS="SELECT id FROM invoice WHERE status NOT IN ('DRAFT','REJECTED')"

echo "== staging tables in production"
$PSQL -d bip_prod -q <<'SQL'
DROP SCHEMA IF EXISTS uat_import CASCADE;
CREATE SCHEMA uat_import;
CREATE TABLE uat_import.egs_unit (LIKE public.egs_unit);
CREATE TABLE uat_import.customer (LIKE public.customer);
CREATE TABLE uat_import.product (LIKE public.product);
CREATE TABLE uat_import.invoice (LIKE public.invoice);
CREATE TABLE uat_import.invoice_line (LIKE public.invoice_line);
CREATE TABLE uat_import.zatca_submission (LIKE public.zatca_submission);
CREATE TABLE uat_import.invoice_payment (LIKE public.invoice_payment);
SQL

copy() { # $1 table, $2 select on UAT
  $PSQL -d bip_uat -q -c "\copy ($2) TO STDOUT" | $PSQL -d bip_prod -q -c "\copy uat_import.$1 FROM STDIN"
}
echo "== copy selected UAT rows"
copy egs_unit "SELECT * FROM egs_unit WHERE id IN (SELECT DISTINCT egs_unit_id FROM invoice WHERE id IN ($DOCS))"
copy customer "SELECT * FROM customer WHERE id IN (SELECT customer_id FROM invoice WHERE id IN ($DOCS))"
copy product "SELECT * FROM product WHERE id IN (SELECT product_id FROM invoice_line WHERE invoice_id IN ($DOCS))"
copy invoice "SELECT * FROM invoice WHERE id IN ($DOCS) ORDER BY id"
copy invoice_line "SELECT * FROM invoice_line WHERE invoice_id IN ($DOCS)"
copy zatca_submission "SELECT * FROM zatca_submission WHERE invoice_id IN ($DOCS)"
copy invoice_payment "SELECT * FROM invoice_payment WHERE invoice_id IN ($DOCS)"
$PSQL -d bip_prod -At -c "SELECT 'staged: devices '||(SELECT count(*) FROM uat_import.egs_unit)||', customers '||(SELECT count(*) FROM uat_import.customer)||', products '||(SELECT count(*) FROM uat_import.product)||', documents '||(SELECT count(*) FROM uat_import.invoice)||', lines '||(SELECT count(*) FROM uat_import.invoice_line)||', ZATCA responses '||(SELECT count(*) FROM uat_import.zatca_submission)||', payments '||(SELECT count(*) FROM uat_import.invoice_payment)"

echo "== import (one transaction)"
$PSQL -d bip_prod -q <<'SQL'
BEGIN;
-- UAT's device: kept only as the signer of these documents. Retired, never default, no keys copied.
CREATE TEMP TABLE device_map AS SELECT id AS old_id, nextval('egs_unit_id_seq') AS new_id FROM uat_import.egs_unit;
UPDATE uat_import.egs_unit u SET id = m.new_id, status = 'RETIRED', is_default = false, name = u.name || ' (from UAT)'
  FROM device_map m WHERE u.id = m.old_id;
INSERT INTO public.egs_unit SELECT * FROM uat_import.egs_unit;
UPDATE uat_import.invoice i SET egs_unit_id = m.new_id FROM device_map m WHERE i.egs_unit_id = m.old_id;
UPDATE uat_import.zatca_submission z SET egs_unit_id = m.new_id FROM device_map m WHERE z.egs_unit_id = m.old_id;

-- Production had no customers, products or invoices: the UAT ids are kept as they are.
INSERT INTO public.customer SELECT * FROM uat_import.customer;
INSERT INTO public.product SELECT * FROM uat_import.product;

-- Documents: production's warehouse; links to UAT-only orders and deliveries dropped.
UPDATE uat_import.invoice SET warehouse_id = (SELECT id FROM public.warehouse ORDER BY is_default DESC, id LIMIT 1) WHERE warehouse_id IS NOT NULL;
UPDATE uat_import.invoice SET cancel_note_id = NULL;
UPDATE uat_import.invoice_line SET delivery_note_line_id = NULL, sales_order_line_id = NULL;
UPDATE uat_import.invoice_payment SET receipt_id = NULL;
INSERT INTO public.invoice SELECT * FROM uat_import.invoice WHERE doc_type = 'INVOICE';
INSERT INTO public.invoice SELECT * FROM uat_import.invoice WHERE doc_type <> 'INVOICE';
INSERT INTO public.invoice_line SELECT * FROM uat_import.invoice_line;
INSERT INTO public.zatca_submission SELECT * FROM uat_import.zatca_submission;
INSERT INTO public.invoice_payment SELECT * FROM uat_import.invoice_payment;

-- New rows continue after the copied ones.
SELECT setval('customer_id_seq', GREATEST((SELECT COALESCE(max(id), 0) FROM public.customer), 1));
SELECT setval('product_id_seq', GREATEST((SELECT COALESCE(max(id), 0) FROM public.product), 1));
SELECT setval('invoice_id_seq', GREATEST((SELECT COALESCE(max(id), 0) FROM public.invoice), 1));
SELECT setval('invoice_line_id_seq', GREATEST((SELECT COALESCE(max(id), 0) FROM public.invoice_line), 1));
SELECT setval('zatca_submission_id_seq', GREATEST((SELECT COALESCE(max(id), 0) FROM public.zatca_submission), 1));
SELECT setval('invoice_payment_id_seq', GREATEST((SELECT COALESCE(max(id), 0) FROM public.invoice_payment), 1));

-- Invoice numbers continue after UAT's, so production never reuses a number ZATCA already has.
INSERT INTO document_sequence (organization_id, doc_type, year, next_value)
SELECT 1, doc_type, EXTRACT(YEAR FROM issued_at AT TIME ZONE 'Asia/Riyadh')::int,
       max(substring(number FROM '(\d+)$')::bigint) + 1
FROM public.invoice WHERE number IS NOT NULL GROUP BY doc_type, EXTRACT(YEAR FROM issued_at AT TIME ZONE 'Asia/Riyadh')
ON CONFLICT (organization_id, doc_type, year) DO UPDATE SET next_value = GREATEST(document_sequence.next_value, EXCLUDED.next_value);

INSERT INTO audit_log (organization_id, actor, action, entity_type, entity_id, details, created_at)
VALUES (1, 'system', 'UAT_ZATCA_DOCS_IMPORTED', 'invoice', NULL,
        (SELECT count(*) || ' ZATCA-accepted documents copied from UAT' FROM uat_import.invoice), now());
COMMIT;
DROP SCHEMA uat_import CASCADE;
SQL

echo "== check"
$PSQL -d bip_prod -At -F ' | ' -c "SELECT doc_type, status, count(*), sum(total_incl_vat) FROM invoice GROUP BY 1,2 ORDER BY 1,2" \
  -c "SELECT 'next numbers', doc_type, year, next_value FROM document_sequence WHERE doc_type IN ('INVOICE','CREDIT_NOTE')" \
  -c "SELECT 'devices', id, name, status, is_default FROM egs_unit ORDER BY id" \
  -c "SELECT 'payments', count(*), sum(amount) FROM invoice_payment"
