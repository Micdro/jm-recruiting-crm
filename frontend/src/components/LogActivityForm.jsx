import { useId, useState } from 'react';
import { api, errorMessage } from '../api.js';
import { ACTION_TYPE, ACTIVITY_TYPE, LOGGABLE_TYPES, MAX_TOUCHES, OUTREACH_TYPES, options } from '../labels.js';
import { addBusinessDays, formatWeekday, todayIso } from '../format.js';
import { useUndoableChange } from '../undo.js';
import { ErrorNote } from './Bits.jsx';
import { SelectField, TextArea, TextField } from './Fields.jsx';

const ACTION_OPTIONS = options(ACTION_TYPE);

/** What logging this activity will do, in plain words, before it's saved. */
function preview(kind, record, type, today) {
  const outreach = OUTREACH_TYPES.includes(type);
  const firstName = record.name.split(' ')[0];

  if (outreach && record.status === 'DO_NOT_CONTACT') {
    return { blocked: `${firstName} is marked Do not contact.` };
  }

  if (kind === 'contact' && outreach && (record.status === 'NO_RESPONSE' || record.status === 'NOT_INTERESTED')) {
    return { blocked: 'The sequence is over. Restart it to reach out again, or log a reply or note.' };
  }

  if (kind === 'contact' && outreach && (record.status === 'NEW' || record.status === 'IN_SEQUENCE')) {
    const touch = record.touchCount + 1;
    if (touch > MAX_TOUCHES) {
      return { blocked: 'This contact already has 4 touches. Restart the sequence to reach out again.' };
    }
    if (touch === MAX_TOUCHES) {
      return { text: `Touch 4 of 4. The sequence ends and ${firstName} moves to No response.` };
    }
    return { text: `Touch ${touch} of 4. Next touch ${formatWeekday(addBusinessDays(today, 3))}.` };
  }

  if (type === 'REPLY') {
    return {
      text:
        kind === 'contact' && ['NEW', 'IN_SEQUENCE', 'NO_RESPONSE', 'NURTURE'].includes(record.status)
          ? `${firstName} moves to Replied, with a response due today.`
          : 'Puts a response on today’s list.',
    };
  }

  if ((outreach || type === 'MEETING') && record.nextActionDate && record.nextActionDate <= today) {
    return { text: 'Marks the follow-up that was due as done.' };
  }

  return { text: 'Keeps the current next action.' };
}

/**
 * Logs a touch, reply, meeting or note for a contact or candidate.
 * `searches` (candidates only) lets the activity be tied to one search.
 */
export default function LogActivityForm({ kind, record, onLogged, searches = [], compact = false }) {
  const offerUndo = useUndoableChange();
  const groupId = useId();
  const today = todayIso();

  const [type, setType] = useState('LINKEDIN');
  const [notes, setNotes] = useState('');
  const [plan, setPlan] = useState('default');
  const [nextDate, setNextDate] = useState('');
  const [nextType, setNextType] = useState('LINKEDIN');
  const [searchId, setSearchId] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const effect = preview(kind, record, type, today);

  async function submit(event) {
    event.preventDefault();
    setBusy(true);
    setError('');

    const body = {
      type,
      notes: notes.trim() || null,
      nextActionDate: plan === 'date' ? nextDate || null : null,
      nextActionType: plan === 'date' ? nextType : null,
      clearNextAction: plan === 'clear',
      searchId: searchId ? Number(searchId) : null,
    };

    try {
      const updated =
        kind === 'contact'
          ? await api.contacts.logActivity(record.id, body)
          : await api.candidates.logActivity(record.id, body);
      setNotes('');
      setPlan('default');
      setNextDate('');
      // Undo hands back the restored record, so the page refreshes the same way it did after logging.
      offerUndo(kind === 'contact' ? 'contacts' : 'candidates', record.id,
        `${ACTIVITY_TYPE[type]} logged for ${record.name}`, onLogged);
      onLogged(updated);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className={`log-form ${compact ? 'log-form-compact' : ''}`} onSubmit={submit}>
      <fieldset className="segmented">
        <legend className="visually-hidden">Activity type</legend>
        {LOGGABLE_TYPES.map((value) => (
          <label key={value} className={type === value ? 'segment segment-on' : 'segment'}>
            <input
              type="radio"
              name={`${groupId}-type`}
              value={value}
              checked={type === value}
              onChange={() => setType(value)}
            />
            {ACTIVITY_TYPE[value]}
          </label>
        ))}
      </fieldset>

      <TextArea
        label="Notes"
        value={notes}
        onChange={setNotes}
        rows={compact ? 2 : 3}
        placeholder={type === 'NOTE' ? 'What happened or what to remember' : 'Optional'}
        maxLength={2000}
      />

      {kind === 'candidate' && searches.length > 0 && (
        <SelectField
          label="About a search"
          value={searchId}
          onChange={setSearchId}
          placeholder="Not tied to a search"
          options={searches.map((entry) => ({ value: String(entry.searchId), label: `${entry.searchTitle}, ${entry.companyName}` }))}
        />
      )}

      <fieldset className="next-plan">
        <legend>Next action</legend>
        <label className="radio">
          <input type="radio" name={`${groupId}-plan`} checked={plan === 'default'} onChange={() => setPlan('default')} />
          Automatic
        </label>
        <label className="radio">
          <input type="radio" name={`${groupId}-plan`} checked={plan === 'date'} onChange={() => setPlan('date')} />
          Pick a date
        </label>
        <label className="radio">
          <input type="radio" name={`${groupId}-plan`} checked={plan === 'clear'} onChange={() => setPlan('clear')} />
          Nothing scheduled
        </label>
      </fieldset>

      {plan === 'date' && (
        <div className="form-row">
          <TextField label="Next action date" type="date" value={nextDate} onChange={setNextDate} min={today} required />
          <SelectField label="Next action type" value={nextType} onChange={setNextType} options={ACTION_OPTIONS} required />
        </div>
      )}

      {plan === 'default' && !effect.blocked && <p className="log-preview">{effect.text}</p>}
      {effect.blocked && <p className="log-preview log-preview-blocked">{effect.blocked}</p>}

      <ErrorNote>{error}</ErrorNote>

      <div className="form-actions">
        <button type="submit" className="btn btn-primary" disabled={busy || !!effect.blocked}>
          {busy ? 'Saving…' : `Log ${ACTIVITY_TYPE[type].toLowerCase() === 'linkedin' ? 'LinkedIn message' : ACTIVITY_TYPE[type].toLowerCase()}`}
        </button>
      </div>
    </form>
  );
}
