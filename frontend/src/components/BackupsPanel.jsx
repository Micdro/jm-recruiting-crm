import { useState } from 'react';
import { api, errorMessage } from '../api.js';
import { ErrorNote } from './Bits.jsx';
import Modal from './Modal.jsx';
import { formatTimestamp, plural } from '../format.js';
import { useLoader, useToast } from '../hooks.js';

const KIND = {
  WEEKLY: 'Weekly',
  MANUAL: 'Backed up by hand',
  BEFORE_RESTORE: 'Before a restore',
};

function size(bytes) {
  if (bytes >= 1_000_000) return `${(bytes / 1_000_000).toFixed(1)} MB`;
  return `${Math.max(1, Math.round(bytes / 1_000))} KB`;
}

/**
 * This computer's backups: made each week, kept here, encrypted. Anyone can back up now or download
 * one; the owner can restore one, which replaces the shared records on every computer.
 */
export default function BackupsPanel() {
  const toast = useToast();
  const backups = useLoader(() => api.backups.status(), [], 'backups');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [restoring, setRestoring] = useState(null);

  const data = backups.data;
  if (!data || !data.available) return null;

  async function backUpNow() {
    setBusy(true);
    setError('');
    try {
      backups.setData(await api.backups.backUp());
      toast('Backed up');
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="panel" aria-labelledby="backups-heading">
      <div className="panel-head">
        <h2 id="backups-heading">Backups</h2>
        <button type="button" className="btn btn-sm" disabled={busy} onClick={backUpNow}>
          {busy ? 'Backing up…' : 'Back up now'}
        </button>
      </div>
      <p className="panel-meta">
        This computer backs up every record and resume each week, encrypted, and keeps the newest {data.keep} in{' '}
        <span className="folder-path">{data.folder}</span>. Download one to keep a copy somewhere else.{' '}
        {data.canRestore
          ? 'Restoring replaces everything with the backup, on every computer.'
          : `Only ${data.ownerName ?? 'the owner'} can restore one.`}
      </p>
      <ErrorNote>{error}</ErrorNote>
      {data.backups.length === 0 ? (
        <p className="muted">No backups yet. The first one is made within the hour.</p>
      ) : (
        <ul className="trash-list backup-list">
          {data.backups.map((backup) => (
            <li key={backup.name}>
              <div className="trash-text">
                <p className="trash-name">{formatTimestamp(backup.createdAt)}</p>
                <p className="trash-meta">
                  {KIND[backup.kind]} · {size(backup.sizeBytes)}
                </p>
              </div>
              <span className="setting-actions">
                <a className="btn btn-sm btn-quiet" href={api.backups.fileUrl(backup.name)} download={backup.name}>
                  Download
                </a>
                {data.canRestore && (
                  <button type="button" className="btn btn-sm" onClick={() => setRestoring(backup)}>
                    Restore
                  </button>
                )}
              </span>
            </li>
          ))}
        </ul>
      )}

      {restoring && (
        <Modal title="Restore this backup?" onClose={() => setRestoring(null)}>
          <RestoreForm
            backup={restoring}
            onCancel={() => setRestoring(null)}
            onRestored={(result) => {
              setRestoring(null);
              toast(`Restored ${plural(result.records, 'record')} from ${formatTimestamp(result.backedUpAt)}. Reloading…`);
              // Every page's data changed: start fresh.
              setTimeout(() => window.location.reload(), 1500);
            }}
          />
        </Modal>
      )}
    </section>
  );
}

function RestoreForm({ backup, onCancel, onRestored }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function restore(event) {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      onRestored(await api.backups.restore(backup.name));
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  return (
    <form className="form" onSubmit={restore}>
      <p className="form-intro">
        Everything in the CRM goes back to how it was on {formatTimestamp(backup.createdAt)}: companies, contacts,
        candidates, searches, placements, history, templates and the Trash. Anything added or changed since then is
        replaced, on every computer. The team’s accounts and the AI Found key and limits stay as they are.
      </p>
      <p className="form-intro">
        The CRM backs up what’s here now first, so you can undo this by restoring that backup.
      </p>
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="button" className="btn" onClick={onCancel}>
          Cancel
        </button>
        <button type="submit" className="btn btn-danger-solid" disabled={busy}>
          {busy ? 'Restoring…' : 'Restore this backup'}
        </button>
      </div>
    </form>
  );
}
