import { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { api, errorMessage } from '../api.js';
import { Empty, ErrorNote, Loading, PageHeader, Pill } from '../components/Bits.jsx';
import { FilterSelect, SearchBox } from '../components/ListFilters.jsx';
import Modal from '../components/Modal.jsx';
import CompanyForm from '../forms/CompanyForm.jsx';
import { COMPANY_STATUS, options } from '../labels.js';
import { matchesQuery, useFilterParams, useLoader, useTitle, useToast } from '../hooks.js';

export default function CompaniesPage() {
  useTitle('Companies');
  const navigate = useNavigate();
  const toast = useToast();
  const companies = useLoader(() => api.companies.list(), []);
  const filters = useFilterParams();
  const [adding, setAdding] = useState(false);

  const q = filters.get('q');
  const status = filters.get('status');

  const rows = (companies.data ?? []).filter(
    (company) =>
      (!status || company.status === status) &&
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
        <FilterSelect label="Status" value={status} onChange={filters.set('status')} options={options(COMPANY_STATUS)} />
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
                <th scope="col">Status</th>
                <th scope="col" className="num">Contacts</th>
                <th scope="col" className="num">Open searches</th>
                <th scope="col">Location</th>
                <th scope="col">Owner</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((company) => (
                <tr key={company.id} className="row-link" onClick={() => navigate(`/companies/${company.id}`)}>
                  <td>
                    <Link to={`/companies/${company.id}`} className="cell-title" onClick={(event) => event.stopPropagation()}>
                      {company.name}
                    </Link>
                    {company.focus && <span className="cell-sub">{company.focus}</span>}
                  </td>
                  <td>
                    <Pill value={company.status} labels={COMPANY_STATUS} />
                  </td>
                  <td className="num">{company.contactCount}</td>
                  <td className="num">{company.openSearchCount}</td>
                  <td>{company.location}</td>
                  <td>{company.ownerName ?? <span className="muted">Unassigned</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="table-count">
            {rows.length} of {companies.data.length}
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
