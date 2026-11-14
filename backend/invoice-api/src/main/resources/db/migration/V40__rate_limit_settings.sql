-- API rate limits, set by the company admin under Settings → Company. NULL = the server default (env).
ALTER TABLE organization ADD COLUMN api_rate_limit_per_minute INTEGER;
ALTER TABLE organization ADD COLUMN login_rate_limit_per_minute INTEGER;
