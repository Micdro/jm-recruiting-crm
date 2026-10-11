import { useState } from 'react';
import { errorMessage } from '../api.js';
import { ErrorNote } from './Bits.jsx';
import { SelectField, TextArea, TextField } from './Fields.jsx';

/**
 * A short note, call or meeting about a company or search. For companies, a follow-up date can be
 * set at the same time; logging a call, email or meeting clears a follow-up that was due.
 */
export default function NoteForm({ onSave, withFollowUp = false }) {
  const [summary, setSummary] = useState('');
  const [type, setType] = useState('NOTE');
  const [followUpDate, setFollowUpDate] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  return (
    <form
      className="note-form"
      onSubmit={async (event) => {
        event.preventDefault();
        setBusy(true);
        setError('');
        try {
          await onSave(withFollowUp ? { type, summary, followUpDate: followUpDate || null } : { type, summary });
          setSummary('');
          setFollowUpDate('');
        } catch (err) {
          setError(errorMessage(err));
        } finally {
          setBusy(false);
        }
      }}
    >
      <TextArea label="Add a note" value={summary} onChange={setSummary} rows={2} required maxLength={2000} />
      <div className="note-form-row">
        <SelectField
          label="Kind"
          value={type}
          onChange={setType}
          required
          options={[
            { value: 'NOTE', label: 'Note' },
            { value: 'CALL', label: 'Call' },
            { value: 'MEETING', label: 'Meeting' },
            { value: 'EMAIL', label: 'Email' },
          ]}
        />
        {withFollowUp && <TextField label="Follow up on" type="date" value={followUpDate} onChange={setFollowUpDate} />}
        <button type="submit" className="btn btn-sm" disabled={busy || !summary.trim()}>
          {busy ? 'Saving…' : 'Save note'}
        </button>
      </div>
      <ErrorNote>{error}</ErrorNote>
    </form>
  );
}
