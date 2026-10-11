import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { api, errorMessage } from '../api.js';
import { ConfirmButton, Details, DueTag, Empty, ErrorNote, ExternalLink, Loading, Pill, TouchPips } from '../components/Bits.jsx';
import NoteForm from '../components/NoteForm.jsx';
import Modal from '../components/Modal.jsx';
import Timeline from '../components/Timeline.jsx';
import CompanyForm from '../forms/CompanyForm.jsx';
import ContactForm from '../forms/ContactForm.jsx';
import SearchForm from '../forms/SearchForm.jsx';
import { COMPANY_STATUS, OUTREACH_STATUS, SEARCH_STATUS, TRACK } from '../labels.js';
import { formatDate, href, todayIso } from '../format.js';
import { useLoader, useTitle, useToast } from '../hooks.js';

export default function CompanyDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const toast = useToast();
  const company = useLoader(() => api.companies.get(id), [id]);
  const contacts = useLoader(() => api.contacts.list({ companyId: id }), [id]);
  const searches = useLoader(() => api.searches.list({ companyId: id }), [id]);
  const history = useLoader(() => api.activities.list({ companyId: id }), [id]);
  const [modal, setModal] = useState(null);
  const [error, setError] = useState('');

  const record = company.data;
  useTitle(record?.name ?? 'Company');

  if (company.error) {
    return (
      <>
        <Link to="/companies" className="back">
          Companies
        </Link>
        <ErrorNote>{errorMessage(company.error)}</ErrorNote>
      </>
    );
  }
  if (!record) return <Loading label="Loading company" />;

  const today = todayIso();

  return (
    <>
      <Link to="/companies" className="back">
        Companies
      </Link>
      <div className="page-header">
        <div className="page-heading">
          <h1>{record.name}</h1>
          <div className="page-sub page-sub-row">
            <Pill value={record.status} labels={COMPANY_STATUS} />
            {record.focus && <span>{record.focus}</span>}
          </div>
        </div>
        <div className="page-actions">
          <button type="button" className="btn" onClick={() => setModal('edit')}>
            Edit
          </button>
          <ConfirmButton
            onConfirm={async () => {
              try {
                await api.companies.remove(record.id);
                toast(`Deleted ${record.name}`);
                navigate('/companies');
              } catch (err) {
                setError(errorMessage(err));
              }
            }}
          />
        </div>
      </div>
      <ErrorNote>{error}</ErrorNote>

      <div className="detail-grid">
        <div className="detail-main">
          <section className="panel" aria-labelledby="company-contacts">
            <div className="panel-head">
              <h2 id="company-contacts">People</h2>
              <button type="button" className="btn btn-sm" onClick={() => setModal('contact')}>
                Add contact
              </button>
            </div>
            {contacts.data && contacts.data.length === 0 && (
              <Empty title="No contacts here yet">Add the buyer or hiring manager you plan to reach.</Empty>
            )}
            {contacts.data && contacts.data.length > 0 && (
              <ul className="people">
                {contacts.data.map((contact) => (
                  <li key={contact.id}>
                    <div>
                      <Link to={`/contacts/${contact.id}`} className="cell-title">
                        {contact.name}
                      </Link>
                      {contact.title && <span className="cell-sub">{contact.title}</span>}
                    </div>
                    <Pill value={contact.status} labels={OUTREACH_STATUS} />
                    <TouchPips count={contact.touchCount} />
                    <DueTag date={contact.nextActionDate} today={today} />
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section className="panel" aria-labelledby="company-searches">
            <div className="panel-head">
              <h2 id="company-searches">Searches</h2>
              <button type="button" className="btn btn-sm" onClick={() => setModal('search')}>
                Open search
              </button>
            </div>
            {searches.data && searches.data.length === 0 && (
              <Empty title="No searches with this company">Open one when they engage the firm on a role.</Empty>
            )}
            {searches.data && searches.data.length > 0 && (
              <ul className="people">
                {searches.data.map((search) => (
                  <li key={search.id}>
                    <div>
                      <Link to={`/searches/${search.id}`} className="cell-title">
                        {search.title}
                      </Link>
                      <span className="cell-sub">{TRACK[search.track]}</span>
                    </div>
                    <Pill value={search.status} labels={SEARCH_STATUS} />
                    <span className="cell-sub">Opened {formatDate(search.openedDate)}</span>
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section className="panel">
            <div className="panel-head">
              <h2>Details</h2>
            </div>
            <Details
              items={[
                { label: 'Website', value: record.website && <ExternalLink href={href(record.website)}>{record.website}</ExternalLink> },
                { label: 'LinkedIn', value: record.linkedinProfile && <ExternalLink href={href(record.linkedinProfile)}>Company page</ExternalLink> },
                { label: 'Location', value: record.location },
                { label: 'Size', value: record.companySize },
                { label: 'Owner', value: record.ownerName ?? 'Unassigned' },
              ]}
            />
            {record.notes && <p className="notes">{record.notes}</p>}
          </section>
        </div>

        <section className="panel detail-side" aria-labelledby="company-history">
          <div className="panel-head">
            <h2 id="company-history">History</h2>
          </div>
          <NoteForm
            onSave={async (body) => {
              await api.activities.note({ ...body, companyId: record.id });
              history.reload();
            }}
          />
          <Timeline activities={history.data} loading={history.loading} show={['contact', 'candidate', 'search']} />
        </section>
      </div>

      {modal === 'edit' && (
        <Modal title={`Edit ${record.name}`} onClose={() => setModal(null)}>
          <CompanyForm
            company={record}
            onCancel={() => setModal(null)}
            onSaved={(saved) => {
              setModal(null);
              toast('Company saved');
              company.setData(saved);
              history.reload();
            }}
          />
        </Modal>
      )}
      {modal === 'contact' && (
        <Modal title={`Add contact at ${record.name}`} onClose={() => setModal(null)}>
          <ContactForm
            contact={{ companyId: record.id }}
            onCancel={() => setModal(null)}
            onSaved={(saved) => {
              setModal(null);
              toast(`Added ${saved.name}`);
              contacts.reload();
              company.reload();
            }}
          />
        </Modal>
      )}
      {modal === 'search' && (
        <Modal title={`Open a search with ${record.name}`} onClose={() => setModal(null)} wide>
          <SearchForm
            search={{ companyId: record.id }}
            onCancel={() => setModal(null)}
            onSaved={(saved) => {
              setModal(null);
              toast(`Opened ${saved.title}`);
              navigate(`/searches/${saved.id}`);
            }}
          />
        </Modal>
      )}
    </>
  );
}
