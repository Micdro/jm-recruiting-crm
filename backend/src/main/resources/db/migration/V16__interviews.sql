-- Interviews: the next (or latest) interview for a candidate in a search, and whether the
-- candidate and the client have been debriefed after it. Each round's scheduling and debriefs are
-- also written to the activity history.
--
-- These are columns on pipeline_entry rather than a table of their own, so a computer that hasn't
-- updated yet keeps syncing: it ignores columns it doesn't know, and its own changes to a pipeline
-- entry leave these columns as they are.

ALTER TABLE pipeline_entry ADD COLUMN interview_date DATE;
-- Optional: an interview without a time counts as over at the end of its day.
ALTER TABLE pipeline_entry ADD COLUMN interview_time TIME;
-- Encrypted by the app, for example "Round 2 with the CTO".
ALTER TABLE pipeline_entry ADD COLUMN interview_label TEXT;
ALTER TABLE pipeline_entry ADD COLUMN candidate_debriefed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE pipeline_entry ADD COLUMN client_debriefed BOOLEAN NOT NULL DEFAULT FALSE;
