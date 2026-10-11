-- 1. Field encryption ----------------------------------------------------------
-- Sensitive values are encrypted by the application (AES-256-GCM), so their columns become TEXT.
-- Existing plain-text values are encrypted on the next startup by EncryptionBackfill.

ALTER TABLE company ALTER COLUMN notes TYPE TEXT;

ALTER TABLE contact ALTER COLUMN email TYPE TEXT;
ALTER TABLE contact ALTER COLUMN phone TYPE TEXT;
ALTER TABLE contact ALTER COLUMN notes TYPE TEXT;
ALTER TABLE contact ADD COLUMN email_hash VARCHAR(64);
DROP INDEX IF EXISTS ix_contact_email;
CREATE INDEX ix_contact_email_hash ON contact (email_hash);

ALTER TABLE candidate ALTER COLUMN email TYPE TEXT;
ALTER TABLE candidate ALTER COLUMN phone TYPE TEXT;
ALTER TABLE candidate ALTER COLUMN comp_expectation TYPE TEXT;
ALTER TABLE candidate ALTER COLUMN resume_url TYPE TEXT;
ALTER TABLE candidate ALTER COLUMN production_evidence TYPE TEXT;
ALTER TABLE candidate ALTER COLUMN notes TYPE TEXT;
ALTER TABLE candidate ADD COLUMN email_hash VARCHAR(64);
DROP INDEX IF EXISTS ix_candidate_email;
CREATE INDEX ix_candidate_email_hash ON candidate (email_hash);

ALTER TABLE job_search ALTER COLUMN production_outcome TYPE TEXT;
ALTER TABLE job_search ALTER COLUMN technical_scope TYPE TEXT;
ALTER TABLE job_search ALTER COLUMN accountability TYPE TEXT;
ALTER TABLE job_search ALTER COLUMN regulatory_constraints TYPE TEXT;
ALTER TABLE job_search ALTER COLUMN notes TYPE TEXT;

ALTER TABLE pipeline_entry ALTER COLUMN rationale TYPE TEXT;

ALTER TABLE placement ALTER COLUMN base_salary TYPE TEXT USING base_salary::text;
ALTER TABLE placement ALTER COLUMN notes TYPE TEXT;

ALTER TABLE activity ALTER COLUMN summary TYPE TEXT;

-- 2. Companies: the Client List stages and fields from ClickUp -----------------------

ALTER TABLE company ALTER COLUMN status TYPE VARCHAR(30);
UPDATE company SET status = CASE status
    WHEN 'TARGET' THEN 'LEAD'
    WHEN 'PROSPECTING' THEN 'INITIAL_OUTREACH'
    WHEN 'IN_CONVERSATION' THEN 'ENGAGED'
    WHEN 'CLIENT' THEN 'PLACING'
    WHEN 'NOT_A_FIT' THEN 'NOT_QUALIFIED'
    ELSE status
END;

ALTER TABLE company ADD COLUMN industry VARCHAR(30);
ALTER TABLE company ADD COLUMN segments VARCHAR(200);
ALTER TABLE company ADD COLUMN tier VARCHAR(30);
ALTER TABLE company ADD COLUMN priority VARCHAR(20);
ALTER TABLE company ADD COLUMN hiring_need VARCHAR(30);
ALTER TABLE company ADD COLUMN hiring_timeline VARCHAR(30);
ALTER TABLE company ADD COLUMN anticipated_hires INTEGER;
ALTER TABLE company ADD COLUMN lead_source VARCHAR(30);
ALTER TABLE company ADD COLUMN last_contacted_date DATE;
ALTER TABLE company ADD COLUMN follow_up_date DATE;

CREATE INDEX ix_company_follow_up ON company (follow_up_date);

-- 3. Searches: timeline choices match the ClickUp intake (1-2 months, 3+ months) ------------

UPDATE job_search SET timeline = 'ONE_TO_TWO_MONTHS' WHERE timeline = 'WITHIN_60_DAYS';
UPDATE job_search SET timeline = 'THREE_PLUS_MONTHS' WHERE timeline = 'WITHIN_90_DAYS';

-- 4. Contacts: buying role (Buyer, Influencer, Talent, Other) ---------------------------

ALTER TABLE contact ADD COLUMN buying_role VARCHAR(20);

-- 5. Candidates: the Candidate List fields ---------------------------------------------

ALTER TABLE candidate ADD COLUMN role_areas VARCHAR(100);
ALTER TABLE candidate ADD COLUMN work_preference VARCHAR(20);
ALTER TABLE candidate ADD COLUMN experience VARCHAR(20);
ALTER TABLE candidate ADD COLUMN willing_to_relocate BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE candidate ADD COLUMN employment_type VARCHAR(20);
