-- Direct connections between computers, alongside the shared folder. None of this is shared.

-- Whether this computer accepts direct connections (its sync port is open).
ALTER TABLE sync_self ADD COLUMN direct_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- Per other computer: the addresses it said (in the folder) it can be reached at, the one that
-- last worked, how direct syncing with it went, how far this computer has sent it its own changes,
-- and when it last fetched changes from this computer (so this one needn't send them as well).
ALTER TABLE sync_peer_state ADD COLUMN direct_addresses TEXT;
ALTER TABLE sync_peer_state ADD COLUMN direct_address VARCHAR(255);
ALTER TABLE sync_peer_state ADD COLUMN direct_last_success TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE sync_peer_state ADD COLUMN direct_last_error VARCHAR(500);
ALTER TABLE sync_peer_state ADD COLUMN push_cursor BIGINT;
ALTER TABLE sync_peer_state ADD COLUMN pulled_by_at TIMESTAMP(6) WITH TIME ZONE;
