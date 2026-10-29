-- Fatoora Platform schema. Every business table carries organization_id so a single deployment
-- can later host several clients; today each client runs its own deployment with one organization.

CREATE TABLE organization (
    id                   BIGSERIAL PRIMARY KEY,
    legal_name_ar        VARCHAR(255) NOT NULL,
    legal_name_en        VARCHAR(255),
    vat_number           VARCHAR(15)  NOT NULL,
    other_id             VARCHAR(50)  NOT NULL,
    other_id_scheme      VARCHAR(10)  NOT NULL DEFAULT 'CRN',
    street               VARCHAR(255) NOT NULL,
    building_number      VARCHAR(4)   NOT NULL,
    additional_number    VARCHAR(4),
    district             VARCHAR(255) NOT NULL,
    city                 VARCHAR(255) NOT NULL,
    postal_code          VARCHAR(5)   NOT NULL,
    country_code         VARCHAR(2)   NOT NULL DEFAULT 'SA',
    phone                VARCHAR(50),
    email                VARCHAR(255),
    brand_color          VARCHAR(7)   NOT NULL DEFAULT '#0B2A6F',
    logo_url             VARCHAR(500),
    invoice_prefix       VARCHAR(10)  NOT NULL DEFAULT 'INV',
    credit_note_prefix   VARCHAR(10)  NOT NULL DEFAULT 'CN',
    debit_note_prefix    VARCHAR(10)  NOT NULL DEFAULT 'DN',
    zatca_environment    VARCHAR(20)  NOT NULL DEFAULT 'SANDBOX',
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE app_user (
    id                   BIGSERIAL PRIMARY KEY,
    organization_id      BIGINT       NOT NULL REFERENCES organization (id),
    email                VARCHAR(255) NOT NULL UNIQUE,
    full_name            VARCHAR(255) NOT NULL,
    password_hash        VARCHAR(255) NOT NULL,
    role                 VARCHAR(20)  NOT NULL,
    enabled              BOOLEAN      NOT NULL DEFAULT TRUE,
    totp_secret_enc      TEXT,
    last_login_at        TIMESTAMPTZ,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE customer (
    id                   BIGSERIAL PRIMARY KEY,
    organization_id      BIGINT       NOT NULL REFERENCES organization (id),
    name_ar              VARCHAR(255) NOT NULL,
    name_en              VARCHAR(255),
    vat_number           VARCHAR(15),
    other_id             VARCHAR(50),
    other_id_scheme      VARCHAR(10),
    street               VARCHAR(255),
    building_number      VARCHAR(10),
    additional_number    VARCHAR(10),
    district             VARCHAR(255),
    city                 VARCHAR(255),
    postal_code          VARCHAR(10),
    country_code         VARCHAR(2)   NOT NULL DEFAULT 'SA',
    email                VARCHAR(255),
    phone                VARCHAR(50),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_customer_org ON customer (organization_id);

-- An EGS (e-invoice generation solution) unit: one registered invoicing device with its own
-- certificate and its own gap-free ICV / PIH chain.
CREATE TABLE egs_unit (
    id                   BIGSERIAL PRIMARY KEY,
    organization_id      BIGINT       NOT NULL REFERENCES organization (id),
    name                 VARCHAR(100) NOT NULL,
    serial_number        VARCHAR(255) NOT NULL UNIQUE,
    branch               VARCHAR(255) NOT NULL,
    location             VARCHAR(255) NOT NULL,
    industry             VARCHAR(255) NOT NULL,
    invoice_types        VARCHAR(4)   NOT NULL DEFAULT '1100',
    environment          VARCHAR(20)  NOT NULL,
    status               VARCHAR(20)  NOT NULL DEFAULT 'NEW',
    icv                  BIGINT       NOT NULL DEFAULT 0,
    last_invoice_hash    VARCHAR(100) NOT NULL,
    is_default           BOOLEAN      NOT NULL DEFAULT FALSE,
    activated_at         TIMESTAMPTZ,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE egs_credential (
    id                   BIGSERIAL PRIMARY KEY,
    egs_unit_id          BIGINT       NOT NULL REFERENCES egs_unit (id),
    kind                 VARCHAR(20)  NOT NULL,
    binary_security_token TEXT        NOT NULL,
    secret_enc           TEXT         NOT NULL,
    private_key_enc      TEXT         NOT NULL,
    request_id           VARCHAR(50),
    certificate_not_after TIMESTAMPTZ,
    active               BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_egs_credential_unit ON egs_credential (egs_unit_id, kind, active);

CREATE TABLE document_sequence (
    organization_id      BIGINT       NOT NULL REFERENCES organization (id),
    doc_type             VARCHAR(20)  NOT NULL,
    year                 INT          NOT NULL,
    next_value           BIGINT       NOT NULL,
    PRIMARY KEY (organization_id, doc_type, year)
);

CREATE TABLE invoice (
    id                   BIGSERIAL PRIMARY KEY,
    organization_id      BIGINT       NOT NULL REFERENCES organization (id),
    kind                 VARCHAR(20)  NOT NULL,
    doc_type             VARCHAR(20)  NOT NULL,
    status               VARCHAR(30)  NOT NULL,
    send_mode            VARCHAR(10)  NOT NULL DEFAULT 'NOW',
    number               VARCHAR(50),
    uuid                 UUID,
    issued_at            TIMESTAMPTZ,
    supply_date          DATE,
    currency             VARCHAR(3)   NOT NULL DEFAULT 'SAR',
    payment_means        VARCHAR(10)  NOT NULL DEFAULT '10',
    customer_id          BIGINT REFERENCES customer (id),
    buyer_snapshot       TEXT,
    seller_snapshot      TEXT,
    original_invoice_id  BIGINT REFERENCES invoice (id),
    billing_reference    VARCHAR(50),
    reason               VARCHAR(1000),
    internal_note        VARCHAR(2000),
    total_excl_vat       NUMERIC(18, 2) NOT NULL DEFAULT 0,
    vat_total            NUMERIC(18, 2) NOT NULL DEFAULT 0,
    total_incl_vat       NUMERIC(18, 2) NOT NULL DEFAULT 0,
    egs_unit_id          BIGINT REFERENCES egs_unit (id),
    icv                  BIGINT,
    pih                  VARCHAR(100),
    invoice_hash         VARCHAR(100),
    signed_xml           TEXT,
    cleared_xml          TEXT,
    qr_code              TEXT,
    scheduled_submit_at  TIMESTAMPTZ,
    report_deadline_at   TIMESTAMPTZ,
    submitted_at         TIMESTAMPTZ,
    submit_attempts      INT          NOT NULL DEFAULT 0,
    zatca_warnings       TEXT,
    zatca_errors         TEXT,
    last_error           VARCHAR(2000),
    created_by           VARCHAR(255),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version              BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_invoice_number UNIQUE (organization_id, number),
    CONSTRAINT uq_invoice_uuid UNIQUE (uuid),
    CONSTRAINT uq_invoice_icv UNIQUE (egs_unit_id, icv)
);
CREATE INDEX idx_invoice_org_status ON invoice (organization_id, status);
CREATE INDEX idx_invoice_due ON invoice (status, scheduled_submit_at);

CREATE TABLE invoice_line (
    id                   BIGSERIAL PRIMARY KEY,
    invoice_id           BIGINT       NOT NULL REFERENCES invoice (id) ON DELETE CASCADE,
    position             INT          NOT NULL,
    name                 VARCHAR(1000) NOT NULL,
    quantity             NUMERIC(18, 6) NOT NULL,
    unit_code            VARCHAR(10)  NOT NULL DEFAULT 'PCE',
    unit_price           NUMERIC(18, 6) NOT NULL,
    vat_category         VARCHAR(1)   NOT NULL DEFAULT 'S',
    vat_rate             NUMERIC(5, 2) NOT NULL DEFAULT 15,
    exemption_code       VARCHAR(30),
    exemption_reason     VARCHAR(1000)
);
CREATE INDEX idx_invoice_line_invoice ON invoice_line (invoice_id);

CREATE TABLE zatca_submission (
    id                   BIGSERIAL PRIMARY KEY,
    invoice_id           BIGINT       REFERENCES invoice (id),
    egs_unit_id          BIGINT       REFERENCES egs_unit (id),
    endpoint             VARCHAR(100) NOT NULL,
    http_status          INT,
    outcome              VARCHAR(30)  NOT NULL,
    response_body        TEXT,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_submission_invoice ON zatca_submission (invoice_id);

CREATE TABLE audit_log (
    id                   BIGSERIAL PRIMARY KEY,
    organization_id      BIGINT,
    actor                VARCHAR(255),
    action               VARCHAR(100) NOT NULL,
    entity_type          VARCHAR(50),
    entity_id            BIGINT,
    details              VARCHAR(4000),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_org_time ON audit_log (organization_id, created_at DESC);
