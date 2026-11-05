-- A worker's day-by-day record: work value and amount paid on a date. Each change also moves that month's
-- figures in worker_month by the same amount, so the monthly ledger stays the single total everything reads.

CREATE TABLE worker_day (
    id                   BIGSERIAL PRIMARY KEY,
    worker_id            BIGINT         NOT NULL REFERENCES worker (id) ON DELETE CASCADE,
    entry_date           DATE           NOT NULL,
    work                 NUMERIC(18, 2),
    paid                 NUMERIC(18, 2),
    notes                TEXT,
    created_by           VARCHAR(255),
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    UNIQUE (worker_id, entry_date)
);
CREATE INDEX idx_worker_day_date ON worker_day (entry_date);
