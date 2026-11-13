-- Per-user access: the features a user may open, what they may do there, and whether they see only their own records.
-- features NULL = the user is not on per-user access (admins, the provider and logins made before this change).
ALTER TABLE app_user ADD COLUMN features text;
ALTER TABLE app_user ADD COLUMN actions text;
ALTER TABLE app_user ADD COLUMN own_data_only boolean NOT NULL DEFAULT false;
