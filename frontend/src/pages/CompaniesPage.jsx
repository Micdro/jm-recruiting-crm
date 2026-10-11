import { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { api, errorMessage } from '../api.js';
import { DueTag, Empty, ErrorNote, Loading, PageHeader, Pill, ShowMore } from '../components/Bits.jsx';
import { FilterCheck, FilterSelect, SearchBox } from '../components/ListFilters.jsx';
import Modal from '../components/Modal.jsx';
import CompanyForm from '../forms/CompanyForm.jsx';
import { COMPANY_STATUS, INDUSTRY, PARKED_COMPANY_STAGES, PRIORITY, SEGMENT, options } from '../labels.js';
import { todayIso } from '../format.js';
import { matchesQuery, useFilterParams, useLoader, useTitle, useToast, useUsers } from '../hooks.js';

const PAGE_SIZE = 200;

export default function CompaniesPage() {
  useTitle('Companies');
  const navigate = useNavigate();
  const toast = useToast();
  const companies = useLoader(() => api.companies.list(), [], 'companies');
  const users = useUsers();
  const filters = useFilterParams();
  const [adding, setAdding] = useState(false);
  const [limit, setLimit] = useState(PAGE_SIZE);

  const q = filters.get('q');
  const status = filters.get('status');
  const priority = filters.get('priority');
  const industry = filters.get('industry');
  const segment = filters.get('segment');
  const owner = filters.get('owner');
  const due = filters.get('due') === 'true';
  // Dormant, not qualified, not interested and complete companies stay out of the way unless asked for.
  const showParked = filters.get('parked') === 'true' || PARKED_COMPANY_STAGES.includes(status);
  const today = todayIso();

  const rows = (companies.data ?? []).filter(
    (company) =>
      (!status || company.status === status) &&
      (showParked || !PARKED_COMPANY_STAGES.includes(company.status)) &&
      (!priority || company.priority === priority) &&
      (!industry || company.industry === industry) &&
      (!segment || (company.segments ?? []).includes(segment)) &&
      (!owner || String(company.ownerId) === owner) &&
      (!due || (company.followUpDate && company.followUpDate <= today)) &&
      matchesQuery(q, company.name, company.focus, company.location, company.website),
  );

  return (
    <>
      <PageHeader title="Companies" sub="AI-native vendors selling into banks, insurers and other regulated institutions.">
        <button type="button" className="btn btn-primary" onClick={() => setAdding(true)}>
          Add company
        </button>
      </PageHeader>

      <div className="filters">
        <SearchBox value={q} onChange={filters.set('q')} placeholder="Search name, focus or location" />
        <FilterSelect label="Stage" value={status} onChange={filters.set('status')} options={options(COMPANY_STATUS)} />
        <FilterSelect label="Priority" value={priority} onChange={filters.set('priority')} options={options(PRIORITY)} />
        <FilterSelect label="Industry" value={industry} onChange={filters.set('industry')} options={options(INDUSTRY)} />
        <FilterSelect label="Segment" value={segment} onChange={filters.set('segment')} options={options(SEGMENT)} />
        <FilterSelect
          label="Owner"
          value={owner}
          onChange={filters.set('owner')}
          options={users.map((user) => ({ value: String(user.id), label: user.displayName }))}
          allLabel="Everyone"
        />
        <FilterCheck label="Follow-up due" checked={due} onChange={filters.set('due')} />
        <FilterCheck label="Show parked" checked={showParked} onChange={filters.set('parked')} />
      </div>

      {companies.error && <ErrorNote>{errorMessage(companies.error)}</ErrorNote>}
      {!companies.data && companies.loading && <Loading label="Loading companies" />}

      {companies.data && rows.length === 0 && (
        <Empty title={companies.data.length === 0 ? 'No companies yet' : 'No companies match these filters'}>
          {companies.data.length === 0 ? 'Add your first target account, or import a list with company names.' : null}
        </Empty>
      )}

      {rows.length > 0 && (
        <div className="table-wrap">
          <table className="table">
            <caption className="visually-hidden">Companies</caption>
            <thead>
              <tr>
                <th scope="col">Company</th>
                <th scope="col">Stage</th>
                <th scope="col">Priority</th>
                <th scope="col">Follow up</th>
                <th scope="col" className="num">Contacts</th>
                <th scope="col" className="num">Open searches</th>
                <th scope="col">Owner</th>
              </tr>
            </thead>
            <tbody>
              {rows.slice(0, limit).map((company) => (
                <tr key={company.id} className="row-link" onClick={() => navigate(`/companies/${company.id}`)}>
                  <td>
                    <Link to={`/companies/${company.id}`} className="cell-title" onClick={(event) => event.stopPropagation()}>
                      {company.name}
                    </Link>
                    {company.focus && <span className="cell-sub">{company.focus}</span>}
                    {company.location && <span className="cell-sub">{company.location}</span>}
                  </td>
                  <td>
                    <Pill value={company.status} labels={COMPANY_STATUS} />
                  </td>
                  <td>
                    <Pill value={company.priority} labels={PRIORITY} />
                  </td>
                  <td>{company.followUpDate && <DueTag date={company.followUpDate} today={today} />}</td>
                  <td className="num">{company.contactCount}</td>
                  <td className="num">{company.openSearchCount}</td>
                  <td>{company.ownerName ?? <span className="muted">Unassigned</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="table-count">
            Showing {Math.min(limit, rows.length)} of {rows.length}
            {rows.length < companies.data.length ? `, filtered from ${companies.data.length}` : ''}
            <ShowMore shown={limit} total={rows.length} onMore={() => setLimit((value) => value + PAGE_SIZE)} />
          </p>
        </div>
      )}

      {adding && (
        <Modal title="Add company" onClose={() => setAdding(false)}>
          <CompanyForm
            onCancel={() => setAdding(false)}
            onSaved={(saved) => {
              setAdding(false);
              toast(`Added ${saved.name}`);
              navigate(`/companies/${saved.id}`);
            }}
          />
        </Modal>
      )}
    </>
  );
}
