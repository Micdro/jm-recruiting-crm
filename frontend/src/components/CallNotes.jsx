import { useId, useState } from 'react';
import { api, errorMessage } from '../api.js';
import { CALL_CHANGES, chosenProvider, readBy, shown } from '../candidateAi.js';
import { formatWeekday, todayIso } from '../format.js';
import { useLoader } from '../hooks.js';
import { ACTION_TYPE, options } from '../labels.js';
import { ErrorNote } from './Bits.jsx';
import { SelectField, TextArea, TextField } from './Fields.jsx';

const TYPES = [
  ['CALL', 'Call'],
  ['MEETING', 'Meeting'],
];
const ACTION_OPTIONS = options(ACTION_TYPE);

/** What saving without a next action date does to the follow-up. */
function emptyDateHint(candidate, today) {
  if (candidate.nextActionDate && candidate.nextActionDate <= today) return 'Leave it empty to mark the follow-up that was due as done.';
  if (candidate.nextActionDate) return `Leave it empty to keep the follow-up on ${formatWeekday(candidate.nextActionDate)}.`;
  return 'Leave it empty for no follow-up.';
}

/**
 * Paste rough notes from a call: AI writes them up for the timeline, picks out the next step and
 * what changed on the record. The person checks it, unticks anything wrong, and saves.
 */
export default function CallNotes({ candidate, searches = [], onCancel, onSaved }) {
  const groupId = useId();
  const ai = useLoader(() => api.ai.status(), [], 'ai:status');
  const today = todayIso();
  const firstName = candidate.name.split(' ')[0];

  const [type, setType] = useState('CALL');
  const [notes, setNotes] = useState('');
  const [draft, setDraft] = useState(null);
  const [summary, setSummary] = useState('');
  const [kept, setKept] = useState({});
  const [keepEvidence, setKeepEvidence] = useState(true);
  const [nextDate, setNextDate] = useState('');
  const [nextType, setNextType] = useState('CALL');
  const [searchId, setSearchId] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const provider = chosenProvider(ai.data);
  const noKey = provider && !provider.hasKey;
  const changes = draft ? CALL_CHANGES.filter((change) => draft.update[change.key] !== null && draft.update[change.key] !== undefined) : [];
  const evidence = draft?.update.addEvidence;
  const noun = type === 'MEETING' ? 'meeting' : 'call';

  async function read(event) {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      const result = await api.candidates.readCallNotes(candidate.id, { type, notes });
      setDraft(result);
      setSummary(result.update.summary ?? '');
      setKept(Object.fromEntries(CALL_CHANGES.map((change) => [change.key, true])));
      setKeepEvidence(true);
      setNextDate(result.update.nextActionDate ?? '');
      setNextType(result.update.nextActionType ?? 'CALL');
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  async function save(event) {
    event.preventDefault();
    setBusy(true);
    setError('');
    const body = {
      type,
      summary: summary.trim(),
      addEvidence: keepEvidence ? evidence ?? null : null,
      nextActionDate: nextDate || null,
      nextActionType: nextDate ? nextType : null,
      searchId: searchId ? Number(searchId) : null,
    };
    for (const change of CALL_CHANGES) {
      body[change.key] = kept[change.key] ? draft.update[change.key] ?? null : null;
    }
    try {
      onSaved(await api.candidates.saveCall(candidate.id, body), noun);
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  if (!draft) {
    return (
      <form className="form" onSubmit={read}>
        <fieldset className="segmented">
          <legend className="visually-hidden">Notes from a</legend>
          {TYPES.map(([value, label]) => (
            <label key={value} className={type === value ? 'segment segment-on' : 'segment'}>
              <input type="radio" name={`${groupId}-type`} value={value} checked={type === value} onChange={() => setType(value)} />
              {label}
            </label>
          ))}
        </fieldset>
        <TextArea
          label="Your notes"
          value={notes}
          onChange={setNotes}
          rows={10}
          maxLength={20000}
          required
          autoFocus
          placeholder="Rough is fine: what they want, pay, timing, where they're interviewing, what you agreed to do next"
        />
        {noKey ? (
          <p className="warn-note" role="note">
            Reading notes needs {provider.id === 'OPENAI' ? 'an' : 'a'} {provider.label} API key.{' '}
            {ai.data.canManage ? 'Add one in the AI settings on AI Found.' : `Ask ${ai.data.owner ?? 'an admin'} to add one.`}
          </p>
        ) : (
          <p className="field-hint">
            Your notes go to {provider?.label ?? 'the AI'} to read. You check everything before it’s saved.
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

  return (
    <form className="form" onSubmit={save}>
      <p className="ai-note">{readBy(draft.provider, draft.costCents)}. Check it, untick anything that’s wrong, then save.</p>
      <TextArea label={`For ${firstName}’s timeline`} value={summary} onChange={setSummary} rows={7} maxLength={2000} required />

      {changes.length > 0 || evidence ? (
        <fieldset className="field call-changes">
          <legend>Update {firstName}’s record</legend>
          {changes.map((change) => (
            <label key={change.key} className="call-change">
              <input
                type="checkbox"
                checked={!!kept[change.key]}
                onChange={(event) => setKept((current) => ({ ...current, [change.key]: event.target.checked }))}
              />
              <span>
                <strong>{change.label}:</strong> <span className="call-change-from">{shown(change, candidate[change.key])}</span>{' '}
                <span aria-hidden="true">→</span>
                <span className="visually-hidden">to</span> {shown(change, draft.update[change.key])}
              </span>
            </label>
          ))}
          {evidence && (
            <label className="call-change">
              <input type="checkbox" checked={keepEvidence} onChange={(event) => setKeepEvidence(event.target.checked)} />
              <span>
                <strong>Add to production evidence</strong>
                <span className="call-change-evidence">{evidence}</span>
              </span>
            </label>
          )}
        </fieldset>
      ) : (
        <p className="muted">Nothing else on {firstName}’s record changes.</p>
      )}

      <div className="form-row">
        <TextField
          label="Next action date"
          type="date"
          value={nextDate}
          onChange={setNextDate}
          min={today}
          hint={nextDate ? `${formatWeekday(nextDate)}${draft.nextStep ? `: ${draft.nextStep}` : ''}` : emptyDateHint(candidate, today)}
        />
        <SelectField label="Next action type" value={nextType} onChange={setNextType} options={ACTION_OPTIONS} required />
      </div>
      {searches.length > 0 && (
        <SelectField
          label="About a search"
          value={searchId}
          onChange={setSearchId}
          placeholder="Not tied to a search"
          options={searches.map((entry) => ({ value: String(entry.searchId), label: `${entry.searchTitle}, ${entry.companyName}` }))}
        />
      )}

      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="button" className="btn" onClick={() => setDraft(null)} disabled={busy}>
          Back to notes
        </button>
        <button type="submit" className="btn btn-primary" disabled={busy || !summary.trim()}>
          {busy ? 'Saving…' : `Save ${noun}`}
        </button>
      </div>
    </form>
  );
}
