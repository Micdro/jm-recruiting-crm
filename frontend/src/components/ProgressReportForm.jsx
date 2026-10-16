import { useMemo, useState } from 'react';
import { api, errorMessage } from '../api.js';
import { parseDate, todayIso } from '../format.js';
import { useLoader } from '../hooks.js';
import {
  funnel,
  lastReportDay,
  namer,
  reportNote,
  sinceSentence,
  suggestedAsks,
  suggestedNextSteps,
  withClient,
} from '../progressReport.js';
import { ErrorNote } from './Bits.jsx';
import { CheckboxField, TextArea, TextField } from './Fields.jsx';

/**
 * Prepares a progress report on a search for the client: the numbers and who is with them come
 * from the pipeline; next steps and the asks start as a draft to edit. Downloads a PDF and notes
 * it in the search history, so the next report covers what changed since.
 */
export default function ProgressReportForm({ search, entries, history, onDone, onCancel }) {
  const firm = useLoader(() => api.firm.invoiceSettings(), [], 'firm:invoice');
  const today = todayIso();
  const list = useMemo(() => entries ?? [], [entries]);
  const [since, setSince] = useState(() => lastReportDay(history) ?? search.openedDate ?? today);
  const [preparedFor, setPreparedFor] = useState(search.hiringManagerName ?? '');
  const [useReferences, setUseReferences] = useState(false);
  const who = useMemo(() => namer(useReferences), [useReferences]);
  const [nextSteps, setNextSteps] = useState(() => suggestedNextSteps(list));
  const [asks, setAsks] = useState(() => suggestedAsks(list, today, who));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const stages = funnel(list, history);
  const recent = sinceSentence(history, since || today);
  const atClient = withClient(list, today, who);

  function switchNames(checked) {
    // The draft asks name people too, so they follow the choice unless they were edited.
    if (asks === suggestedAsks(list, today, who)) setAsks(suggestedAsks(list, today, namer(checked)));
    setUseReferences(checked);
  }

  async function download(event) {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      const { downloadProgress } = await import('../progressReportPdf.js');
      await downloadProgress({
        firm: firm.data ?? { firmName: 'Jane Michael LLC' },
        report: {
          date: today,
          searchTitle: search.title,
          companyName: search.companyName,
          preparedFor: preparedFor.trim(),
          openedDate: search.openedDate,
          daysOpen: search.openedDate ? Math.round((parseDate(today) - parseDate(search.openedDate)) / 86_400_000) : null,
          funnel: stages,
          since: recent,
          withClient: atClient,
          nextSteps,
          asks,
        },
      });
      await api.activities.note({ searchId: search.id, type: 'NOTE', summary: reportNote(preparedFor.trim()) });
      onDone();
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  return (
    <form className="form progress-form" onSubmit={download}>
      <div className="progress-preview" aria-label="What the report shows">
        <dl className="progress-funnel">
          {stages.map((row) => (
            <div key={row.stage}>
              <dt>{row.label}</dt>
              <dd>{row.count}</dd>
            </div>
          ))}
        </dl>
        <div className="progress-text">
          <p>{recent}</p>
          {atClient.length === 0 ? (
            <p className="muted">No one has been submitted to them yet.</p>
          ) : (
            <ul className="plain-list progress-with-client">
              {atClient.map((person) => (
                <li key={person.id}>
                  <strong>{person.who}</strong>
                  {person.title && `, ${person.title}`}
                  <span className="muted">{person.status}</span>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>

      <div className="form-row">
        <TextField label="Prepared for" value={preparedFor} onChange={setPreparedFor} maxLength={200} placeholder="The hiring manager" />
        <TextField
          label="Recent progress since"
          type="date"
          value={since}
          onChange={setSince}
          max={today}
          hint="The last report, or the day the search opened."
        />
      </div>
      <CheckboxField
        label="Use references instead of names"
        checked={useReferences}
        onChange={switchNames}
        hint="For candidates sent as blind profiles, such as JM-GJDH17."
      />
      <TextArea
        label="What we need from you"
        rows={3}
        value={asks}
        onChange={setAsks}
        maxLength={2000}
        hint="One per line, with a date where you can. Shown in bold."
      />
      <TextArea label="Next steps" rows={3} value={nextSteps} onChange={setNextSteps} maxLength={2000} hint="One per line." />

      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="button" className="btn" onClick={onCancel}>
          Cancel
        </button>
        <button type="submit" className="btn btn-primary" disabled={busy}>
          {busy ? 'Making the PDF…' : 'Download PDF'}
        </button>
      </div>
    </form>
  );
}
