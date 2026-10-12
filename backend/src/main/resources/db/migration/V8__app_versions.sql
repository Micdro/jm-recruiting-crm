-- Which version of the app each other computer runs, as it noted in the sync folder, so the
-- "This computer" page can point out one that needs updating. Not shared.
ALTER TABLE sync_peer_state ADD COLUMN app_build INTEGER;
