import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { api, errorMessage } from '../api.js';
import { ErrorNote, ExternalLink, PageHeader } from '../components/Bits.jsx';
import CandidateForm from '../forms/CandidateForm.jsx';
import ContactForm from '../forms/ContactForm.jsx';
import { useLoader, useTitle, useToast } from '../hooks.js';
import { bookmarklet, findCompany, findExisting, parseHeadline } from '../linkedin.js';

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

function Setup() {
  const link = useRef(null);
  const [hint, setHint] = useState(false);

  // React won't render a javascript: link, so the bookmark's address is set directly.
  useEffect(() => {
    link.current?.setAttribute('href', bookmarklet(window.location.origin));
  }, []);

  return (
    <>
      <PageHeader title="Add from LinkedIn" sub="Save someone from their LinkedIn profile in two clicks." />
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
            <a
              ref={link}
              className="bookmarklet"
              draggable="true"
              onClick={(event) => {
                event.preventDefault();
                setHint(true);
              }}
            >
              Add to JM CRM
            </a>
            {hint && <span className="warn-note bookmarklet-hint">Drag it to the bookmarks bar rather than clicking it here.</span>}
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
  const [addedCompany, setAddedCompany] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const headline = parseHeadline(capture.headline);
  const companyName = capture.company || headline.company;
  const company = addedCompany ?? findCompany(companyName, companies.data);
  const existing = findExisting(capture, contacts.data, candidates.data);

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
          <div className="warn-note">
            Already in the CRM:{' '}
            {existing.map(({ kind: which, record }, index) => (
              <span key={`${which}-${record.id}`}>
                {index > 0 && ', '}
                <Link to={`/${which === 'contact' ? 'contacts' : 'candidates'}/${record.id}`}>{record.name}</Link> ({which}
                {which === 'contact' && record.companyName ? ` at ${record.companyName}` : ''})
              </span>
            ))}
            . Open that record instead, or add them again below.
          </div>
        )}
      </section>

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
    </>
  );
}
