-- Syncing through a shared folder (Google Drive, OneDrive): each computer writes its changes there
-- as encrypted files and reads the others'. None of this is shared between computers.

-- Where the folder is on this computer, how far this computer has written its own changes there,
-- and a random id for this computer's files (new each time it starts using a folder).
ALTER TABLE sync_self ADD COLUMN folder TEXT;
ALTER TABLE sync_self ADD COLUMN folder_cursor BIGINT NOT NULL DEFAULT 0;
ALTER TABLE sync_self ADD COLUMN folder_seq BIGINT NOT NULL DEFAULT 0;
ALTER TABLE sync_self ADD COLUMN folder_instance VARCHAR(64);

-- How far this computer has read each other computer's files, which set of files that was, and
-- when that computer last noted in the folder that it was running.
ALTER TABLE sync_peer_state ADD COLUMN folder_seq BIGINT NOT NULL DEFAULT 0;
ALTER TABLE sync_peer_state ADD COLUMN folder_instance VARCHAR(64);
ALTER TABLE sync_peer_state ADD COLUMN last_seen TIMESTAMP(6) WITH TIME ZONE;
