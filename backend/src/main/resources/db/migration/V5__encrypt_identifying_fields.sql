-- Everything that identifies a person or company is now encrypted by the app (names, titles,
-- employers, LinkedIn pages, locations, websites, search titles and money amounts), so those
-- columns become TEXT. EncryptionBackfill encrypts existing values the next time the CRM unlocks.

ALTER TABLE company ALTER COLUMN name TYPE TEXT;
ALTER TABLE company ALTER COLUMN website TYPE TEXT;
ALTER TABLE company ALTER COLUMN location TYPE TEXT;
ALTER TABLE company ALTER COLUMN focus TYPE TEXT;
ALTER TABLE company ALTER COLUMN linkedin_profile TYPE TEXT;
-- Encrypted names can't be compared, so duplicates are found by a keyed fingerprint instead.
ALTER TABLE company ADD COLUMN name_hash VARCHAR(64);
DROP INDEX IF EXISTS ix_company_name;
CREATE INDEX ix_company_name_hash ON company (name_hash);

ALTER TABLE contact ALTER COLUMN name TYPE TEXT;
ALTER TABLE contact ALTER COLUMN title TYPE TEXT;
ALTER TABLE contact ALTER COLUMN linkedin_url TYPE TEXT;

ALTER TABLE candidate ALTER COLUMN name TYPE TEXT;
ALTER TABLE candidate ALTER COLUMN linkedin_url TYPE TEXT;
ALTER TABLE candidate ALTER COLUMN location TYPE TEXT;
ALTER TABLE candidate ALTER COLUMN current_title TYPE TEXT;
ALTER TABLE candidate ALTER COLUMN current_company TYPE TEXT;

ALTER TABLE job_search ALTER COLUMN title TYPE TEXT;
ALTER TABLE job_search ALTER COLUMN location TYPE TEXT;
ALTER TABLE job_search ALTER COLUMN base_min TYPE TEXT USING base_min::text;
ALTER TABLE job_search ALTER COLUMN base_max TYPE TEXT USING base_max::text;

ALTER TABLE placement ALTER COLUMN fee_amount TYPE TEXT USING fee_amount::text;

-- The CRM key, locked with each person's password. Signing in opens it; nothing else on disk does.
ALTER TABLE app_user ADD COLUMN key_wrap TEXT;
