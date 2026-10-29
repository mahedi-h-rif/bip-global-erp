-- Outside workers and their monthly account: value of work done and amount paid; due is work minus paid.

CREATE TABLE worker (
    id                   BIGSERIAL PRIMARY KEY,
    organization_id      BIGINT         NOT NULL REFERENCES organization (id),
    name                 VARCHAR(255)   NOT NULL,
    phone                VARCHAR(40),
    iqama_number         VARCHAR(40),
    nationality          VARCHAR(80),
    notes                TEXT,
    active               BOOLEAN        NOT NULL DEFAULT TRUE,
    position             INT            NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_worker_org ON worker (organization_id, position);

CREATE TABLE worker_month (
    id                   BIGSERIAL PRIMARY KEY,
    worker_id            BIGINT         NOT NULL REFERENCES worker (id) ON DELETE CASCADE,
    year                 INT            NOT NULL,
    month                INT            NOT NULL CHECK (month BETWEEN 1 AND 12),
    work                 NUMERIC(18, 2),
    paid                 NUMERIC(18, 2),
    UNIQUE (worker_id, year, month)
);
