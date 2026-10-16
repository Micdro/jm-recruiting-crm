-- Guarantee check-ins: the day the team last checked in with a placed candidate and the client.
-- Check-ins are due 30 and 60 days after the start date, while the guarantee runs; one check-in
-- covers every check-in point that has come. Each check-in's notes go in the activity history.
-- A column on placement, so a computer that hasn't updated yet keeps syncing.
ALTER TABLE placement ADD COLUMN checked_in_on DATE;
