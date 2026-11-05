-- Daily-wage workers now carry the days worked on the worker itself (work total = days × daily rate).

ALTER TABLE worker ADD COLUMN days NUMERIC(10, 2);
