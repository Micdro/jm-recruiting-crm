import { useState } from 'react';
import { api, errorMessage } from '../api.js';
import { ErrorNote } from './Bits.jsx';

const KIND = { contacts: 'CONTACT', companies: 'COMPANY', candidates: 'CANDIDATE' };

/**
 * Merging two records that are the same person or company. Shows both side by side and what the
 * kept one will look like after: its own values, with empty ones filled from the other.
 *
 * fields: [{ label, value(record), fill }]. fill: the other's value fills an empty one (most
 * details); without it the kept record's value stays (name, status, stage). combine(a, b), when
 * given, says what the merged record will have (a count of contacts, say).
 */
export default function MergeDialog({ kind, records, fields, moves, onMerged, onCancel }) {
  const [first, second] = records;
  // The older record is usually the one the history is on, so it's kept unless you choose otherwise.
  const [keepId, setKeepId] = useState(Math.min(first.id, second.id));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const kept = keepId === first.id ? first : second;
  const other = kept === first ? second : first;

  function after(field) {
    if (field.combine) return field.combine(kept, other);
    const mine = field.value(kept);
    return field.fill && (mine === null || mine === undefined || mine === '') ? field.value(other) : mine;
  }

  async function merge() {
    setBusy(true);
    setError('');
    try {
      onMerged(await api.merge({ kind: KIND[kind], keepId: kept.id, mergeId: other.id }));
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  return (
    <div className="form">
      <p className="form-intro">
        Choose the one to keep. Everything on the other moves to it ({moves}). Empty details are filled from the other, notes
        are combined, and the other goes to the Trash. The kept record&rsquo;s history says what was merged in.
      </p>
      <div className="table-wrap">
        <table className="table table-compact merge-table">
          <thead>
            <tr>
              <th scope="col">
                <span className="visually-hidden">Detail</span>
              </th>
              {[first, second].map((record) => (
                <th scope="col" key={record.id} className={record === kept ? 'merge-kept' : undefined}>
                  <label className="merge-pick">
                    <input
                      type="radio"
                      name="merge-keep"
                      checked={record === kept}
                      onChange={() => setKeepId(record.id)}
                      aria-label={`Keep ${record.name}`}
                    />
                    {record === kept ? 'Keep' : 'Merge in'}
                  </label>
                </th>
              ))}
              <th scope="col">After merging</th>
            </tr>
          </thead>
          <tbody>
            {fields.map((field) => (
              <tr key={field.label}>
                <th scope="row">{field.label}</th>
                {[first, second].map((record) => (
                  <td key={record.id} className={record === kept ? 'merge-kept' : undefined}>
                    <Value value={field.value(record)} />
                  </td>
                ))}
                <td className="merge-after">
                  <Value value={after(field)} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="button" className="btn" onClick={onCancel}>
          Cancel
        </button>
        <button type="button" className="btn btn-primary" disabled={busy} onClick={merge}>
          {busy ? 'Merging…' : `Merge into ${kept.name}`}
        </button>
      </div>
    </div>
  );
}

function Value({ value }) {
  if (value === null || value === undefined || value === '') return <span className="muted">Empty</span>;
  return <>{value}</>;
}
