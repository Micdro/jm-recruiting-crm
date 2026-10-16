import { useState } from 'react';
import { api, errorMessage } from '../api.js';
import { interviewWhen } from '../interviews.js';
import { ErrorNote } from './Bits.jsx';
import { TextArea, TextField } from './Fields.jsx';

/** Schedules the next interview for a candidate in a search, moves it, or cancels it. */
export function InterviewForm({ entry, onSaved, onCancel }) {
  const [date, setDate] = useState(entry.interviewDate ?? '');
  const [time, setTime] = useState(entry.interviewTime ? entry.interviewTime.slice(0, 5) : '');
  const [label, setLabel] = useState(entry.interviewLabel ?? '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const firstName = entry.candidateName.split(' ')[0];
  const before = ['SOURCED', 'CONTACTED', 'ASSESSING', 'SHORTLISTED', 'SUBMITTED'].includes(entry.stage);

  async function save(event) {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      const updated = await api.pipeline.scheduleInterview(entry.id, {
        date,
        time: time || null,
        label: label.trim() || null,
      });
      onSaved(updated, 'saved');
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  async function cancelInterview() {
    setBusy(true);
    setError('');
    try {
      onSaved(await api.pipeline.cancelInterview(entry.id), 'cancelled');
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  return (
    <form className="form" onSubmit={save}>
      <p className="form-intro">
        After the interview, Today reminds you to debrief {firstName} and {entry.companyName}.
        {before ? ` ${firstName} moves to Interviewing.` : ''}
      </p>
      <div className="form-row">
        <TextField label="Date" type="date" value={date} onChange={setDate} required autoFocus />
        <TextField label="Time" hint="Optional" type="time" value={time} onChange={setTime} />
      </div>
      <TextField label="What it is" hint="Optional" value={label} onChange={setLabel} maxLength={200} placeholder="Round 2 with the CTO" />
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        {entry.interviewDate && (
          <button type="button" className="btn btn-danger form-actions-left" disabled={busy} onClick={cancelInterview}>
            Cancel the interview
          </button>
        )}
        <button type="button" className="btn" onClick={onCancel}>
          Close
        </button>
        <button type="submit" className="btn btn-primary" disabled={busy || !date}>
          {entry.interviewDate ? 'Save' : 'Schedule interview'}
        </button>
      </div>
    </form>
  );
}

/** Notes from the debrief with the candidate or the client after an interview. */
export function DebriefForm({ entry, with: side, today, onSaved, onCancel }) {
  const [notes, setNotes] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const firstName = entry.candidateName.split(' ')[0];
  const when = interviewWhen(entry, today);

  async function save(event) {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      onSaved(await api.pipeline.debrief(entry.id, { with: side, notes: notes.trim() || null }));
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  return (
    <form className="form" onSubmit={save}>
      <p className="form-intro">
        {side === 'CANDIDATE'
          ? `How did the interview ${when} go for ${firstName}? Their interest, any concerns, pay and timing.`
          : `What did ${entry.companyName} think of ${firstName} after the interview ${when}? Feedback, concerns and next steps.`}
      </p>
      <TextArea label="Notes" hint="Optional. They go in the history." value={notes} onChange={setNotes} rows={5} maxLength={4000} autoFocus />
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="button" className="btn" onClick={onCancel}>
          Cancel
        </button>
        <button type="submit" className="btn btn-primary" disabled={busy}>
          Save debrief
        </button>
      </div>
    </form>
  );
}

/** The interview on a pipeline card: when it is, and the debriefs once it's over. */
export function InterviewLine({ entry, today, onEdit, onDebrief }) {
  const bothDone = entry.candidateDebriefed && entry.clientDebriefed;
  return (
    <div className="card-interview">
      <button
        type="button"
        className="link-button card-interview-when"
        onClick={() => onEdit(entry)}
        aria-label={`Change the interview for ${entry.candidateName}`}
      >
        Interview {interviewWhen(entry, today)}
      </button>
      {entry.interviewLabel && <span className="card-interview-label">{entry.interviewLabel}</span>}
      {entry.debriefDue && (
        <div className="debriefs">
          <DebriefButton entry={entry} side="CANDIDATE" done={entry.candidateDebriefed} onDebrief={onDebrief} />
          <DebriefButton entry={entry} side="CLIENT" done={entry.clientDebriefed} onDebrief={onDebrief} />
        </div>
      )}
      {bothDone && <span className="debrief-done">Candidate and client debriefed</span>}
    </div>
  );
}

/** "Debrief candidate" / "Debrief client", or a tick once it's done. */
export function DebriefButton({ entry, side, done, onDebrief }) {
  const who = side === 'CANDIDATE' ? 'candidate' : 'client';
  if (done) return <span className="debrief-done">{side === 'CANDIDATE' ? 'Candidate' : 'Client'} debriefed</span>;
  return (
    <button
      type="button"
      className="btn btn-sm"
      onClick={() => onDebrief(entry, side)}
      aria-label={side === 'CANDIDATE' ? `Debrief ${entry.candidateName}` : `Debrief ${entry.companyName} about ${entry.candidateName}`}
    >
      Debrief {who}
    </button>
  );
}
