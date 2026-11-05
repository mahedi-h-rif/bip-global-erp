-- Record how each day's worker payment was made (cash, card, ...).

ALTER TABLE worker_day ADD COLUMN method VARCHAR(20);
