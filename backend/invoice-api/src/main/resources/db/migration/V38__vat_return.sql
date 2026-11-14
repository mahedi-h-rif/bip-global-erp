-- Quarterly VAT returns as recorded by the company: filed with ZATCA (date, reference) and paid.
CREATE TABLE vat_return (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT        NOT NULL REFERENCES organization (id),
    year            INT           NOT NULL,
    quarter         INT           NOT NULL CHECK (quarter BETWEEN 1 AND 4),
    filed_on        DATE,
    reference       VARCHAR(100),
    paid_amount     NUMERIC(18, 2),
    paid_on         DATE,
    notes           VARCHAR(1000),
    updated_by      VARCHAR(255),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (organization_id, year, quarter)
);
