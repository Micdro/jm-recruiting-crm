import { useState } from 'react';
import { Link } from 'react-router';
import { api, errorMessage } from '../api.js';
import { ConfirmButton, Empty, ErrorNote, Loading, PageHeader } from '../components/Bits.jsx';
import { formatTimestamp } from '../format.js';
import { useLoader, useTitle, useToast } from '../hooks.js';

/** Where each kind of change opens. Kinds without a page of their own have no link. */
function pathOf(change) {
  const id = change.recordId;
  switch (change.kind) {
    case 'COMPANY':
      return id ? `/companies/${id}` : null;
    case 'CONTACT':
      return id ? `/contacts/${id}` : null;
    case 'CANDIDATE':
      return id ? `/candidates/${id}` : null;
    case 'SEARCH':
      return id ? `/searches/${id}` : null;
    case 'PLACEMENT':
      return '/placements';
    case 'TEMPLATE':
      return '/templates';
    case 'POST':
      return '/marketing';
    default:
      return null;
  }
}

/** "Today 3:10 PM" reads "today 3:10 PM" mid-sentence. */
const lowerFirst = (text) => text.charAt(0).toLowerCase() + text.slice(1);

/** Your last 20 changes on this computer from the last day, and undoing any of them. */
export default function RecentChangesPage() {
  useTitle('Recent changes');
  const toast = useToast();
  const changes = useLoader(() => api.recentChanges.list(), [], 'recent-changes');
  const [error, setError] = useState('');

  async function undo(change) {
    setError('');
    try {
      const undone = await api.recentChanges.undo(change.id);
      toast(undone.already ? 'That was already put back.' : `Undone: ${undone.label}`);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      changes.reload();
    }
  }

  const list = changes.data;

  return (
    <>
      <PageHeader
        title="Recent changes"
        sub="Your last 20 changes on this computer from the last day. Undo puts back everything a change did, if nothing in it has changed since."
      />
      <ErrorNote>{error || (changes.error && errorMessage(changes.error))}</ErrorNote>
      {!list && <Loading label="Loading your recent changes" />}
      {list && list.length === 0 && (
        <section className="panel">
          <Empty title="No changes in the last day">
            Edits, deletes, merges, imports, logged touches and AI Found reviews you make on this computer show up here.
          </Empty>
        </section>
      )}
      {list && list.length > 0 && (
        <section className="panel" aria-label="Your recent changes">
          <ul className="trash-list">
            {list.map((change) => {
              const path = change.undoneAt ? null : pathOf(change);
              return (
                <li key={change.id}>
                  <div className="trash-text">
                    <p className="trash-name">{path ? <Link to={path}>{change.label}</Link> : change.label}</p>
                    <p className="trash-meta">
                      {formatTimestamp(change.at)}
                      {change.undoneAt ? `. Undone ${lowerFirst(formatTimestamp(change.undoneAt))}.` : ''}
                    </p>
                  </div>
                  {change.undoneAt ? (
                    <span className="tag">Undone</span>
                  ) : (
                    <ConfirmButton
                      label="Undo"
                      confirmLabel="Undo it"
                      busyLabel="Undoing…"
                      className="btn btn-sm"
                      onConfirm={() => undo(change)}
                    />
                  )}
                </li>
              );
            })}
          </ul>
        </section>
      )}
    </>
  );
}
