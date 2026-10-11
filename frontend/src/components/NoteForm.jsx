import { useState } from 'react';
import { errorMessage } from '../api.js';
import { ErrorNote } from './Bits.jsx';
import { SelectField, TextArea } from './Fields.jsx';

/** A short note, call or meeting about a company or search. */
export default function NoteForm({ onSave }) {
  const [summary, setSummary] = useState('');
  const [type, setType] = useState('NOTE');
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
          await onSave({ type, summary });
          setSummary('');
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
        <button type="submit" className="btn btn-sm" disabled={busy || !summary.trim()}>
          {busy ? 'Saving…' : 'Save note'}
        </button>
      </div>
      <ErrorNote>{error}</ErrorNote>
    </form>
  );
}
