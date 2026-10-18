import { useState } from 'react';
import { Link } from 'react-router';
import { api, errorMessage } from '../api.js';
import { formatDate, formatDay, formatWeekday, plural } from '../format.js';
import { useLoader } from '../hooks.js';
import { formatClock } from '../interviews.js';
import { STAGE } from '../labels.js';
import { dollars } from '../marketing.js';
import { ErrorNote } from './Bits.jsx';

const HIDDEN_KEY = 'jmcrm.brief.hidden';

/**
 * This week's brief, then writes it with AI when it isn't written yet and the AI has its key: the
 * first person to open Today in a week writes it for the team. A failed write still shows the facts.
 */
async function loadBrief() {
  const brief = await api.brief.get();
  if (brief.written || !brief.canWrite) return brief;
  try {
    return await api.brief.write();
  } catch (err) {
    return { ...brief, writeError: errorMessage(err), writing: err?.response?.status === 409 };
  }
}

function hiddenWeek() {
  try {
    return localStorage.getItem(HIDDEN_KEY);
  } catch {
    return null;
  }
}

function rememberHidden(week) {
  try {
    if (week) localStorage.setItem(HIDDEN_KEY, week);
    else localStorage.removeItem(HIDDEN_KEY);
  } catch {
    // Private windows can't keep it; the brief just shows again next time.
  }
}

/** The week's brief on Today: where the firm stands, what needs attention, and the week's focus. */
export default function MondayBrief() {
  const brief = useLoader(loadBrief, [], 'brief');
  const [hidden, setHidden] = useState(hiddenWeek);
  const [details, setDetails] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const data = brief.data;
  if (!data) {
    return brief.loading ? (
      <section className="panel brief" aria-label="Monday brief">
        <p className="muted">Getting this week’s brief ready…</p>
      </section>
    ) : null;
  }

  const written = data.written;
  const week = data.weekOf;
  const title = `Week of ${formatDate(week)}`;

  if (hidden === week) {
    return (
      <section className="panel brief brief-hidden" aria-labelledby="brief-heading">
        <h2 id="brief-heading">Monday brief</h2>
        <span className="muted">{written?.headline ?? title}</span>
        <button
          type="button"
          className="btn btn-sm"
          onClick={() => {
            rememberHidden(null);
            setHidden(null);
          }}
        >
          Show
        </button>
      </section>
    );
  }

  async function rewrite() {
    setBusy(true);
    setError('');
    try {
      brief.setData(await api.brief.write(true));
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="panel brief" aria-labelledby="brief-heading">
      <div className="panel-head">
        <h2 id="brief-heading">
          Monday brief <span className="brief-week">{title}</span>
        </h2>
        <div className="brief-actions">
          {written && data.canWrite && (
            <button type="button" className="btn btn-sm btn-quiet" disabled={busy} onClick={rewrite}>
              {busy ? 'Rewriting…' : 'Rewrite'}
            </button>
          )}
          <button
            type="button"
            className="btn btn-sm btn-quiet"
            onClick={() => {
              rememberHidden(week);
              setHidden(week);
            }}
          >
            Hide this week
          </button>
        </div>
      </div>

      {written ? (
        <>
          {written.headline && <p className="brief-headline">{written.headline}</p>}
          {written.focus.length > 0 && (
            <div className="brief-focus">
              <h3>Focus this week</h3>
              <ol>
                {written.focus.map((line) => (
                  <li key={line}>{line}</li>
                ))}
              </ol>
            </div>
          )}
          <div className="brief-columns">
            {written.lastWeek.length > 0 && (
              <div>
                <h3>Last week</h3>
                <ul>
                  {written.lastWeek.map((line) => (
                    <li key={line}>{line}</li>
                  ))}
                </ul>
              </div>
            )}
            {written.watch.length > 0 && (
              <div>
                <h3>Watch</h3>
                <ul>
                  {written.watch.map((line) => (
                    <li key={line}>{line}</li>
                  ))}
                </ul>
              </div>
            )}
          </div>
          <p className="brief-meta">
            Written by {written.provider} for {dollars(written.costCents)}
            {written.writtenAt ? ` on ${formatDay(written.writtenAt)}` : ''}, from the CRM’s records.
          </p>
        </>
      ) : (
        <NotWritten data={data} onRetry={brief.reload} />
      )}

      <ErrorNote>{error}</ErrorNote>

      {written && (
        <button type="button" className="link-button brief-toggle" aria-expanded={details} onClick={() => setDetails(!details)}>
          {details ? 'Hide the details' : 'Show the details'}
        </button>
      )}
      {(details || !written) && <Details facts={data.facts} />}
    </section>
  );
}

function NotWritten({ data, onRetry }) {
  if (data.writing) {
    return (
      <p className="brief-note">
        The brief is being written. <button type="button" className="link-button" onClick={onRetry}>Check again</button>
      </p>
    );
  }
  if (data.writeError) {
    return (
      <>
        <ErrorNote>{data.writeError}</ErrorNote>
        <button type="button" className="btn btn-sm" onClick={onRetry}>
          Try again
        </button>
      </>
    );
  }
  return (
    <p className="brief-note">
      The written brief needs {data.provider === 'OpenAI' ? 'an' : 'a'} {data.provider ?? 'AI'} API key.{' '}
      {data.owner ? `${data.owner} can add one` : 'An admin can add one'} in the AI settings on AI Found. Here are the
      week’s facts in the meantime.
    </p>
  );
}

/** The facts behind the brief, with links to act on them. */
function Details({ facts }) {
  const { outreach, posts } = facts;
  const due = facts.due.reduce((sum, person) => sum + person.contacts + person.candidates + person.companies, 0);

  return (
    <div className="brief-details">
      <ul className="brief-stats">
        <li>
          <strong>{outreach.touches}</strong> touches last week (target {outreach.target} a person)
        </li>
        <li>
          <strong>{outreach.replies}</strong> {outreach.replies === 1 ? 'reply' : 'replies'}, <strong>{outreach.meetings}</strong>{' '}
          {outreach.meetings === 1 ? 'meeting' : 'meetings'}
        </li>
        <li>
          <strong>{due}</strong> follow-ups due now
        </li>
        <li>
          <strong>{facts.debriefsOwed}</strong> {facts.debriefsOwed === 1 ? 'debrief' : 'debriefs'} owed
        </li>
        <li>
          <strong>{posts.plannedThisWeek}</strong> {posts.plannedThisWeek === 1 ? 'post' : 'posts'} planned this week
        </li>
      </ul>

      <FactList title="Waiting too long" items={facts.stuck} empty="Nobody has been at one stage too long.">
        {(stuck) => (
          <>
            <Link to={`/candidates/${stuck.candidateId}`}>{stuck.candidate}</Link>, {STAGE[stuck.stage]} for{' '}
            {plural(stuck.days, 'day')}, <Link to={`/searches/${stuck.searchId}`}>{stuck.search}</Link> at {stuck.company}
            <span className="muted"> ({stuck.owner})</span>
          </>
        )}
      </FactList>

      <FactList title="Searches that need attention" items={facts.atRisk} empty="Every active search is moving.">
        {(risk) => (
          <>
            <Link to={`/searches/${risk.searchId}`}>{risk.search}</Link> at {risk.company}: {risk.reason}
            <span className="muted"> ({risk.owner})</span>
          </>
        )}
      </FactList>

      <FactList title="Interviews this week" items={facts.interviews} empty="No interviews booked this week.">
        {(interview) => (
          <>
            {formatWeekday(interview.on)}
            {interview.at ? ` at ${formatClock(interview.at)}` : ''}:{' '}
            <Link to={`/candidates/${interview.candidateId}`}>{interview.candidate}</Link>
            {interview.label ? `, ${interview.label}` : ''}, <Link to={`/searches/${interview.searchId}`}>{interview.search}</Link>{' '}
            at {interview.company}
          </>
        )}
      </FactList>

      <FactList title="Moved forward last week" items={facts.moves} empty="Nobody reached the shortlist or further.">
        {(move) => (
          <>
            <Link to={`/candidates/${move.candidateId}`}>{move.candidate}</Link> to {STAGE[move.stage]},{' '}
            <Link to={`/searches/${move.searchId}`}>{move.search}</Link> at {move.company}
          </>
        )}
      </FactList>
    </div>
  );
}

function FactList({ title, items, empty, children }) {
  return (
    <div className="brief-fact">
      <h3>{title}</h3>
      {items.length === 0 ? (
        <p className="muted">{empty}</p>
      ) : (
        <ul>
          {items.map((item, index) => (
            <li key={index}>{children(item)}</li>
          ))}
        </ul>
      )}
    </div>
  );
}
