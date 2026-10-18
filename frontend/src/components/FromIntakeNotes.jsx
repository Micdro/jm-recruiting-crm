import { useState } from 'react';
import { api, errorMessage } from '../api.js';
import { chosenProvider, readBy } from '../candidateAi.js';
import SearchForm from '../forms/SearchForm.jsx';
import { useLoader } from '../hooks.js';
import { ErrorNote } from './Bits.jsx';
import { TextArea } from './Fields.jsx';

/**
 * Paste notes from a client intake call: AI fills in the search form, the brief and a job
 * description. The person checks the form and opens the search. `company` ({ id, name }) when the
 * notes are read from the client's page.
 */
export default function FromIntakeNotes({ company = null, onCancel, onOpened }) {
  const ai = useLoader(() => api.ai.status(), [], 'ai:status');
  const [notes, setNotes] = useState('');
  const [draft, setDraft] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const provider = chosenProvider(ai.data);
  const noKey = provider && !provider.hasKey;

  async function read(event) {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      setDraft(await api.searches.readIntake({ notes, companyId: company?.id ?? null }));
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  if (draft) {
    let companyNote = null;
    if (!draft.search.companyId) {
      if (draft.companies.length > 1) companyNote = `More than one company in the CRM matches “${draft.companyHeard}”. Pick the right one.`;
      else if (draft.companyHeard) companyNote = `${draft.companyHeard} isn’t in the CRM yet. Pick the company, or add it on Companies first.`;
      else companyNote = 'The notes don’t name the company. Pick it below.';
    }
    return (
      <>
        <p className="ai-note">
          {readBy(draft.provider, draft.costCents)}. The brief and job description are filled in too. Check each field, then
          open the search.
        </p>
        {companyNote && (
          <p className="warn-note" role="note">
            {companyNote}
          </p>
        )}
        {draft.hiringManager && (
          <p className="warn-note" role="note">
            The notes name {draft.hiringManager} as the hiring manager, who isn’t a contact at the company yet. Add them on
            the company’s page to link them to the search.
          </p>
        )}
        <SearchForm search={draft.search} onCancel={onCancel} onSaved={onOpened} />
      </>
    );
  }

  return (
    <form className="form" onSubmit={read}>
      <TextArea
        label="Notes from the intake call"
        value={notes}
        onChange={setNotes}
        rows={12}
        maxLength={20000}
        required
        autoFocus
        placeholder="Rough is fine: the role, what must be live in production, the stack, pay, location, timeline, the interview process, who you spoke to"
      />
      {noKey ? (
        <p className="warn-note" role="note">
          Reading intake notes needs {provider.id === 'OPENAI' ? 'an' : 'a'} {provider.label} API key.{' '}
          {ai.data.canManage ? 'Add one in the AI settings on AI Found.' : `Ask ${ai.data.owner ?? 'an admin'} to add one.`}
        </p>
      ) : (
        <p className="field-hint">
          Your notes go to {provider?.label ?? 'the AI'} to read. You check the search before it’s opened.
        </p>
      )}
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="button" className="btn" onClick={onCancel}>
          Cancel
        </button>
        <button type="submit" className="btn btn-primary" disabled={busy || noKey || !notes.trim()}>
          {busy ? 'Reading…' : 'Read notes'}
        </button>
      </div>
    </form>
  );
}
