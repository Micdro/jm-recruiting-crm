import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router';
import { api, errorMessage } from '../api.js';
import BlindProfileForm from '../components/BlindProfileForm.jsx';
import { Empty, ErrorNote, ExternalLink, Loading, PageHeader, Pill, ShowMore } from '../components/Bits.jsx';
import MessageComposer from '../components/MessageComposer.jsx';
import Modal from '../components/Modal.jsx';
import { ACTIVITY_TYPE, COMPANY_STATUS, JOB_BOARD, OUTREACH_STATUS, ROLE_TRACK, TIER } from '../labels.js';
import { formatDay, formatTimestamp, plural } from '../format.js';
import { useLoader, useTitle, useToast } from '../hooks.js';
import { matchForRoles } from '../matching.js';

const ROLES_SHOWN = 5;
const LEVEL_CLASS = { 'Strong match': 'match-strong', 'Good match': 'match-good-fit', 'Possible match': 'match-possible' };
/** People whose sequence is over or who said no can't be written to from here. */
const CANT_WRITE = new Set(['NO_RESPONSE', 'NOT_INTERESTED', 'DO_NOT_CONTACT']);
const BOARDLESS_PAGE = 30;

/** While the CRM reads or looks for boards, the page checks back this often. */
const POLL_MS = 3000;

export default function SignalsPage() {
  useTitle('Hiring signals');
  const signals = useLoader(() => api.signals.list(), [], 'signals');
  // For matching candidates to the roles. The same lists the rest of the CRM loads.
  const candidates = useLoader(() => api.candidates.list(), [], 'candidates');
  const crmCompanies = useLoader(() => api.companies.list(), [], 'companies');
  const [view, setView] = useState('new');
  const [error, setError] = useState('');
  const [writing, setWriting] = useState(null);
  const [profiling, setProfiling] = useState(null);
  const toast = useToast();

  /** Opens the composer for someone at a hiring company, about one of its roles. */
  async function write(person, company) {
    setError('');
    try {
      const contact = await api.contacts.get(person.id);
      setWriting({ contact, company });
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  /** Opens a blind profile of a candidate, prepared for one of a company's open roles. */
  async function profile(candidateId, company, role) {
    setError('');
    try {
      const candidate = await api.candidates.get(candidateId);
      setProfiling({ candidate, forRole: { companyName: company.name, title: role.title } });
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  const data = signals.data;
  const running = data?.overview.running;
  const reload = signals.reload;

  useEffect(() => {
    if (!running) return undefined;
    const timer = setTimeout(reload, POLL_MS);
    return () => clearTimeout(timer);
  }, [running, data, reload]);

  async function startJob(kind) {
    setError('');
    try {
      const result = kind === 'check' ? await api.signals.check() : await api.signals.findBoards();
      if (result.started) {
        toast(
          kind === 'check'
            ? `Reading ${plural(result.companies, 'job board')}. New roles show up here as they come in.`
            : `Looking for job boards for ${plural(result.companies, 'company', 'companies')}.`,
        );
      } else if (result.overview.running) {
        toast('Already on it. This page updates as it goes.');
      } else {
        toast(
          kind === 'check'
            ? 'No job boards to read yet. Find job boards first, or add one on a company’s page.'
            : 'Every company without a board was looked up in the last 30 days.',
        );
      }
      signals.setData((current) => (current ? { ...current, overview: result.overview } : current));
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  /** Applies a change to one company in the list without reloading it. */
  function updateCompany(companyId, change) {
    signals.setData((current) =>
      current
        ? {
            ...current,
            companies: current.companies
              .map((company) => (company.companyId === companyId ? change(company) : company))
              .filter(Boolean),
          }
        : current,
    );
  }

  async function act(action, success) {
    setError('');
    try {
      await action();
      success();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  const companies = data?.companies ?? [];
  const fresh = companies.filter((company) => company.unreviewedRoles > 0);
  const shown = view === 'new' ? fresh : companies;

  return (
    <>
      <PageHeader
        title="Hiring signals"
        sub="Open forward deployed and AI platform roles at companies in the CRM, from their Greenhouse, Lever and Ashby job boards."
      >
        <Link to="/signals/guide" className="btn btn-quiet">
          How to set up
        </Link>
        <button type="button" className="btn" disabled={Boolean(running)} onClick={() => startJob('find')}>
          Find job boards
        </button>
        <button type="button" className="btn btn-primary" disabled={Boolean(running)} onClick={() => startJob('check')}>
          Check boards now
        </button>
      </PageHeader>

      {!data && signals.loading && <Loading label="Loading hiring signals" />}
      {signals.error && <ErrorNote>{errorMessage(signals.error)}</ErrorNote>}
      <ErrorNote>{error}</ErrorNote>

      {data && (
        <>
          <StatusLine overview={data.overview} />

          <div className="signals-bar">
            <fieldset className="segmented segmented-small">
              <legend className="visually-hidden">Which roles</legend>
              {[
                ['new', `New roles (${fresh.length})`],
                ['all', `All hiring (${companies.length})`],
              ].map(([value, label]) => (
                <label key={value} className={view === value ? 'segment segment-on' : 'segment'}>
                  <input type="radio" name="view" value={value} checked={view === value} onChange={() => setView(value)} />
                  {label}
                </label>
              ))}
            </fieldset>
          </div>

          <section className="panel" aria-label={view === 'new' ? 'Companies with new roles' : 'Companies hiring'}>
            {shown.length === 0 ? (
              <NothingYet overview={data.overview} view={view} hasAny={companies.length > 0} onFind={() => startJob('find')} />
            ) : (
              <ul className="signals">
                {shown.map((company) => (
                  <CompanySignal
                    key={company.companyId}
                    company={company}
                    candidates={candidates.data}
                    crmCompanies={crmCompanies.data}
                    onWrite={(person) => write(person, company)}
                    onProfile={(candidateId, role) => profile(candidateId, company, role)}
                    onReviewed={() =>
                      act(
                        () => api.signals.reviewed(company.companyId),
                        () => {
                          updateCompany(company.companyId, (current) => ({
                            ...current,
                            unreviewedRoles: 0,
                            roles: current.roles.map((role) => ({ ...role, unreviewed: false })),
                          }));
                          toast(`Marked ${company.name} reviewed. New roles there will show up again.`);
                        },
                      )
                    }
                    onConfirm={() =>
                      act(
                        () => api.signals.confirm(company.companyId),
                        () => updateCompany(company.companyId, (current) => ({ ...current, boardConfirmed: true })),
                      )
                    }
                    onWrongBoard={() =>
                      act(
                        () => api.signals.setBoard(company.companyId, { none: true }),
                        () => {
                          updateCompany(company.companyId, () => null);
                          toast(`Removed that board from ${company.name}. Add the right link on its page if it has one.`);
                        },
                      )
                    }
                  />
                ))}
              </ul>
            )}
          </section>

          {data.failing.length > 0 && <FailingBoards companies={data.failing} />}
          {data.withoutBoard.length > 0 && <WithoutBoard companies={data.withoutBoard} />}
        </>
      )}

      {writing && (
        <Modal title={`Message for ${writing.contact.name}`} onClose={() => setWriting(null)} wide>
          <SignalComposer
            contact={writing.contact}
            roles={writing.company.roles}
            onLogged={(type) => {
              toast(`${ACTIVITY_TYPE[type]} logged for ${writing.contact.name}.`);
              setWriting(null);
              reload();
            }}
          />
        </Modal>
      )}

      {profiling && (
        <Modal title="Blind profile for a client" onClose={() => setProfiling(null)} wide>
          <BlindProfileForm
            candidate={profiling.candidate}
            entries={[]}
            forRole={profiling.forRole}
            onCancel={() => setProfiling(null)}
            onDone={(reference) => {
              setProfiling(null);
              toast(`Blind profile ${reference} downloaded. It’s noted in the candidate’s history.`);
            }}
          />
        </Modal>
      )}
    </>
  );
}

/** The composer, about one of the company's roles: the newest new one unless another is picked. */
function SignalComposer({ contact, roles, onLogged }) {
  const titles = [...new Set(roles.map((role) => role.title))];
  const first = roles.find((role) => role.unreviewed)?.title ?? titles[0];
  const [title, setTitle] = useState(first);

  return (
    <>
      {titles.length > 1 && (
        <div className="field signal-role-pick">
          <label htmlFor="signal-role">About the opening</label>
          <select id="signal-role" value={title} onChange={(event) => setTitle(event.target.value)}>
            {titles.map((value) => (
              <option key={value} value={value}>
                {value}
              </option>
            ))}
          </select>
        </div>
      )}
      <MessageComposer key={title} contact={contact} role={title} onLogged={(updated, type) => onLogged(type)} />
    </>
  );
}

function StatusLine({ overview }) {
  const { running } = overview;
  if (running) {
    const doing = running.kind === 'FIND' ? 'Looking for job boards' : 'Reading job boards';
    return (
      <p className="signals-status" role="status">
        {doing}: {running.done} of {running.total}.
      </p>
    );
  }
  const parts = [];
  parts.push(
    overview.lastCheckedAt ? `Boards last read ${formatTimestamp(overview.lastCheckedAt).toLowerCase()}.` : 'No boards read yet.',
  );
  parts.push(`${plural(overview.withBoard, 'company has', 'companies have')} a job board on file.`);
  if (overview.withoutBoard > 0) parts.push(`${overview.withoutBoard} still to look up.`);
  if (overview.toConfirm > 0) parts.push(`${plural(overview.toConfirm, 'board')} found automatically to check.`);
  return (
    <p className="signals-status" role="status">
      {parts.join(' ')}
    </p>
  );
}

function NothingYet({ overview, view, hasAny, onFind }) {
  if (overview.withBoard === 0) {
    return (
      <Empty title="No job boards on file yet">
        <p>
          The CRM looks up each company’s Greenhouse, Lever or Ashby board from its website and name on its own, a batch
          every 15 minutes while it’s open. Find job boards does them all now. You can also paste a board link on a
          company’s page. <Link to="/signals/guide">Read the setup guide</Link>.
        </p>
        {!overview.running && (
          <button type="button" className="btn btn-sm" onClick={onFind}>
            Find job boards
          </button>
        )}
      </Empty>
    );
  }
  if (view === 'new') {
    return (
      <Empty title="No new roles since your last review">
        {hasAny
          ? 'Roles posted from now on show up here. All hiring lists every open role.'
          : 'None of the boards on file have forward deployed or AI platform roles open right now.'}
      </Empty>
    );
  }
  return <Empty title="Nobody is hiring for these roles right now">Boards are read again every few hours.</Empty>;
}

function CompanySignal({ company, candidates, crmCompanies, onWrite, onProfile, onReviewed, onConfirm, onWrongBoard }) {
  const [allRoles, setAllRoles] = useState(false);
  const roles = allRoles ? company.roles : company.roles.slice(0, Math.max(ROLES_SHOWN, company.unreviewedRoles));
  const hidden = company.roles.length - roles.length;

  // Candidates for the new roles if there are any, otherwise for every open one.
  const matches = useMemo(() => {
    if (!candidates) return [];
    const fresh = company.roles.filter((role) => role.unreviewed);
    return matchForRoles({ name: company.name }, fresh.length ? fresh : company.roles, candidates, crmCompanies);
  }, [company, candidates, crmCompanies]);

  return (
    <li className="signal" data-testid="signal">
      <div className="signal-head">
        <div className="signal-who">
          <Link to={`/companies/${company.companyId}`} className="queue-name">
            {company.name}
          </Link>
          <span className="signal-meta">
            <Pill value={company.status} labels={COMPANY_STATUS} />
            <Pill value={company.tier} labels={TIER} />
            {company.ownerName && <span>{company.ownerName}</span>}
            <span>
              {plural(company.roles.length, 'matching role')}
              {company.openRoles ? ` of ${company.openRoles} open` : ''}
            </span>
          </span>
        </div>
        <div className="signal-actions">
          <ExternalLink href={company.boardUrl}>{JOB_BOARD[company.provider]} board</ExternalLink>
          {company.unreviewedRoles > 0 && (
            <button type="button" className="btn btn-sm" onClick={onReviewed}>
              Mark reviewed
            </button>
          )}
        </div>
      </div>

      {!company.boardConfirmed && (
        <p className="signal-confirm">
          This board was found automatically. Is it {company.name}’s?{' '}
          <button type="button" className="link-button" onClick={onConfirm}>
            Yes, it’s theirs
          </button>{' '}
          <button type="button" className="link-button" onClick={onWrongBoard}>
            No, wrong company
          </button>
        </p>
      )}

      <ul className="signal-roles">
        {roles.map((role, index) => (
          <li key={`${index}-${role.url ?? role.title}`}>
            <span className="signal-role">
              {role.url ? <ExternalLink href={role.url}>{role.title}</ExternalLink> : role.title}
              {role.unreviewed && <span className="pill pill-good">New</span>}
            </span>
            <span className="signal-role-meta">
              {[ROLE_TRACK[role.track], role.location, role.publishedAt && `posted ${formatDay(role.publishedAt)}`]
                .filter(Boolean)
                .join(', ')}
            </span>
          </li>
        ))}
      </ul>
      {hidden > 0 && (
        <button type="button" className="link-button show-more" onClick={() => setAllRoles(true)}>
          {plural(hidden, 'more role')}
        </button>
      )}

      <div className="signal-columns">
        <div className="signal-block">
          <h3 className="signal-subhead">Reach out to</h3>
          {company.people.length === 0 ? (
            <p className="signal-people">
              No contacts here yet. <Link to={`/companies/${company.companyId}`}>Add the hiring manager</Link>
            </p>
          ) : (
            <ul className="signal-list">
              {company.people.map((person) => (
                <li key={person.id}>
                  <span className="signal-list-who">
                    <Link to={`/contacts/${person.id}`}>{person.name}</Link>
                    <span className="muted">{[person.title, OUTREACH_STATUS[person.status]].filter(Boolean).join(', ')}</span>
                  </span>
                  <button
                    type="button"
                    className="btn btn-sm"
                    disabled={CANT_WRITE.has(person.status)}
                    title={CANT_WRITE.has(person.status) ? 'Their sequence is over, or they asked not to be contacted' : undefined}
                    onClick={() => onWrite(person)}
                    aria-label={`Write to ${person.name} about this role`}
                  >
                    Write
                  </button>
                </li>
              ))}
              {company.contactCount > company.people.length && (
                <li>
                  <Link to={`/companies/${company.companyId}`} className="muted">
                    {plural(company.contactCount - company.people.length, 'other contact')}
                  </Link>
                </li>
              )}
            </ul>
          )}
        </div>

        <div className="signal-block">
          <h3 className="signal-subhead">Candidates who fit</h3>
          {!candidates ? (
            <p className="signal-people">Loading candidates…</p>
          ) : matches.length === 0 ? (
            <p className="signal-people">Nobody in the CRM fits these roles yet.</p>
          ) : (
            <ul className="signal-list">
              {matches.map(({ candidate, role, level, reasons }) => (
                <li key={candidate.id}>
                  <span className="signal-list-who">
                    <span>
                      <Link to={`/candidates/${candidate.id}`}>{candidate.name}</Link>{' '}
                      <span className={`match-level ${LEVEL_CLASS[level]}`}>{level}</span>
                    </span>
                    <span className="muted">
                      {[
                        company.roles.length > 1 ? `For ${role.title}` : null,
                        ...reasons.filter((reason) => reason.good).slice(0, 3).map((reason) => reason.text),
                      ]
                        .filter(Boolean)
                        .join('. ')}
                    </span>
                  </span>
                  <button
                    type="button"
                    className="btn btn-sm"
                    onClick={() => onProfile(candidate.id, role)}
                    aria-label={`Blind profile of ${candidate.name} for ${role.title}`}
                  >
                    Blind profile
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
    </li>
  );
}

function FailingBoards({ companies }) {
  return (
    <section className="panel" aria-labelledby="signals-failing">
      <div className="panel-head">
        <h2 id="signals-failing">
          Boards that couldn’t be read <span className="count">{companies.length}</span>
        </h2>
      </div>
      <ul className="plain-list">
        {companies.map((company) => (
          <li key={company.companyId}>
            <Link to={`/companies/${company.companyId}`}>{company.name}</Link>{' '}
            <span className="muted">({JOB_BOARD[company.provider]})</span>: {company.error}
          </li>
        ))}
      </ul>
    </section>
  );
}

function WithoutBoard({ companies }) {
  const [limit, setLimit] = useState(BOARDLESS_PAGE);
  return (
    <section className="panel" aria-labelledby="signals-boardless">
      <div className="panel-head">
        <h2 id="signals-boardless">
          No job board on file <span className="count">{companies.length}</span>
        </h2>
      </div>
      <p className="panel-meta">
        The CRM looks these up on its own, a batch every 15 minutes, and tries again every 30 days for any it couldn’t
        find. Companies waiting on AI Found are looked up once accepted. If a company uses another system or posts on its
        own site, open it and mark that it has no board, so it isn’t looked up again.
      </p>
      <ul className="plain-list boardless">
        {companies.slice(0, limit).map((company) => (
          <li key={company.companyId}>
            <Link to={`/companies/${company.companyId}`}>{company.name}</Link>{' '}
            <span className="muted">
              {company.searchedAt ? `looked up ${formatDay(company.searchedAt)}, not found` : 'not looked up yet'}
            </span>
          </li>
        ))}
      </ul>
      <ShowMore shown={limit} total={companies.length} onMore={() => setLimit((value) => value + BOARDLESS_PAGE)} />
    </section>
  );
}
