-- Sync between computers that each keep their own copy of the CRM.
--
-- Every insert, update and delete on a shared table records the row's latest version in sync_row:
-- when it changed, on which computer (origin), whether it was deleted, and the transaction that
-- wrote it. Other computers ask for everything written since the last transaction they saw and
-- keep the newest version of each row (last write wins). On a server with no sync set up, the
-- tracking is harmless and costs one small write per change.

-- This computer. One row once sync is set up; empty on a plain server.
CREATE TABLE sync_self (
    node_id INTEGER     NOT NULL PRIMARY KEY,
    name    VARCHAR(100) NOT NULL
);

-- Every computer in the group, with the address the others reach it at. Shared like the data.
CREATE TABLE sync_node (
    id         BIGINT       NOT NULL PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    address    VARCHAR(255),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now()
);

-- The latest version of every shared row.
CREATE TABLE sync_row (
    table_name VARCHAR(40) NOT NULL,
    row_id     BIGINT      NOT NULL,
    version    TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    origin     INTEGER     NOT NULL,
    deleted    BOOLEAN     NOT NULL,
    txid       BIGINT      NOT NULL,
    PRIMARY KEY (table_name, row_id)
);

CREATE INDEX ix_sync_row_txid ON sync_row (txid);

-- How far this computer has read each other computer's changes, and how that went. Not shared.
CREATE TABLE sync_peer_state (
    node_id      BIGINT NOT NULL PRIMARY KEY,
    cursor       BIGINT NOT NULL DEFAULT 0,
    last_attempt TIMESTAMP(6) WITH TIME ZONE,
    last_success TIMESTAMP(6) WITH TIME ZONE,
    last_error   VARCHAR(500)
);

-- Changes that arrived before something they depend on (for example a contact whose company
-- hasn't arrived yet). Retried after every sync. Not shared.
CREATE TABLE sync_pending (
    table_name  VARCHAR(40) NOT NULL,
    row_id      BIGINT      NOT NULL,
    change      TEXT        NOT NULL,
    reason      VARCHAR(500),
    received_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (table_name, row_id)
);

CREATE FUNCTION sync_track() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    rid BIGINT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        rid := OLD.id;
    ELSE
        rid := NEW.id;
    END IF;

    -- A change being copied in from another computer records its own version instead.
    IF current_setting('jmcrm.applying_row', true) = TG_TABLE_NAME || ':' || rid THEN
        RETURN NULL;
    END IF;

    INSERT INTO sync_row (table_name, row_id, version, origin, deleted, txid)
    VALUES (TG_TABLE_NAME, rid, clock_timestamp(),
            coalesce((SELECT node_id FROM sync_self LIMIT 1), 0),
            TG_OP = 'DELETE', pg_current_xact_id()::text::bigint)
    ON CONFLICT (table_name, row_id) DO UPDATE
        SET version = EXCLUDED.version,
            origin = EXCLUDED.origin,
            deleted = EXCLUDED.deleted,
            txid = EXCLUDED.txid;
    RETURN NULL;
END;
$$;

CREATE TRIGGER sync_track AFTER INSERT OR UPDATE OR DELETE ON sync_node FOR EACH ROW EXECUTE FUNCTION sync_track();
CREATE TRIGGER sync_track AFTER INSERT OR UPDATE OR DELETE ON app_user FOR EACH ROW EXECUTE FUNCTION sync_track();
CREATE TRIGGER sync_track AFTER INSERT OR UPDATE OR DELETE ON company FOR EACH ROW EXECUTE FUNCTION sync_track();
CREATE TRIGGER sync_track AFTER INSERT OR UPDATE OR DELETE ON contact FOR EACH ROW EXECUTE FUNCTION sync_track();
CREATE TRIGGER sync_track AFTER INSERT OR UPDATE OR DELETE ON candidate FOR EACH ROW EXECUTE FUNCTION sync_track();
CREATE TRIGGER sync_track AFTER INSERT OR UPDATE OR DELETE ON job_search FOR EACH ROW EXECUTE FUNCTION sync_track();
CREATE TRIGGER sync_track AFTER INSERT OR UPDATE OR DELETE ON pipeline_entry FOR EACH ROW EXECUTE FUNCTION sync_track();
CREATE TRIGGER sync_track AFTER INSERT OR UPDATE OR DELETE ON placement FOR EACH ROW EXECUTE FUNCTION sync_track();
CREATE TRIGGER sync_track AFTER INSERT OR UPDATE OR DELETE ON activity FOR EACH ROW EXECUTE FUNCTION sync_track();

-- Rows that already exist are versioned now, so they are shared too.
INSERT INTO sync_row (table_name, row_id, version, origin, deleted, txid)
SELECT t.table_name, t.id, clock_timestamp(), 0, FALSE, pg_current_xact_id()::text::bigint
FROM (
    SELECT 'app_user' AS table_name, id FROM app_user
    UNION ALL SELECT 'company', id FROM company
    UNION ALL SELECT 'contact', id FROM contact
    UNION ALL SELECT 'candidate', id FROM candidate
    UNION ALL SELECT 'job_search', id FROM job_search
    UNION ALL SELECT 'pipeline_entry', id FROM pipeline_entry
    UNION ALL SELECT 'placement', id FROM placement
    UNION ALL SELECT 'activity', id FROM activity
) t;
