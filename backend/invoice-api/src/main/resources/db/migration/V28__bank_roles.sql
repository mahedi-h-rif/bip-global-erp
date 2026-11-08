-- Phase F: bank details printed on tax invoices (video §1.2).
ALTER TABLE organization ADD COLUMN bank_name VARCHAR(150);
ALTER TABLE organization ADD COLUMN bank_account_name VARCHAR(200);
ALTER TABLE organization ADD COLUMN bank_account_no VARCHAR(60);
ALTER TABLE organization ADD COLUMN bank_iban VARCHAR(40);
ALTER TABLE organization ADD COLUMN invoice_footer VARCHAR(1000);   -- e.g. branch offices line

-- Phase G: departments and a permission matrix (video: separate sales, purchase, finance, cost departments).
-- A custom role grants actions per module; a user may hold one custom role on top of the built-in role.
CREATE TABLE custom_role (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT       NOT NULL REFERENCES organization (id),
    name            VARCHAR(100) NOT NULL,
    description     VARCHAR(500),
    -- {"sales":["view","create","edit","delete","approve"], "purchasing":[...], ...}
    permissions     TEXT         NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (organization_id, name)
);
ALTER TABLE app_user ADD COLUMN custom_role_id BIGINT REFERENCES custom_role (id) ON DELETE SET NULL;
