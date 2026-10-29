-- Quotations (price offers, never sent to ZATCA) and agreements printed on the company letterhead.

CREATE TABLE quotation (
    id                   BIGSERIAL PRIMARY KEY,
    organization_id      BIGINT         NOT NULL REFERENCES organization (id),
    number               VARCHAR(40)    NOT NULL,
    customer_id          BIGINT         REFERENCES customer (id),
    client_name          VARCHAR(255),
    issue_date           DATE           NOT NULL,
    valid_until          DATE,
    status               VARCHAR(20)    NOT NULL DEFAULT 'DRAFT',
    notes                TEXT,
    terms                TEXT,
    total_excl_vat       NUMERIC(18, 2) NOT NULL DEFAULT 0,
    vat_total            NUMERIC(18, 2) NOT NULL DEFAULT 0,
    total_incl_vat       NUMERIC(18, 2) NOT NULL DEFAULT 0,
    -- Deleting the invoice draft just unlinks it, so the quotation can be converted again.
    invoice_id           BIGINT         REFERENCES invoice (id) ON DELETE SET NULL,
    created_by           VARCHAR(255),
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    UNIQUE (organization_id, number)
);
CREATE INDEX idx_quotation_org ON quotation (organization_id, created_at);

CREATE TABLE quotation_line (
    id                   BIGSERIAL PRIMARY KEY,
    quotation_id         BIGINT         NOT NULL REFERENCES quotation (id) ON DELETE CASCADE,
    position             INT            NOT NULL,
    name                 VARCHAR(1000)  NOT NULL,
    quantity             NUMERIC(18, 6) NOT NULL,
    unit_code            VARCHAR(10)    NOT NULL DEFAULT 'PCE',
    unit_price           NUMERIC(18, 6) NOT NULL,
    vat_rate             NUMERIC(5, 2)  NOT NULL DEFAULT 15,
    product_id           BIGINT         REFERENCES product (id)
);
CREATE INDEX idx_quotation_line ON quotation_line (quotation_id, position);

-- The company's standard agreement, edited by the admin; new agreements start from it.
CREATE TABLE agreement_template (
    id                   BIGSERIAL PRIMARY KEY,
    organization_id      BIGINT         NOT NULL UNIQUE REFERENCES organization (id),
    title                VARCHAR(255)   NOT NULL,
    body                 TEXT           NOT NULL,
    updated_by           VARCHAR(255),
    updated_at           TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE TABLE agreement (
    id                   BIGSERIAL PRIMARY KEY,
    organization_id      BIGINT         NOT NULL REFERENCES organization (id),
    number               VARCHAR(40)    NOT NULL,
    customer_id          BIGINT         REFERENCES customer (id),
    client_name          VARCHAR(255)   NOT NULL,
    client_details       TEXT,
    title                VARCHAR(255)   NOT NULL,
    body                 TEXT           NOT NULL,
    agreement_date       DATE           NOT NULL,
    status               VARCHAR(20)    NOT NULL DEFAULT 'DRAFT',
    signed_on            DATE,
    created_by           VARCHAR(255),
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    UNIQUE (organization_id, number)
);
CREATE INDEX idx_agreement_org ON agreement (organization_id, created_at);

-- The person who signs agreements and quotations for the company (on paper, with the company stamp).
ALTER TABLE organization
    ADD COLUMN signatory_name    VARCHAR(255),
    ADD COLUMN signatory_title   VARCHAR(255);
