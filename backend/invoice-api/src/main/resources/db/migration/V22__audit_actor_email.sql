-- The audit log stored the whole session record ("CurrentUser[userId=1, organizationId=1, email=x, role=ADMIN]")
-- as the actor; keep just the login email, which is what the "created by" columns hold. Sign-ins were
-- recorded before the session existed, so their actor is taken from the user they name.

UPDATE audit_log
SET actor = substring(actor from 'email=([^,]+),')
WHERE actor LIKE 'CurrentUser[%' AND substring(actor from 'email=([^,]+),') IS NOT NULL;

UPDATE audit_log a
SET actor = u.email
FROM app_user u
WHERE a.action = 'LOGIN' AND a.entity_type = 'user' AND a.entity_id = u.id;

CREATE INDEX IF NOT EXISTS audit_log_org_actor_idx ON audit_log (organization_id, lower(actor), created_at DESC);
