import { useState } from 'react';
import { api, errorMessage } from '../api.js';
import { formatDate } from '../format.js';
import { ErrorNote } from './Bits.jsx';
import { TextArea } from './Fields.jsx';

/** Notes from a guarantee check-in with a placed candidate and the client. */
export default function CheckInForm({ placement, onSaved, onCancel }) {
  const [notes, setNotes] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const firstName = placement.candidateName.split(' ')[0];

  async function save(event) {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      onSaved(await api.placements.checkIn(placement.id, { notes: notes.trim() || null }));
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  return (
    <form className="form" onSubmit={save}>
      <p className="form-intro">
        How are {firstName} and {placement.companyName} finding it, {placement.checkInDays} days in? Anything at risk
        {placement.guaranteeEndDate ? ` before the guarantee ends on ${formatDate(placement.guaranteeEndDate)}` : ''}?
      </p>
      <TextArea
        label="Notes"
        hint="Optional. They go in the history."
        value={notes}
        onChange={setNotes}
        rows={5}
        maxLength={4000}
        autoFocus
      />
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="button" className="btn" onClick={onCancel}>
          Cancel
        </button>
        <button type="submit" className="btn btn-primary" disabled={busy}>
          Save check-in
        </button>
      </div>
    </form>
  );
}
