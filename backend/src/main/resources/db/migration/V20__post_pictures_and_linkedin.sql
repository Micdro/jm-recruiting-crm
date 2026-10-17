-- Pictures on posts. The file is kept in file_blob like candidates' files, encrypted by the app,
-- and travels between computers through the sync folder. Columns on content_post, so a computer
-- that hasn't updated yet keeps syncing.
ALTER TABLE content_post ADD COLUMN image_key VARCHAR(32);
ALTER TABLE content_post ADD COLUMN image_type VARCHAR(50);
ALTER TABLE content_post ADD COLUMN image_source VARCHAR(10);

-- Each person's LinkedIn connection on this computer, for posting to their own profile. Not shared:
-- the token stays on the computer it was made on. Name and token are encrypted by the app.
CREATE TABLE linkedin_account (
    user_id      BIGINT       NOT NULL PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    person_urn   VARCHAR(100) NOT NULL,
    name         TEXT,
    access_token TEXT         NOT NULL,
    expires_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    connected_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now()
);

-- Computers that hadn't updated when the first posts were planned skipped them. Read every
-- computer's sync files once more, so those posts arrive; what's already here is left as it is.
UPDATE sync_peer_state SET folder_instance = NULL;
