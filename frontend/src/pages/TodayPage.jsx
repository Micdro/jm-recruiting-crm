import { useState } from 'react';
import { Link } from 'react-router';
import { api, errorMessage } from '../api.js';
import { DueTag, Empty, ErrorNote, Loading, Pill, TouchPips } from '../components/Bits.jsx';
import LogActivityForm from '../components/LogActivityForm.jsx';
import MessageComposer from '../components/MessageComposer.jsx';
import Modal from '../components/Modal.jsx';
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
  PRIORITY,
  SEARCH_STATUS,
  SHORTLIST_STAGES,
} from '../labels.js';
import { formatDate, formatLongDay, formatWeekday, money, plural } from '../format.js';
import { useLoader, useTitle, useToast } from '../hooks.js';

const QUICK_TYPES = ['EMAIL', 'LINKEDIN', 'CALL'];

export default function TodayPage() {
  useTitle('Today');
  const [scope, setScope] = useState('mine');
  const dashboard = useLoader(() => api.dashboard(scope), [scope], `dashboard:${scope}`);
  const [logging, setLogging] = useState(null);
  const [writing, setWriting] = useState(null);
  const toast = useToast();

  const data = dashboard.data;

  return (
    <div className="today">
      <div className="page-header">
        <div className="page-heading">
          <h1>Today</h1>
          {data && <div className="page-sub">{formatLongDay(data.today)}</div>}
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
              <ContactQueue
                data={data}
                scope={scope}
                onChanged={dashboard.reload}
                onLog={(contact) => setLogging({ kind: 'contact', record: contact })}
                onWrite={setWriting}
              />
              <CompanyFollowUps data={data} />
              <CandidateQueue data={data} onLog={(candidate) => setLogging({ kind: 'candidate', record: candidate })} />
            </div>

            <aside className="today-side">
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
              toast(touchLoggedMessage(writing, updated, type));
              setWriting(null);
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

/** What logging a touch did, for the confirmation. */
function touchLoggedMessage(contact, updated, type) {
  const next = updated.nextActionDate
    ? ` Next: ${ACTION_TYPE[updated.nextActionType] ?? 'Follow-up'} on ${formatWeekday(updated.nextActionDate)}.`
    : '';
  const what =
    type === 'REPLY'
      ? `Reply logged for ${contact.name}.`
      : updated.status === 'NO_RESPONSE' && contact.status !== 'NO_RESPONSE'
        ? `Touch 4 of 4 logged for ${contact.name}. Sequence complete.`
        : `${ACTIVITY_TYPE[type]} logged for ${contact.name}.`;
  return what + next;
}

function ContactQueue({ data, scope, onChanged, onLog, onWrite }) {
  const toast = useToast();
  const [busyId, setBusyId] = useState(null);
  const [error, setError] = useState('');
  const [fillCount, setFillCount] = useState(10);

  async function quickLog(contact, type) {
    setBusyId(contact.id);
    setError('');
    try {
      const updated = await api.contacts.logActivity(contact.id, { type });
      toast(touchLoggedMessage(contact, updated, type));
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
                    {contact.nextActionType && <span className="queue-type">{ACTION_TYPE[contact.nextActionType]}</span>}
                  </span>
                  {scope === 'team' && contact.ownerName && <span className="queue-owner">{contact.ownerName}</span>}
                </div>
                <div className="queue-actions">
                  <button
                    type="button"
                    className="btn btn-sm btn-primary"
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
                  <button
                    type="button"
                    className="btn btn-sm"
                    disabled={busyId === contact.id}
                    onClick={() => quickLog(contact, 'REPLY')}
                    aria-label={`Log reply from ${contact.name}`}
                  >
                    Replied
                  </button>
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

/** Client and prospect companies whose follow-up date has come (ClickUp's Follow-Up Date). */
function CompanyFollowUps({ data }) {
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
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}

function CandidateQueue({ data, onLog }) {
  const candidates = data.candidatesDue;
  if (candidates.length === 0) return null;

  return (
    <section className="panel" aria-labelledby="queue-candidates">
      <div className="panel-head">
        <h2 id="queue-candidates">
          Candidates to follow up <span className="count">{data.candidatesDueTotal}</span>
        </h2>
      </div>
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
