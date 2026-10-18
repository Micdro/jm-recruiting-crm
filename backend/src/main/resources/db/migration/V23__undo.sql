-- Recent changes: what each action changed, so the person who made it can undo it later the same
-- day. Kept on this computer only (not synced): an action is undone on the computer it was made on,
-- and the undo then syncs like any other change.
--
-- undo_row holds each shared row's state before and after every change made here. Changes copied
-- in from another computer aren't recorded. undo_action names the actions people took, by the
-- transaction that made them; rows of transactions that aren't an action are cleared out after a
-- few minutes. Both are cleared after a day.

CREATE TABLE undo_action (
    txid       BIGINT PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    -- Encrypted by the app: it names people and companies.
    label      TEXT         NOT NULL,
    kind       VARCHAR(30)  NOT NULL,
    record_id  BIGINT,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now(),
    undone_at  TIMESTAMP(6) WITH TIME ZONE
);

CREATE INDEX ix_undo_action_user ON undo_action (user_id, created_at);

CREATE TABLE undo_row (
    seq        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    txid       BIGINT       NOT NULL,
    table_name VARCHAR(40)  NOT NULL,
    row_id     BIGINT       NOT NULL,
    old_row    JSONB,
    new_row    JSONB,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX ix_undo_row_txid ON undo_row (txid);
CREATE INDEX ix_undo_row_created ON undo_row (created_at);

CREATE FUNCTION undo_capture() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    rid BIGINT;
BEGIN
    -- Restoring a backup, or undoing an action, isn't itself recorded.
    IF current_setting('jmcrm.undo_off', true) = 'on' THEN
        RETURN NULL;
    END IF;

    IF TG_OP = 'DELETE' THEN
        rid := OLD.id;
    ELSE
        rid := NEW.id;
    END IF;

    -- A change copied in from another computer isn't this computer's to undo.
    IF current_setting('jmcrm.applying_row', true) = TG_TABLE_NAME || ':' || rid THEN
        RETURN NULL;
    END IF;

    -- The prospect finder's running totals (spend, last run, what the team thought) change all the
    -- time; undoing an action leaves them be.
    IF TG_TABLE_NAME = 'firm_setting' THEN
        IF TG_OP = 'DELETE' THEN
            IF OLD.name LIKE 'prospects.%' THEN
                RETURN NULL;
            END IF;
        ELSIF NEW.name LIKE 'prospects.%' THEN
            RETURN NULL;
        END IF;
    END IF;

    INSERT INTO undo_row (txid, table_name, row_id, old_row, new_row)
    VALUES (pg_current_xact_id()::text::bigint, TG_TABLE_NAME, rid,
            CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE to_jsonb(OLD) END,
            CASE WHEN TG_OP = 'DELETE' THEN NULL ELSE to_jsonb(NEW) END);
    RETURN NULL;
END;
$$;

CREATE TRIGGER undo_capture AFTER INSERT OR UPDATE OR DELETE ON message_template FOR EACH ROW EXECUTE FUNCTION undo_capture();
CREATE TRIGGER undo_capture AFTER INSERT OR UPDATE OR DELETE ON content_post FOR EACH ROW EXECUTE FUNCTION undo_capture();
CREATE TRIGGER undo_capture AFTER INSERT OR UPDATE OR DELETE ON post_template FOR EACH ROW EXECUTE FUNCTION undo_capture();
CREATE TRIGGER undo_capture AFTER INSERT OR UPDATE OR DELETE ON firm_setting FOR EACH ROW EXECUTE FUNCTION undo_capture();
CREATE TRIGGER undo_capture AFTER INSERT OR UPDATE OR DELETE ON company FOR EACH ROW EXECUTE FUNCTION undo_capture();
CREATE TRIGGER undo_capture AFTER INSERT OR UPDATE OR DELETE ON contact FOR EACH ROW EXECUTE FUNCTION undo_capture();
CREATE TRIGGER undo_capture AFTER INSERT OR UPDATE OR DELETE ON candidate FOR EACH ROW EXECUTE FUNCTION undo_capture();
CREATE TRIGGER undo_capture AFTER INSERT OR UPDATE OR DELETE ON candidate_file FOR EACH ROW EXECUTE FUNCTION undo_capture();
CREATE TRIGGER undo_capture AFTER INSERT OR UPDATE OR DELETE ON job_search FOR EACH ROW EXECUTE FUNCTION undo_capture();
CREATE TRIGGER undo_capture AFTER INSERT OR UPDATE OR DELETE ON pipeline_entry FOR EACH ROW EXECUTE FUNCTION undo_capture();
CREATE TRIGGER undo_capture AFTER INSERT OR UPDATE OR DELETE ON placement FOR EACH ROW EXECUTE FUNCTION undo_capture();
CREATE TRIGGER undo_capture AFTER INSERT OR UPDATE OR DELETE ON activity FOR EACH ROW EXECUTE FUNCTION undo_capture();
CREATE TRIGGER undo_capture AFTER INSERT OR UPDATE OR DELETE ON trash_item FOR EACH ROW EXECUTE FUNCTION undo_capture();
