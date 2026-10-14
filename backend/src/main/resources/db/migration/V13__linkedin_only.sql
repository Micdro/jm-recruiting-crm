-- The firm stopped sending email: the cold sequence is LinkedIn for all four touches. Contacts
-- whose next touch or reply was set to go by email now go by LinkedIn.
--
-- Every computer makes the same change when it updates, so it is not shared through sync.
-- Sharing it would give these rows a new version, and a computer that updates later could then
-- overwrite edits it had not yet received with its older copy of the row.
ALTER TABLE contact DISABLE TRIGGER sync_track;

UPDATE contact
SET next_action_type = 'LINKEDIN'
WHERE next_action_type = 'EMAIL'
  AND status IN ('NEW', 'IN_SEQUENCE', 'REPLIED');

ALTER TABLE contact ENABLE TRIGGER sync_track;
