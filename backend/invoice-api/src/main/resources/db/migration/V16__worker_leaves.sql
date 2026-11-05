-- Worker leave: a date range per worker, with an optional salary deduction (salary cut) for the leave.

CREATE TABLE worker_leave (
    id               BIGSERIAL PRIMARY KEY,
    worker_id        BIGINT        NOT NULL REFERENCES worker (id) ON DELETE CASCADE,
    start_date       DATE          NOT NULL,
    end_date         DATE          NOT NULL,
    type             VARCHAR(20)   NOT NULL DEFAULT 'ANNUAL',
    deduction        NUMERIC(18, 2),
    notes            TEXT,
    created_by       VARCHAR(255),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by       VARCHAR(255),
    updated_at       TIMESTAMPTZ
);
CREATE INDEX idx_worker_leave_worker ON worker_leave (worker_id, start_date);
