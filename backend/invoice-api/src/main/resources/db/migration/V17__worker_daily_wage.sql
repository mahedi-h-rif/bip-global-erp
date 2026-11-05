-- Non-permanent (daily-wage) workers: a daily flag and default rate on the worker, plus days/rate and the
-- salary payment method on each month's entry (work = days × rate for daily workers).

ALTER TABLE worker ADD COLUMN daily BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE worker ADD COLUMN daily_rate NUMERIC(18, 2);

ALTER TABLE worker_month ADD COLUMN days NUMERIC(10, 2);
ALTER TABLE worker_month ADD COLUMN rate NUMERIC(18, 2);
ALTER TABLE worker_month ADD COLUMN paid_method VARCHAR(20);
