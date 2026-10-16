import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { api, errorMessage } from '../api.js';
import { ErrorNote, ExternalLink, PageHeader } from '../components/Bits.jsx';
import CandidateForm from '../forms/CandidateForm.jsx';
import ContactForm from '../forms/ContactForm.jsx';
import { useLoader, useTitle, useToast } from '../hooks.js';
import { bookmarklet, findCompany, findExisting, jobChange, logBookmarklet, parseHeadline } from '../linkedin.js';

/**
 * Without details: how to set up the Add to JM CRM bookmark. With them (the bookmark opens this
 * page from a LinkedIn profile): the person's details, ready to save as a contact or a candidate.
 */
export default function LinkedInPage() {
  useTitle('Add from LinkedIn');
  const [params] = useSearchParams();
  const capture = {
    name: params.get('name')?.trim() ?? '',
    headline: params.get('headline')?.trim() ?? '',
    company: params.get('company')?.trim() ?? '',
    location: params.get('location')?.trim() ?? '',
    url: params.get('url')?.trim() ?? '',
  };
  return capture.name || capture.url ? <AddPerson capture={capture} /> : <Setup />;
}

/** A bookmark button to drag to the bookmarks bar. Clicking it here only shows a hint. */
function BookmarkButton({ address, children }) {
  const link = useRef(null);
  const [hint, setHint] = useState(false);

  // React won't render a javascript: link, so the bookmark's address is set directly.
  useEffect(() => {
    link.current?.setAttribute('href', address);
  }, [address]);

  return (
    <>
      <a
        ref={link}
        className="bookmarklet"
        draggable="true"
        onClick={(event) => {
          event.preventDefault();
          setHint(true);
        }}
      >
        {children}
      </a>
      {hint && <span className="warn-note bookmarklet-hint">Drag it to the bookmarks bar rather than clicking it here.</span>}
    </>
  );
}

function Setup() {

  return (
    <>
      <PageHeader title="Add from LinkedIn" sub="Save people from their LinkedIn profiles, and log your LinkedIn messages, in a click or two." />
      <section className="panel narrow-panel" aria-labelledby="linkedin-setup">
        <div className="panel-head">
          <h2 id="linkedin-setup">Set up the button once</h2>
        </div>
        <ol className="plain-list numbered linkedin-steps">
          <li>
            Show the bookmarks bar in Chrome or Edge: press <kbd>Ctrl</kbd> <kbd>Shift</kbd> <kbd>B</kbd>.
          </li>
          <li>
            Drag this button onto the bookmarks bar:{' '}
            <BookmarkButton address={bookmarklet(window.location.origin)}>Add to JM CRM</BookmarkButton>
          </li>
          <li>
            On someone’s LinkedIn profile, click <strong>Add to JM CRM</strong> in the bookmarks bar. The CRM opens with their
            name, title, company and profile link filled in. Choose contact or candidate, check the details and save.
          </li>
        </ol>
        <p className="panel-foot">
          The button only copies what’s on the profile you’re looking at into this CRM. Nothing is sent anywhere else. It works
          on regular profiles and Sales Navigator leads; if LinkedIn changes its pages and a detail is missed, fill it in before
          saving.
        </p>
      </section>

      <section className="panel narrow-panel" aria-labelledby="linkedin-log-setup">
        <div className="panel-head">
          <h2 id="linkedin-log-setup">Log LinkedIn messages in one click</h2>
        </div>
        <ol className="plain-list numbered linkedin-steps">
          <li>
            Drag this button onto the bookmarks bar too:{' '}
            <BookmarkButton address={logBookmarklet(window.location.origin)}>Log to JM CRM</BookmarkButton>
          </li>
          <li>
            When someone replies, open the conversation on LinkedIn (the messaging page or a chat window) and click{' '}
            <strong>Log to JM CRM</strong>. The CRM opens on their record with their last message.
          </li>
          <li>
            Click <strong>Log their reply</strong>, or <strong>Log my message</strong> for one you sent without{' '}
            <strong>Write</strong>. The CRM suggests the right one from who wrote last.
          </li>
        </ol>
        <p className="panel-foot">
          It reads only the conversation you have open, when you click it, and sends it only to this CRM. Nothing runs on its
          own. It also works on a profile page. If it can’t tell who wrote last, pick the button yourself.
        </p>
      </section>
    </>
  );
}

function AddPerson({ capture }) {
  const navigate = useNavigate();
  const toast = useToast();
  const contacts = useLoader(() => api.contacts.list(), [], 'contacts');
  const candidates = useLoader(() => api.candidates.list(), [], 'candidates');
  const companies = useLoader(() => api.companies.list(), [], 'companies');
  const [kind, setKind] = useState('contact');
  const [updating, setUpdating] = useState(null);
  const [addedCompany, setAddedCompany] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const headline = parseHeadline(capture.headline);
  const companyName = capture.company || headline.company;
  const company = addedCompany ?? findCompany(companyName, companies.data);
  // A title only counts when the headline clearly gives one: "Head of ML at Northwind".
  const job = { title: headline.company ? headline.title : '', company: companyName };
  const existing = findExisting(capture, contacts.data, candidates.data).map((match) => ({
    ...match,
    change: jobChange(match.kind, match.record, job),
  }));
  const changed = existing.filter((match) => match.change);

  /** Opens the full record (lists leave out notes) to update it with the new job. */
  async function startUpdate({ kind: which, record, change }) {
    setBusy(true);
    setError('');
    try {
      const full = which === 'contact' ? await api.contacts.get(record.id) : await api.candidates.get(record.id);
      setUpdating({ kind: which, record: full, change });
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  /** Notes the move in their history, then opens the record. */
  async function updated(record) {
    const { kind: which, change } = updating;
    const notes = `Job change from LinkedIn: ${change.now}${change.was ? ` (was ${change.was})` : ''}.`;
    const log = which === 'contact' ? api.contacts.logActivity : api.candidates.logActivity;
    try {
      await log(record.id, { type: 'NOTE', notes });
      toast(`Updated ${record.name}`);
    } catch {
      toast(`Updated ${record.name}, but the job change couldn’t be noted in the history.`);
    }
    navigate(`/${which === 'contact' ? 'contacts' : 'candidates'}/${record.id}`);
  }

  async function addCompany() {
    setBusy(true);
    setError('');
    try {
      const created = await api.companies.create({ name: companyName, status: 'LEAD' });
      setAddedCompany(created);
      companies.reload();
      toast(`Added ${created.name}`);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  function saved(record, path) {
    toast(`Added ${record.name}`);
    navigate(path);
  }

  return (
    <>
      <PageHeader title="Add from LinkedIn" sub="Check the details and save. You can close this tab afterwards." />

      <section className="panel linkedin-capture" aria-labelledby="linkedin-person">
        <div>
          <h2 id="linkedin-person">{capture.name || 'Name not found'}</h2>
          {capture.headline && <p>{capture.headline}</p>}
          {(capture.location || capture.url) && (
            <p className="muted">
              {capture.location}
              {capture.location && capture.url ? ', ' : ''}
              {capture.url && <ExternalLink href={capture.url}>LinkedIn profile</ExternalLink>}
            </p>
          )}
        </div>
        {existing.length > 0 && (
          <div className="warn-note linkedin-existing">
            <p>
              Already in the CRM:{' '}
              {existing.map(({ kind: which, record }, index) => (
                <span key={`${which}-${record.id}`}>
                  {index > 0 && ', '}
                  <Link to={`/${which === 'contact' ? 'contacts' : 'candidates'}/${record.id}`}>{record.name}</Link> ({which}
                  {which === 'contact' && record.companyName ? ` at ${record.companyName}` : ''})
                </span>
              ))}
              .{changed.length === 0 && ' Open that record instead, or add them again below.'}
            </p>
            {changed.map((match) => (
              <p key={`${match.kind}-${match.record.id}`} className="linkedin-change">
                <span>
                  <strong>New job.</strong> LinkedIn shows {match.change.now}. The {match.kind} record has{' '}
                  {match.change.was || 'no job'}.
                </span>
                <button type="button" className="btn btn-sm" disabled={busy} onClick={() => startUpdate(match)}>
                  Update {match.kind}
                </button>
              </p>
            ))}
          </div>
        )}
      </section>

      {updating ? (
        <UpdatePerson
          updating={updating}
          capture={capture}
          company={addedCompany ?? findCompany(updating.change.company, companies.data)}
          companyName={companyName}
          busy={busy}
          error={error}
          onAddCompany={addCompany}
          onSaved={updated}
          onCancel={() => setUpdating(null)}
        />
      ) : (
        <section className="panel" aria-labelledby="linkedin-add">
          <div className="panel-head">
            <h2 id="linkedin-add">Add as</h2>
            <fieldset className="segmented segmented-small">
              <legend className="visually-hidden">Add as</legend>
              {[
                ['contact', 'Contact at a client'],
                ['candidate', 'Candidate'],
              ].map(([value, label]) => (
                <label key={value} className={kind === value ? 'segment segment-on' : 'segment'}>
                  <input type="radio" name="linkedin-kind" checked={kind === value} onChange={() => setKind(value)} />
                  {label}
                </label>
              ))}
            </fieldset>
          </div>
          <ErrorNote>{error}</ErrorNote>

          {kind === 'contact' ? (
            <>
              {companyName && !company && companies.data && (
                <p className="setup-note linkedin-company">
                  {companyName} isn’t in the CRM yet.{' '}
                  <button type="button" className="btn btn-sm" disabled={busy} onClick={addCompany}>
                    {busy ? 'Adding…' : `Add ${companyName} as a company`}
                  </button>
                </p>
              )}
              <ContactForm
                key={company?.id ?? 'no-company'}
                contact={{
                  name: capture.name,
                  title: headline.title,
                  linkedinUrl: capture.url,
                  companyId: company?.id ?? '',
                }}
                onSaved={(record) => saved(record, `/contacts/${record.id}`)}
                onCancel={() => navigate('/')}
              />
            </>
          ) : (
            <CandidateForm
              candidate={{
                name: capture.name,
                currentTitle: headline.title,
                currentCompany: companyName,
                location: capture.location,
                linkedinUrl: capture.url,
                source: 'LINKEDIN',
              }}
              onSaved={(record) => saved(record, `/candidates/${record.id}`)}
              onCancel={() => navigate('/')}
            />
          )}
        </section>
      )}
    </>
  );
}

/** The record they're already in, with the job LinkedIn shows filled in, to check and save. */
function UpdatePerson({ updating, capture, company, companyName, busy, error, onAddCompany, onSaved, onCancel }) {
  const { kind, record, change } = updating;
  const ended = ['NO_RESPONSE', 'NOT_INTERESTED', 'NURTURE'].includes(record.status);
  const newCompany = kind === 'contact' && change.companyChanged ? company : null;

  return (
    <section className="panel" aria-labelledby="linkedin-update">
      <div className="panel-head">
        <h2 id="linkedin-update">Update {record.name}</h2>
        <button type="button" className="btn btn-sm" onClick={onCancel}>
          Back
        </button>
      </div>
      <p className="form-intro">
        LinkedIn shows {change.now}. Check the details and save. The move is noted in their history.
      </p>
      <ErrorNote>{error}</ErrorNote>
      {kind === 'contact' ? (
        <>
          {change.companyChanged && !newCompany && (
            <p className="setup-note linkedin-company">
              {companyName} isn’t in the CRM yet.{' '}
              <button type="button" className="btn btn-sm" disabled={busy} onClick={onAddCompany}>
                {busy ? 'Adding…' : `Add ${companyName} as a company`}
              </button>
            </p>
          )}
          {change.companyChanged && ended && (
            <p className="setup-note">Their sequence has ended. A new job is a good reason to restart it on their page.</p>
          )}
          <ContactForm
            key={newCompany?.id ?? 'same-company'}
            contact={{
              ...record,
              title: change.title || record.title,
              companyId: newCompany?.id ?? record.companyId,
              linkedinUrl: record.linkedinUrl || capture.url,
            }}
            onSaved={onSaved}
            onCancel={onCancel}
          />
        </>
      ) : (
        <CandidateForm
          candidate={{
            ...record,
            currentTitle: change.title || record.currentTitle,
            currentCompany: change.company,
            linkedinUrl: record.linkedinUrl || capture.url,
          }}
          onSaved={onSaved}
          onCancel={onCancel}
        />
      )}
    </section>
  );
}
