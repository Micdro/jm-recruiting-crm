-- The account owner: the one person who can change what the CRM spends on the team's behalf (the
-- prospect finder's Claude API key and its daily and monthly limits) and hand that on to someone
-- else. Always an admin. The first admin, who set up the CRM, starts as the owner.
--
-- A flag rather than a new role, so computers that haven't updated yet keep treating the owner as
-- an admin: sync leaves out columns a computer doesn't have.
ALTER TABLE app_user ADD COLUMN account_owner BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE app_user SET account_owner = TRUE
WHERE id = (SELECT id FROM app_user WHERE role = 'ADMIN' AND active ORDER BY created_at, id LIMIT 1)
  AND NOT EXISTS (SELECT 1 FROM app_user WHERE account_owner);
