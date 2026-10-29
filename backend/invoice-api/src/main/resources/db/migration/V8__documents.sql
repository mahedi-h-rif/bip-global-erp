-- Company and worker documents (iqama, passport, CR, licences...) with expiry reminders.

CREATE TABLE document (
    id                   BIGSERIAL PRIMARY KEY,
    organization_id      BIGINT         NOT NULL REFERENCES organization (id),
    -- Empty: a company document. Deleting the worker keeps the document as the company's.
    worker_id            BIGINT         REFERENCES worker (id) ON DELETE SET NULL,
    type                 VARCHAR(30)    NOT NULL,
    title                VARCHAR(255)   NOT NULL,
    number               VARCHAR(80),
    holder_name          VARCHAR(255),
    nationality          VARCHAR(80),
    issue_date           DATE,
    expiry_date          DATE,
    notes                TEXT,
    reminders_enabled    BOOLEAN        NOT NULL DEFAULT TRUE,
    remind_after_expiry  BOOLEAN        NOT NULL DEFAULT TRUE,
    last_reminder_on     DATE,
    file_type            VARCHAR(100),
    file_name            VARCHAR(255),
    file_size            INT,
    ocr_text             TEXT,
    created_by           VARCHAR(255),
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_document_org ON document (organization_id, expiry_date);
CREATE INDEX idx_document_worker ON document (worker_id);

-- The scan itself, kept apart so lists never load it.
CREATE TABLE document_file (
    document_id          BIGINT         PRIMARY KEY REFERENCES document (id) ON DELETE CASCADE,
    data                 BYTEA          NOT NULL
);

-- One row per reminder sent: the in-app bell lists the unread ones.
CREATE TABLE document_alert (
    id                   BIGSERIAL PRIMARY KEY,
    organization_id      BIGINT         NOT NULL REFERENCES organization (id),
    document_id          BIGINT         NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    days_left            INT            NOT NULL,
    expiry_date          DATE           NOT NULL,
    message              TEXT           NOT NULL,
    email_status         VARCHAR(20),
    email_recipients     TEXT,
    read_at              TIMESTAMPTZ,
    read_by              VARCHAR(255),
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_document_alert_org ON document_alert (organization_id, read_at, created_at);
