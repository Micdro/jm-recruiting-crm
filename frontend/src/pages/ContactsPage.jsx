import { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { api, errorMessage } from '../api.js';
import { DueTag, Empty, ErrorNote, Loading, PageHeader, Pill, ShowMore, TouchPips } from '../components/Bits.jsx';
import { FilterCheck, FilterSelect, SearchBox } from '../components/ListFilters.jsx';
import Modal from '../components/Modal.jsx';
import ContactForm from '../forms/ContactForm.jsx';
import { ACTION_TYPE, BUYING_ROLE, OUTREACH_STATUS, options } from '../labels.js';
import { todayIso } from '../format.js';
import { matchesQuery, useAuth, useFilterParams, useLoader, useTitle, useToast, useUsers } from '../hooks.js';

const CLOSED = ['NOT_INTERESTED', 'DO_NOT_CONTACT'];


const PAGE_SIZE = 200;
export default function ContactsPage() {
  useTitle('Contacts');
  const navigate = useNavigate();
  const toast = useToast();
  const { user } = useAuth();
  const users = useUsers();
  const contacts = useLoader(() => api.contacts.list(), [], 'contacts');
  const filters = useFilterParams();
  const [adding, setAdding] = useState(false);
  const [limit, setLimit] = useState(PAGE_SIZE);

  const q = filters.get('q');
  const status = filters.get('status');
  const owner = filters.get('owner');
  const due = filters.get('due') === 'true';
  const today = todayIso();

  const rows = (contacts.data ?? []).filter(
    (contact) =>
      (!status || contact.status === status) &&
      (!owner || String(contact.ownerId) === owner) &&
      (!due || (contact.nextActionDate && contact.nextActionDate <= today && !CLOSED.includes(contact.status))) &&
      matchesQuery(q, contact.name, contact.email, contact.title, contact.companyName),
  );

  return (
    <>
      <PageHeader title="Contacts" sub="Buyers and hiring managers at client and prospect companies.">
        <button type="button" className="btn btn-primary" onClick={() => setAdding(true)}>
          Add contact
        </button>
      </PageHeader>

      <div className="filters">
        <SearchBox value={q} onChange={filters.set('q')} placeholder="Search name, email, title or company" />
        <FilterSelect label="Status" value={status} onChange={filters.set('status')} options={options(OUTREACH_STATUS)} />
        <FilterSelect
          label="Owner"
          value={owner}
          onChange={filters.set('owner')}
          options={users.map((member) => ({
            value: String(member.id),
            label: member.id === user?.id ? `${member.displayName} (you)` : member.displayName,
          }))}
          allLabel="Everyone"
        />
        <FilterCheck label="Due now" checked={due} onChange={filters.set('due')} />
      </div>

      {contacts.error && <ErrorNote>{errorMessage(contacts.error)}</ErrorNote>}
      {!contacts.data && contacts.loading && <Loading label="Loading contacts" />}

      {contacts.data && rows.length === 0 && (
        <Empty title={contacts.data.length === 0 ? 'No contacts yet' : 'No contacts match these filters'}>
          {contacts.data.length === 0 ? (
            <>
              Add one, or <Link to="/import">import your buyer list</Link>.
            </>
          ) : null}
        </Empty>
      )}

      {rows.length > 0 && (
        <div className="table-wrap">
          <table className="table">
            <caption className="visually-hidden">Contacts</caption>
            <thead>
              <tr>
                <th scope="col">Name</th>
                <th scope="col">Company</th>
                <th scope="col">Status</th>
                <th scope="col">Touches</th>
                <th scope="col">Next action</th>
                <th scope="col">Owner</th>
              </tr>
            </thead>
            <tbody>
              {rows.slice(0, limit).map((contact) => (
                <tr key={contact.id} className="row-link" onClick={() => navigate(`/contacts/${contact.id}`)}>
                  <td>
                    <Link to={`/contacts/${contact.id}`} className="cell-title" onClick={(event) => event.stopPropagation()}>
                      {contact.name}
                    </Link>
                    {(contact.title || contact.buyingRole) && (
                      <span className="cell-sub">
                        {contact.title}
                        {contact.buyingRole && <span className="tag">{BUYING_ROLE[contact.buyingRole]}</span>}
                      </span>
                    )}
                  </td>
                  <td>{contact.companyName}</td>
                  <td>
                    <Pill value={contact.status} labels={OUTREACH_STATUS} />
                  </td>
                  <td>
                    <TouchPips count={contact.touchCount} />
                  </td>
                  <td>
                    {contact.nextActionDate ? (
                      <>
                        <DueTag date={contact.nextActionDate} today={today} />
                        <span className="cell-sub">{ACTION_TYPE[contact.nextActionType]}</span>
                      </>
                    ) : (
                      <span className="muted">None</span>
                    )}
                  </td>
                  <td>{contact.ownerName ?? <span className="muted">Unassigned</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="table-count">
            Showing {Math.min(limit, rows.length)} of {rows.length}
            {rows.length < contacts.data.length ? `, filtered from ${contacts.data.length}` : ''}
            <ShowMore shown={limit} total={rows.length} onMore={() => setLimit((value) => value + PAGE_SIZE)} />
          </p>
        </div>
      )}

      {adding && (
        <Modal title="Add contact" onClose={() => setAdding(false)}>
          <ContactForm
            onCancel={() => setAdding(false)}
            onSaved={(saved) => {
              setAdding(false);
              toast(`Added ${saved.name}`);
              navigate(`/contacts/${saved.id}`);
            }}
          />
        </Modal>
      )}
    </>
  );
}
