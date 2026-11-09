-- Signing out revokes that session's token (by its id), so a copied cookie stops working at once.
-- Rows are only needed until the token would have expired anyway; old rows are pruned on each sign-out.
CREATE TABLE revoked_session (
    jti        VARCHAR(64) PRIMARY KEY,
    expires_at TIMESTAMPTZ NOT NULL
);
