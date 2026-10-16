import { useState } from 'react';
import { Link, Navigate, useSearchParams } from 'react-router';
import { api, errorMessage } from '../api.js';
import { Empty, ErrorNote, ExternalLink, Loading, PageHeader, Pill, TouchPips } from '../components/Bits.jsx';
import { ACTION_TYPE, CANDIDATE_STATUS, OUTREACH_STATUS } from '../labels.js';
import { formatWeekday, toIsoDate } from '../format.js';
import { useLoader, useTitle } from '../hooks.js';
import { findPerson } from '../linkedin.js';

/**
 * Opened by the Log to JM CRM bookmark from a LinkedIn conversation: who it's with, who wrote last
 * and what they said. Finds them in the CRM and logs their reply or your message in one click.
 */
export default function LinkedInLogPage() {
  useTitle('Log from LinkedIn');
  const [params] = useSearchParams();
  const capture = {
    name: params.get('name')?.trim() ?? '',
    url: params.get('url')?.trim() ?? '',
    others: params.get('others')?.trim() ?? '',
    from: params.get('from') ?? '',
    text: params.get('text')?.trim() ?? '',
    when: params.get('when')?.trim() ?? '',
  };
  if (!capture.name && !capture.url) return <Navigate to="/linkedin" replace />;
  return <LogConversation capture={capture} />;
}

function LogConversation({ capture }) {
  const contacts = useLoader(() => api.contacts.list(), [], 'contacts');
  const candidates = useLoader(() => api.candidates.list(), [], 'candidates');
  const [chosen, setChosen] = useState(null);

  if (!contacts.data || !candidates.data) {
    const failed = contacts.error || candidates.error;
    return failed ? <ErrorNote>{errorMessage(failed)}</ErrorNote> : <Loading label="Finding them in the CRM" />;
  }

  const found = findPerson(capture, contacts.data, candidates.data);
  const match = found.find((item) => `${item.kind}-${item.record.id}` === chosen) ?? found[0];
  const addLink = `/linkedin?${new URLSearchParams({ name: capture.name, url: capture.url })}`;

  return (
    <>
      <PageHeader title="Log from LinkedIn" sub="Check who it is and what happened, then log it. You can close this tab afterwards." />

      <section className="panel linkedin-capture" aria-labelledby="linkedin-convo">
        <div>
          <h2 id="linkedin-convo">{capture.name || 'Name not found'}</h2>
          <p className="muted">
            {capture.from === 'them' && `Their message was the last one${capture.when ? `, ${capture.when}` : ''}.`}
            {capture.from === 'me' && `Your message was the last one${capture.when ? `, ${capture.when}` : ''}.`}
            {!capture.from && 'Couldn’t tell who sent the last message.'}{' '}
            {capture.url && <ExternalLink href={capture.url}>LinkedIn profile</ExternalLink>}
          </p>
          {capture.text && <blockquote className="linkedin-quote">{capture.text}</blockquote>}
        </div>
      </section>

      {found.length === 0 ? (
        <section className="panel">
          <Empty title="Not in the CRM yet">
            <p>Nobody in the CRM has this LinkedIn profile or name.</p>
            <Link to={addLink} className="btn btn-sm btn-primary">
              Add {capture.name || 'them'}
            </Link>
          </Empty>
        </section>
      ) : (
        <>
          {found.length > 1 && (
            <fieldset className="segmented segmented-small linkedin-pick">
              <legend className="visually-hidden">Who to log it for</legend>
              {found.map((item) => {
                const key = `${item.kind}-${item.record.id}`;
                const on = match === item;
                return (
                  <label key={key} className={on ? 'segment segment-on' : 'segment'}>
                    <input type="radio" name="linkedin-who" checked={on} onChange={() => setChosen(key)} />
                    {item.record.name} ({item.kind}
                    {item.kind === 'contact' && item.record.companyName ? ` at ${item.record.companyName}` : ''})
                  </label>
                );
              })}
            </fieldset>
          )}
          <LogFor key={`${match.kind}-${match.record.id}`} match={match} capture={capture} />
        </>
      )}
    </>
  );
}

/** One person's record, with the reply or message to log. */
function LogFor({ match, capture }) {
  const { kind, record } = match;
  const path = `/${kind === 'contact' ? 'contacts' : 'candidates'}/${record.id}`;
  const history = useLoader(
    () => api.activities.list(kind === 'contact' ? { contactId: record.id } : { candidateId: record.id }),
    [kind, record.id],
  );
  const [notes, setNotes] = useState(capture.from === 'them' && capture.text ? `“${capture.text}”` : '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [done, setDone] = useState(null);

  const today = toIsoDate(new Date());
  const loggedToday = (type) =>
    (history.data ?? []).find((activity) => activity.type === type && toIsoDate(new Date(activity.occurredAt)) === today);

  async function log(type) {
    setBusy(true);
    setError('');
    try {
      const body = { type, notes: notes.trim() || undefined };
      const updated =
        kind === 'contact' ? await api.contacts.logActivity(record.id, body) : await api.candidates.logActivity(record.id, body);
      setDone({ type, updated });
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  if (done) {
    const { type, updated } = done;
    const next = updated.nextActionDate
      ? ` Next: ${ACTION_TYPE[updated.nextActionType] ?? 'Follow-up'} on ${formatWeekday(updated.nextActionDate)}.`
      : '';
    return (
      <section className="panel" aria-live="polite">
        <p className="linkedin-done">
          {type === 'REPLY' ? `Logged ${record.name}’s reply.` : `Logged your LinkedIn message to ${record.name}.`}
          {kind === 'contact' ? ` Now ${OUTREACH_STATUS[updated.status] ?? updated.status}.` : ''}
          {next}
        </p>
        <p className="panel-foot">
          <Link to={path}>Open {record.name}</Link>. You can close this tab.
        </p>
      </section>
    );
  }

  const status = kind === 'contact' ? OUTREACH_STATUS : CANDIDATE_STATUS;
  const replyFirst = capture.from !== 'me';
  const repeat = loggedToday(replyFirst ? 'REPLY' : 'LINKEDIN');

  const replyButton = (
    <button
      key="reply"
      type="button"
      className={`btn ${replyFirst ? 'btn-primary' : ''}`}
      disabled={busy}
      onClick={() => log('REPLY')}
    >
      Log their reply
    </button>
  );
  const messageButton = (
    <button
      key="message"
      type="button"
      className={`btn ${replyFirst ? '' : 'btn-primary'}`}
      disabled={busy}
      onClick={() => log('LINKEDIN')}
    >
      Log my message
    </button>
  );

  return (
    <section className="panel" aria-labelledby="linkedin-log">
      <div className="panel-head">
        <h2 id="linkedin-log">
          <Link to={path}>{record.name}</Link>{' '}
          <span className="panel-meta">
            {kind === 'contact' ? [record.title, record.companyName].filter(Boolean).join(', ') : 'Candidate'}
          </span>
        </h2>
        <span className="panel-actions">
          <Pill value={record.status} labels={status} />
          {kind === 'contact' && (record.status === 'NEW' || record.status === 'IN_SEQUENCE') && (
            <TouchPips count={record.touchCount} />
          )}
        </span>
      </div>

      {repeat && (
        <p className="warn-note">
          You already logged {replyFirst ? 'a reply from' : 'a LinkedIn message to'} {record.name} today. Log it again only
          if this is a new one.
        </p>
      )}

      <div className="field">
        <label htmlFor="linkedin-notes">Note for their history</label>
        <textarea id="linkedin-notes" rows={3} value={notes} onChange={(event) => setNotes(event.target.value)} />
        <p className="field-hint">
          Optional. Your message counts as a touch and moves their sequence on if they’re in one. Their reply puts them on
          Today to answer.
        </p>
      </div>
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions form-actions-start">
        {replyFirst ? [replyButton, messageButton] : [messageButton, replyButton]}
        <Link to={path} className="link-button">
          Something else? Open their page
        </Link>
      </div>
    </section>
  );
}
