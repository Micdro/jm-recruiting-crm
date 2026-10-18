-- A job description on each search, for the posting or to send candidates. Encrypted by the app.
-- A computer that hasn't updated yet keeps syncing searches and leaves this column alone.
ALTER TABLE job_search ADD COLUMN job_description TEXT;
