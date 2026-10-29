-- Free documents written on the company letterhead pad (letters, notices, certificates).
CREATE TABLE pad_document (
    id                   BIGSERIAL PRIMARY KEY,
    organization_id      BIGINT         NOT NULL REFERENCES organization (id),
    title                VARCHAR(255)   NOT NULL,
    body                 TEXT           NOT NULL,
    created_by           VARCHAR(255),
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_pad_document_org ON pad_document (organization_id, updated_at);
