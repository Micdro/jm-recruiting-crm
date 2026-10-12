import { useState } from 'react';
import { useNavigate } from 'react-router';
import { api, errorMessage } from '../api.js';
import { Empty, ErrorNote, Loading, PageHeader } from '../components/Bits.jsx';
import { formatDate, toIsoDate } from '../format.js';
import { useLoader, useTitle, useToast } from '../hooks.js';

const KIND = {
  COMPANY: 'Company',
  CONTACT: 'Contact',
  CANDIDATE: 'Candidate',
  SEARCH: 'Search',
  PLACEMENT: 'Placement',
  FILE: 'File',
};

const day = (instant) => formatDate(toIsoDate(new Date(instant)));

/** Deleted records, kept for 30 days on every computer, and putting them back. */
export default function TrashPage() {
  useTitle('Trash');
  const toast = useToast();
  const navigate = useNavigate();
  const items = useLoader(() => api.trash.list(), [], 'trash');
  const [busy, setBusy] = useState(null);
  const [error, setError] = useState('');

  async function restore(item) {
    setBusy(item.id);
    setError('');
    try {
      const restored = await api.trash.restore(item.id);
      items.reload();
      toast(`Restored ${restored.label}`, { action: { label: 'Open', onClick: () => navigate(restored.path) } });
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(null);
    }
  }

  const list = items.data;

  return (
    <>
      <PageHeader
        title="Trash"
        sub="Deleted records wait here for 30 days, with their history and files, then are removed for good."
      />
      <ErrorNote>{error || (items.error && errorMessage(items.error))}</ErrorNote>
      {!list && <Loading label="Loading the Trash" />}
      {list && list.length === 0 && (
        <section className="panel">
          <Empty title="The Trash is empty">
            Deleted companies, contacts, candidates, searches, placements and files show up here, on every computer.
          </Empty>
        </section>
      )}
      {list && list.length > 0 && (
        <section className="panel" aria-label="Deleted records">
          <ul className="trash-list">
            {list.map((item) => (
              <li key={item.id}>
                <div className="trash-text">
                  <p className="trash-name">
                    {item.label} <span className="tag">{KIND[item.kind]}</span>
                  </p>
                  {item.detail && <p className="trash-detail">{item.detail}</p>}
                  <p className="trash-meta">
                    Deleted {day(item.deletedAt)}
                    {item.deletedByName ? ` by ${item.deletedByName}` : ''}. Removed for good {day(item.removedForGoodAt)}.
                  </p>
                </div>
                <button type="button" className="btn btn-sm" disabled={busy === item.id} onClick={() => restore(item)}>
                  {busy === item.id ? 'Restoring…' : 'Restore'}
                </button>
              </li>
            ))}
          </ul>
        </section>
      )}
    </>
  );
}
