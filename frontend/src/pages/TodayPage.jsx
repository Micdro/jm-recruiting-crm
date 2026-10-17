import { useState } from 'react';
import { Link } from 'react-router';
import { affirmationFor } from '../affirmations.js';
import { api, errorMessage } from '../api.js';
import { DueTag, Empty, ErrorNote, Loading, Pill, TouchPips } from '../components/Bits.jsx';
import CheckInForm from '../components/CheckInForm.jsx';
import { DebriefButton, DebriefForm } from '../components/Interviews.jsx';
import LaterMenu from '../components/LaterMenu.jsx';
import LogActivityForm from '../components/LogActivityForm.jsx';
import MessageComposer from '../components/MessageComposer.jsx';
import Modal from '../components/Modal.jsx';
import PostToLinkedIn from '../components/PostToLinkedIn.jsx';
import TallyMeter from '../components/TallyMeter.jsx';
import Timeline from '../components/Timeline.jsx';
import {
  ACTION_TYPE,
  ACTIVITY_TYPE,
  BOARD_STAGES,
  CANDIDATE_STATUS,
  COMPANY_STATUS,
  HIRING_NEED,
  MAX_TOUCHES,
  OUTREACH_STATUS,
  POST_STATUS,
  PRIORITY,
  SEARCH_STATUS,
  SHORTLIST_STAGES,
} from '../labels.js';
import { formatDate, formatDay, formatLongDay, formatWeekday, money, plural } from '../format.js';
import { useAuth, useLoader, useTitle, useToast } from '../hooks.js';
import { interviewWhen } from '../interviews.js';
import { postedAs, useLinkedIn } from '../marketing.js';
import { useUndoableChange } from '../undo.js';

const QUICK_TYPES = ['LINKEDIN', 'CALL'];

export default function TodayPage() {
  useTitle('Today');
  const [scope, setScope] = useState('mine');
  const dashboard = useLoader(() => api.dashboard(scope), [scope], `dashboard:${scope}`);
  const [logging, setLogging] = useState(null);
  const [writing, setWriting] = useState(null);
  const [debriefing, setDebriefing] = useState(null);
  const [checkingIn, setCheckingIn] = useState(null);
  const offerUndo = useUndoableChange();
  const toast = useToast();

  const data = dashboard.data;

  return (
    <div className="today">
      <div className="page-header">
        <div className="page-heading">
          <h1>Today</h1>
          {data && <div className="page-sub">{formatLongDay(data.today)}</div>}
          {data && (
            <p className="affirmation" data-testid="affirmation">
              {affirmationFor(data.today)}
            </p>
          )}
        </div>
        <div className="page-actions">
          <fieldset className="segmented segmented-small">
            <legend className="visually-hidden">Whose queue</legend>
            {[
              ['mine', 'My queue'],
              ['team', 'Team'],
            ].map(([value, label]) => (
              <label key={value} className={scope === value ? 'segment segment-on' : 'segment'}>
                <input type="radio" name="scope" value={value} checked={scope === value} onChange={() => setScope(value)} />
                {label}
              </label>
            ))}
          </fieldset>
        </div>
      </div>

      {!data && dashboard.loading && <Loading label="Loading your day" />}
      {dashboard.error && <ErrorNote>{errorMessage(dashboard.error)}</ErrorNote>}

      {data && (
        <>
          <TouchSummary data={data} />

          <div className="today-grid">
            <div className="today-main">
              <LinkedInReminder />
              <Interviews data={data} scope={scope} onDebrief={(entry, side) => setDebriefing({ entry, side })} />
              <CheckIns data={data} scope={scope} onCheckIn={setCheckingIn} />
              <PostsToday scope={scope} today={data.today} />
              <ContactQueue
                data={data}
                scope={scope}
                onChanged={dashboard.reload}
                onLog={(contact) => setLogging({ kind: 'contact', record: contact })}
                onWrite={setWriting}
              />
              <CompanyFollowUps data={data} onChanged={dashboard.reload} />
              <HiringSignals />
              <CandidateQueue
                data={data}
                onChanged={dashboard.reload}
                onLog={(candidate) => setLogging({ kind: 'candidate', record: candidate })}
              />
            </div>

            <aside className="today-side">
              <NewProspects />
              <OpenSearches searches={data.openSearches} />
              <Money placements={data.placements} />
              <section className="panel">
                <div className="panel-head">
                  <h2>Recent activity</h2>
                </div>
                <Timeline activities={data.recentActivity} show={['contact', 'candidate', 'search', 'company']} />
              </section>
            </aside>
          </div>
        </>
      )}

      {writing && (
        <Modal title={`Message for ${writing.name}`} onClose={() => setWriting(null)} wide>
          <MessageComposer
            contact={writing}
            onLogged={(updated, type) => {
              offerUndo('contacts', writing.id, touchLoggedMessage(writing, updated, type), dashboard.reload);
              setWriting(null);
              dashboard.reload();
            }}
          />
        </Modal>
      )}

      {checkingIn && (
        <Modal title={`Check in on ${checkingIn.candidateName}`} onClose={() => setCheckingIn(null)}>
          <CheckInForm
            placement={checkingIn}
            onCancel={() => setCheckingIn(null)}
            onSaved={() => {
              setCheckingIn(null);
              toast('Check-in saved');
              dashboard.reload();
            }}
          />
        </Modal>
      )}

      {debriefing && (
        <Modal
          title={debriefing.side === 'CANDIDATE' ? `Debrief ${debriefing.entry.candidateName}` : `Debrief ${debriefing.entry.companyName}`}
          onClose={() => setDebriefing(null)}
        >
          <DebriefForm
            entry={debriefing.entry}
            with={debriefing.side}
            today={data.today}
            onCancel={() => setDebriefing(null)}
            onSaved={() => {
              setDebriefing(null);
              toast('Debrief saved');
              dashboard.reload();
            }}
          />
        </Modal>
      )}

      {logging && (
        <Modal title={`Log activity for ${logging.record.name}`} onClose={() => setLogging(null)}>
          <LogActivityForm
            kind={logging.kind}
            record={logging.record}
            onLogged={() => {
              setLogging(null);
              dashboard.reload();
            }}
          />
        </Modal>
      )}
    </div>
  );
}

function TouchSummary({ data }) {
  const { myTouchesToday: count, dailyTouchTarget: target, dailyTouchStretch: stretch } = data;
  const remaining = Math.max(0, target - count);

  let sentence;
  if (count === 0) sentence = `No touches yet today. The target is ${target}.`;
  else if (remaining > 0) sentence = `${plural(count, 'touch', 'touches')} today. ${remaining} more to reach ${target}.`;
  else if (count < stretch) sentence = `${count} touches today. Target met; ${stretch - count} more to the stretch goal of ${stretch}.`;
  else sentence = `${count} touches today. Stretch goal reached.`;

  return (
    <section className="touch-summary" aria-label="Touches today">
      <TallyMeter count={count} target={target} stretch={stretch} />
      <div className="touch-summary-text">
        <p className="touch-sentence">{sentence}</p>
        <p className="touch-week">{plural(data.myTouchesThisWeek, 'touch', 'touches')} this week</p>
      </div>
      {data.teamTouches.length > 1 && (
        <ul className="team-touches">
          {data.teamTouches.map((member) => (
            <li key={member.userId}>
              <span className="team-name">{member.name}</span>
              <TallyMeter count={member.today} target={target} stretch={stretch} compact />
              <span className="team-count">{member.today}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

/**
 * Interviews later today, and debriefs due once an interview is over: the candidate and the client,
 * each with their own button. Debriefs come first, oldest first.
 */
function Interviews({ data, scope, onDebrief }) {
  const entries = data.interviews ?? [];
  if (entries.length === 0) return null;
  const due = entries.filter((entry) => entry.debriefDue).length;

  return (
    <section className="panel" aria-labelledby="queue-interviews">
      <div className="panel-head">
        <h2 id="queue-interviews">
          Interviews <span className="count">{entries.length}</span>
        </h2>
        {due > 0 && <span className="panel-meta">{plural(due, 'debrief', 'debriefs')} to do</span>}
      </div>
      <ul className="queue">
        {entries.map((entry) => (
          <li key={entry.id} className="queue-row" data-testid="queue-interview">
            <div className="queue-who">
              <Link to={`/candidates/${entry.candidateId}`} className="queue-name">
                {entry.candidateName}
              </Link>
              <span className="queue-org">
                <Link to={`/searches/${entry.searchId}`}>{entry.searchTitle}</Link>, {entry.companyName}
              </span>
            </div>
            <div className="queue-state">
              <span className={entry.debriefDue ? 'interview-when interview-over' : 'interview-when'}>
                {entry.debriefDue ? 'Interviewed ' : 'Interview '}
                {interviewWhen(entry, data.today)}
              </span>
              {entry.interviewLabel && <span className="queue-type">{entry.interviewLabel}</span>}
              {scope === 'team' && entry.ownerName && <span className="queue-owner">{entry.ownerName}</span>}
            </div>
            <div className="queue-actions">
              {entry.debriefDue ? (
                <>
                  <DebriefButton entry={entry} side="CANDIDATE" done={entry.candidateDebriefed} onDebrief={onDebrief} />
                  <DebriefButton entry={entry} side="CLIENT" done={entry.clientDebriefed} onDebrief={onDebrief} />
                </>
              ) : (
                <Link to={`/searches/${entry.searchId}`} className="btn btn-sm">
                  Open search
                </Link>
              )}
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}

/**
 * Placed candidates due a guarantee check-in, 30 and 60 days after they start: a call with them
 * and with the client while there's still time to fix a problem.
 */
function CheckIns({ data, scope, onCheckIn }) {
  const placements = data.checkIns ?? [];
  if (placements.length === 0) return null;

  return (
    <section className="panel" aria-labelledby="queue-check-ins">
      <div className="panel-head">
        <h2 id="queue-check-ins">
          Guarantee check-ins <span className="count">{placements.length}</span>
        </h2>
        <Link to="/placements" className="panel-link">
          All placements
        </Link>
      </div>
      <ul className="queue">
        {placements.map((placement) => (
          <li key={placement.id} className="queue-row" data-testid="queue-check-in">
            <div className="queue-who">
              <Link to={`/candidates/${placement.candidateId}`} className="queue-name">
                {placement.candidateName}
              </Link>
              <span className="queue-org">
                <Link to={`/searches/${placement.searchId}`}>{placement.searchTitle}</Link>, {placement.companyName}
              </span>
            </div>
            <div className="queue-state">
              <span className="check-in-due">{placement.checkInDays}-day check-in</span>
              <span className="queue-type">
                Started {formatDate(placement.startDate)}
                {placement.guaranteeEndDate ? `, guarantee ends ${formatDate(placement.guaranteeEndDate)}` : ''}
              </span>
              {scope === 'team' && placement.ownerName && <span className="queue-owner">{placement.ownerName}</span>}
            </div>
            <div className="queue-actions">
              <button
                type="button"
                className="btn btn-sm"
                onClick={() => onCheckIn(placement)}
                aria-label={`Check in on ${placement.candidateName}`}
              >
                Check in
              </button>
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}

/** What logging a touch did, for the confirmation. */
function touchLoggedMessage(contact, updated, type) {
  if (type !== 'REPLY' && updated.status === 'NO_RESPONSE' && contact.status !== 'NO_RESPONSE') {
    const back = updated.nextActionDate ? ` Back on Today ${formatWeekday(updated.nextActionDate)} to restart.` : '';
    return `Touch 4 of 4 logged for ${contact.name}. Sequence complete.${back}`;
  }
  const next = updated.nextActionDate
    ? ` Next: ${ACTION_TYPE[updated.nextActionType] ?? 'Follow-up'} on ${formatWeekday(updated.nextActionDate)}.`
    : '';
  const what = type === 'REPLY' ? `Reply logged for ${contact.name}.` : `${ACTIVITY_TYPE[type]} logged for ${contact.name}.`;
  return what + next;
}

function ContactQueue({ data, scope, onChanged, onLog, onWrite }) {
  const toast = useToast();
  const offerUndo = useUndoableChange();
  const [busyId, setBusyId] = useState(null);
  const [error, setError] = useState('');
  const [fillCount, setFillCount] = useState(10);

  async function quickLog(contact, type) {
    setBusyId(contact.id);
    setError('');
    try {
      const updated = await api.contacts.logActivity(contact.id, { type });
      offerUndo('contacts', contact.id, touchLoggedMessage(contact, updated, type), onChanged);
      onChanged();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusyId(null);
    }
  }

  /** A contact back after a sequence with no reply: a fresh four touches, the first due today. */
  async function restart(contact) {
    setBusyId(contact.id);
    setError('');
    try {
      await api.contacts.restartSequence(contact.id);
      offerUndo('contacts', contact.id, `Restarted ${contact.name}'s sequence. Touch 1 is due today.`, onChanged);
      onChanged();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusyId(null);
    }
  }

  async function later(contact, until) {
    setBusyId(contact.id);
    setError('');
    try {
      const updated = await api.contacts.snooze(contact.id, until);
      offerUndo('contacts', contact.id, `Moved ${contact.name} to ${formatWeekday(updated.nextActionDate)}.`, onChanged);
      onChanged();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusyId(null);
    }
  }

  async function fillQueue() {
    setError('');
    try {
      const result = await api.contacts.fillQueue(Number(fillCount));
      toast(
        result.scheduled === 0
          ? 'No new contacts to add.'
          : `Added ${plural(result.scheduled, 'contact')} to today’s queue.`,
      );
      onChanged();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  const contacts = data.contactsDue;

  return (
    <section className="panel" aria-labelledby="queue-contacts">
      <div className="panel-head">
        <h2 id="queue-contacts">
          Contacts to touch <span className="count">{data.contactsDueTotal}</span>
        </h2>
        {scope === 'mine' && data.unscheduledNewContacts > 0 && (
          <form
            className="fill-queue"
            onSubmit={(event) => {
              event.preventDefault();
              fillQueue();
            }}
          >
            <label htmlFor="fill-count">Pull in</label>
            <input
              id="fill-count"
              type="number"
              min="1"
              max="100"
              value={fillCount}
              onChange={(event) => setFillCount(event.target.value)}
            />
            <span>of {data.unscheduledNewContacts} new</span>
            <button type="submit" className="btn btn-sm">
              Add to today
            </button>
          </form>
        )}
      </div>

      <ErrorNote>{error}</ErrorNote>

      {contacts.length === 0 ? (
        <Empty title="Nothing due">
          {data.unscheduledNewContacts > 0 && scope === 'mine'
            ? 'Pull new contacts into today to start their sequences.'
            : 'Import a list or add contacts to start outreach.'}
        </Empty>
      ) : (
        <ul className="queue">
          {contacts.map((contact) => {
            const cold = contact.status === 'NEW' || contact.status === 'IN_SEQUENCE';
            const capped =
              (cold && contact.touchCount >= MAX_TOUCHES) || contact.status === 'NO_RESPONSE' || contact.status === 'NOT_INTERESTED';
            // Back 90 days after a sequence with no reply, ready to restart.
            const revisit = contact.status === 'NO_RESPONSE';
            return (
              <li key={contact.id} className="queue-row" data-testid="queue-contact">
                <div className="queue-who">
                  <Link to={`/contacts/${contact.id}`} className="queue-name">
                    {contact.name}
                  </Link>
                  <span className="queue-org">
                    {contact.title ? `${contact.title}, ` : ''}
                    <Link to={`/companies/${contact.companyId}`}>{contact.companyName}</Link>
                  </span>
                </div>
                <div className="queue-state">
                  {cold ? (
                    <span className="queue-touch">
                      <TouchPips count={contact.touchCount} />
                      <span>Touch {Math.min(contact.touchCount + 1, MAX_TOUCHES)}</span>
                    </span>
                  ) : (
                    <Pill value={contact.status} labels={OUTREACH_STATUS} />
                  )}
                  <span className="queue-due">
                    <DueTag date={contact.nextActionDate} today={data.today} />
                    {revisit ? (
                      <span className="queue-type">Revisit</span>
                    ) : (
                      contact.nextActionType && <span className="queue-type">{ACTION_TYPE[contact.nextActionType]}</span>
                    )}
                  </span>
                  {scope === 'team' && contact.ownerName && <span className="queue-owner">{contact.ownerName}</span>}
                </div>
                <div className="queue-actions">
                  {revisit ? (
                    <button
                      type="button"
                      className="btn btn-sm btn-suggested"
                      disabled={busyId === contact.id}
                      onClick={() => restart(contact)}
                      aria-label={`Restart the sequence for ${contact.name}`}
                    >
                      Restart
                    </button>
                  ) : (
                    <>
                      <button
                        type="button"
                        className="btn btn-sm"
                        disabled={capped || contact.status === 'DO_NOT_CONTACT'}
                        onClick={() => onWrite(contact)}
                        aria-label={`Write a message to ${contact.name}`}
                      >
                        Write
                      </button>
                      {QUICK_TYPES.map((type) => (
                        <button
                          key={type}
                          type="button"
                          className={`btn btn-sm ${contact.nextActionType === type ? 'btn-suggested' : ''}`}
                          disabled={busyId === contact.id || capped}
                          onClick={() => quickLog(contact, type)}
                          aria-label={`Log ${ACTIVITY_TYPE[type]} for ${contact.name}`}
                        >
                          {ACTIVITY_TYPE[type]}
                        </button>
                      ))}
                    </>
                  )}
                  <button
                    type="button"
                    className="btn btn-sm"
                    disabled={busyId === contact.id}
                    onClick={() => quickLog(contact, 'REPLY')}
                    aria-label={`Log reply from ${contact.name}`}
                  >
                    Replied
                  </button>
                  <LaterMenu
                    name={contact.name}
                    today={data.today}
                    disabled={busyId === contact.id || !contact.nextActionDate}
                    onPick={(until) => later(contact, until)}
                  />
                  <button
                    type="button"
                    className="btn btn-sm btn-quiet"
                    onClick={() => onLog(contact)}
                    aria-label={`More options for ${contact.name}`}
                  >
                    More
                  </button>
                </div>
              </li>
            );
          })}
        </ul>
      )}
      {data.contactsDueTotal > contacts.length && (
        <p className="panel-foot">
          Showing {contacts.length} of {data.contactsDueTotal}. <Link to="/contacts?due=true">See all due contacts</Link>
        </p>
      )}
    </section>
  );
}

/** Later on a company or candidate row: moves its follow-up and offers Undo. kind is the api name. */
function useLater(kind, dateOf, onChanged) {
  const offerUndo = useUndoableChange();
  const [busyId, setBusyId] = useState(null);
  const [error, setError] = useState('');

  async function later(record, until) {
    setBusyId(record.id);
    setError('');
    try {
      const updated = await api[kind].snooze(record.id, until);
      offerUndo(kind, record.id, `Moved ${record.name} to ${formatWeekday(dateOf(updated))}.`, onChanged);
      onChanged();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusyId(null);
    }
  }

  return { busyId, error, later };
}

/** Client and prospect companies whose follow-up date has come (ClickUp's Follow-Up Date). */
function CompanyFollowUps({ data, onChanged }) {
  const { busyId, error, later } = useLater('companies', (company) => company.followUpDate, onChanged);
  const companies = data.companiesDue ?? [];
  if (companies.length === 0) return null;

  return (
    <section className="panel" aria-labelledby="queue-companies">
      <div className="panel-head">
        <h2 id="queue-companies">
          Client follow-ups <span className="count">{data.companiesDueTotal}</span>
        </h2>
        <Link to="/companies?due=true" className="panel-link">
          All due
        </Link>
      </div>
      <ErrorNote>{error}</ErrorNote>
      <ul className="queue">
        {companies.map((company) => (
          <li key={company.id} className="queue-row">
            <div className="queue-who">
              <Link to={`/companies/${company.id}`} className="queue-name">
                {company.name}
              </Link>
              <span className="queue-org">{HIRING_NEED[company.hiringNeed] ?? company.focus}</span>
            </div>
            <div className="queue-state">
              <Pill value={company.status} labels={COMPANY_STATUS} />
              <span className="queue-due">
                <DueTag date={company.followUpDate} today={data.today} />
                {company.priority && company.priority !== 'NORMAL' && (
                  <span className="queue-type">{PRIORITY[company.priority]} priority</span>
                )}
              </span>
            </div>
            <div className="queue-actions">
              <Link to={`/companies/${company.id}`} className="btn btn-sm">
                Open
              </Link>
              <LaterMenu
                name={company.name}
                today={data.today}
                disabled={busyId === company.id}
                onPick={(until) => later(company, until)}
              />
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}

/** Companies that posted forward deployed or AI platform roles since the team last looked. */
/** A nudge to connect LinkedIn again in the week before the connection runs out, so posting doesn't stop. */
function LinkedInReminder() {
  const linkedin = useLinkedIn();
  const me = linkedin.data?.me;
  if (!linkedin.data?.appAdded || !me || !(me.expiring || me.expired)) return null;
  return (
    <div className="notice notice-warn" role="status">
      <p>
        {me.expired
          ? 'Your LinkedIn connection has run out, so Post to LinkedIn won’t work until you connect again.'
          : `Your LinkedIn connection runs out on ${formatDay(me.expiresAt)}. Connect again to keep posting from the CRM.`}
      </p>
      <Link to="/marketing?view=plan" className="btn btn-sm">
        Connect again
      </Link>
    </div>
  );
}

/** Today's LinkedIn posts, and ones from the past week that didn't go up, with a button to post each. */
function PostsToday({ scope, today }) {
  const { user } = useAuth();
  const upcoming = useLoader(() => api.marketing.upcoming(), [], 'marketing:upcoming');
  const due = (upcoming.data ?? []).filter(
    (post) => post.plannedFor <= today && (scope === 'team' || !post.ownerId || post.ownerId === user?.id),
  );
  if (due.length === 0) return null;

  return (
    <section className="panel" aria-labelledby="queue-posts">
      <div className="panel-head">
        <h2 id="queue-posts">
          LinkedIn posts <span className="count">{due.length}</span>
        </h2>
        <Link to="/marketing?view=upcoming" className="panel-link">
          All upcoming
        </Link>
      </div>
      <ul className="queue">
        {due.map((post) => (
          <li key={post.id} className="queue-row today-post">
            <div className="queue-who">
              <Link to={`/marketing?post=${post.id}`} className="queue-name">
                {post.title}
              </Link>
              <span className="queue-org">
                {postedAs(post)}
                {post.plannedFor < today ? `, missed ${formatWeekday(post.plannedFor)}` : ''}
              </span>
            </div>
            <div className="queue-state">
              <Pill value={post.status} labels={POST_STATUS} />
              {post.picture?.ready && <img className="today-post-picture" src={post.picture.url} alt="" loading="lazy" />}
            </div>
            <div className="queue-actions">
              {post.body ? (
                <PostToLinkedIn post={post} onPosted={upcoming.reload} size="sm" />
              ) : (
                <Link to={`/marketing?post=${post.id}`} className="btn btn-sm">
                  Write it
                </Link>
              )}
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}

function HiringSignals() {
  const summary = useLoader(() => api.signals.summary(), [], 'signals:summary');
  const data = summary.data;
  if (!data || data.companies === 0) return null;

  return (
    <section className="panel" aria-labelledby="queue-signals">
      <div className="panel-head">
        <h2 id="queue-signals">
          Hiring signals <span className="count">{data.companies}</span>
        </h2>
        <Link to="/signals" className="panel-link">
          All signals
        </Link>
      </div>
      <ul className="queue">
        {data.top.map((item) => (
          <li key={item.companyId} className="queue-row">
            <div className="queue-who">
              <Link to={`/companies/${item.companyId}`} className="queue-name">
                {item.name}
              </Link>
              <span className="queue-org">
                {item.firstRole}
                {item.unreviewedRoles > 1 ? ` and ${plural(item.unreviewedRoles - 1, 'more role')}` : ''}
              </span>
            </div>
            <div className="queue-state">
              <Pill value={item.status} labels={COMPANY_STATUS} />
              <span className="queue-type">New since last review</span>
            </div>
            <div className="queue-actions">
              <Link to="/signals" className="btn btn-sm">
                Review
              </Link>
            </div>
          </li>
        ))}
      </ul>
      {data.companies > data.top.length && (
        <p className="panel-foot">
          Showing {data.top.length} of {data.companies}. <Link to="/signals">See them all</Link>
        </p>
      )}
    </section>
  );
}

function CandidateQueue({ data, onChanged, onLog }) {
  const { busyId, error, later } = useLater('candidates', (candidate) => candidate.nextActionDate, onChanged);
  const candidates = data.candidatesDue;
  if (candidates.length === 0) return null;

  return (
    <section className="panel" aria-labelledby="queue-candidates">
      <div className="panel-head">
        <h2 id="queue-candidates">
          Candidates to follow up <span className="count">{data.candidatesDueTotal}</span>
        </h2>
      </div>
      <ErrorNote>{error}</ErrorNote>
      <ul className="queue">
        {candidates.map((candidate) => (
          <li key={candidate.id} className="queue-row">
            <div className="queue-who">
              <Link to={`/candidates/${candidate.id}`} className="queue-name">
                {candidate.name}
              </Link>
              <span className="queue-org">
                {[candidate.currentTitle, candidate.currentCompany].filter(Boolean).join(', ')}
              </span>
            </div>
            <div className="queue-state">
              <Pill value={candidate.status} labels={CANDIDATE_STATUS} />
              <span className="queue-due">
                <DueTag date={candidate.nextActionDate} today={data.today} />
                {candidate.nextActionType && <span className="queue-type">{ACTION_TYPE[candidate.nextActionType]}</span>}
              </span>
            </div>
            <div className="queue-actions">
              <button type="button" className="btn btn-sm" onClick={() => onLog(candidate)}>
                Log follow-up
              </button>
              <LaterMenu
                name={candidate.name}
                today={data.today}
                disabled={busyId === candidate.id}
                onPick={(until) => later(candidate, until)}
              />
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}

function OpenSearches({ searches }) {
  return (
    <section className="panel">
      <div className="panel-head">
        <h2>Open searches</h2>
        <Link to="/searches" className="panel-link">
          All searches
        </Link>
      </div>
      {searches.length === 0 ? (
        <Empty title="No open searches">Open one from a client’s company page.</Empty>
      ) : (
        <ul className="search-list">
          {searches.map((search) => {
            const inProcess = BOARD_STAGES.filter((stage) => stage !== 'PLACED').reduce(
              (sum, stage) => sum + (search.stageCounts[stage] ?? 0),
              0,
            );
            const onShortlist = SHORTLIST_STAGES.filter((stage) => stage !== 'PLACED').reduce(
              (sum, stage) => sum + (search.stageCounts[stage] ?? 0),
              0,
            );
            return (
              <li key={search.id}>
                <div className="search-list-top">
                  <Link to={`/searches/${search.id}`}>{search.title}</Link>
                  <Pill value={search.status} labels={SEARCH_STATUS} />
                </div>
                <p className="search-list-meta">
                  {search.companyName}. {plural(inProcess, 'candidate')} in process, {onShortlist} on the shortlist.
                </p>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}

function Money({ placements }) {
  return (
    <section className="panel">
      <div className="panel-head">
        <h2>Placements this year</h2>
        <Link to="/placements" className="panel-link">
          All placements
        </Link>
      </div>
      <dl className="money">
        <div>
          <dt>Fees booked</dt>
          <dd>{money(placements.feesThisYear)}</dd>
        </div>
        <div>
          <dt>Collected</dt>
          <dd>{money(placements.collectedThisYear)}</dd>
        </div>
        <div>
          <dt>Invoiced, unpaid</dt>
          <dd>{money(placements.outstanding)}</dd>
        </div>
      </dl>
      <p className="money-count">{plural(placements.placementsThisYear, 'placement')} this year.</p>
      {placements.overdueInvoices?.length > 0 && (
        <>
          <h3 className="panel-subhead">Overdue invoices</h3>
          <ul className="plain-list overdue-list">
            {placements.overdueInvoices.map((placement) => (
              <li key={placement.id}>
                <Link to="/placements">{placement.companyName}</Link>, {money(placement.feeAmount)}
                {placement.invoiceNumber ? ` (invoice ${placement.invoiceNumber})` : ''}, due{' '}
                {formatDate(placement.invoiceDueDate)}
              </li>
            ))}
          </ul>
        </>
      )}
      {placements.guaranteesEndingSoon.length > 0 && (
        <>
          <h3 className="panel-subhead">Guarantees ending in the next 30 days</h3>
          <ul className="plain-list">
            {placements.guaranteesEndingSoon.map((placement) => (
              <li key={placement.id}>
                <Link to={`/candidates/${placement.candidateId}`}>{placement.candidateName}</Link> at {placement.companyName},{' '}
                {formatDate(placement.guaranteeEndDate)}
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );
}

/** Companies the prospect finder found that nobody has reviewed yet. Hidden when there are none. */
function NewProspects() {
  const summary = useLoader(() => api.prospects.summary(), [], 'prospects:summary');
  const data = summary.data;
  if (!data || (data.waiting === 0 && !data.running)) return null;
  return (
    <section className="panel" aria-labelledby="new-prospects">
      <div className="panel-head">
        <h2 id="new-prospects">AI Found</h2>
        <Link to="/prospects" className="panel-link">
          Review
        </Link>
      </div>
      <p className="panel-meta">
        {data.waiting > 0
          ? `${plural(data.waiting, 'new company', 'new companies')} found on the web, waiting for you to accept or dismiss.`
          : 'Searching the web for new prospects now.'}
      </p>
    </section>
  );
}
