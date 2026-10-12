import { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { api, errorMessage } from '../api.js';
import { Empty, ErrorNote, Loading, PageHeader, Pill } from '../components/Bits.jsx';
import ExportButton from '../components/ExportButton.jsx';
import { FilterSelect, SearchBox } from '../components/ListFilters.jsx';
import Modal from '../components/Modal.jsx';
import StageBar from '../components/StageBar.jsx';
import SearchForm from '../forms/SearchForm.jsx';
import { ENGAGEMENT_TYPE, OPEN_SEARCH_STATUSES, SEARCH_STATUS, TRACK, options } from '../labels.js';
import { formatDate, percent, salaryRange } from '../format.js';
import { matchesQuery, useFilterParams, useLoader, useTitle, useToast } from '../hooks.js';

export default function SearchesPage() {
  useTitle('Searches');
  const navigate = useNavigate();
  const toast = useToast();
  const searches = useLoader(() => api.searches.list(), [], 'searches');
  const filters = useFilterParams();
  const [adding, setAdding] = useState(false);

  const q = filters.get('q');
  const status = filters.get('status') || 'open';
  const track = filters.get('track');

  const rows = (searches.data ?? []).filter(
    (search) =>
      (status === 'all' || (status === 'open' ? OPEN_SEARCH_STATUSES.includes(search.status) : search.status === status)) &&
      (!track || search.track === track) &&
      matchesQuery(q, search.title, search.companyName, search.location),
  );

  return (
    <>
      <PageHeader title="Searches" sub="Client engagements, from intake to placement.">
        <ExportButton part="searches" rows={rows} noun="searches" />
        <button type="button" className="btn btn-primary" onClick={() => setAdding(true)}>
          Open search
        </button>
      </PageHeader>

      <div className="filters">
        <SearchBox value={q} onChange={filters.set('q')} placeholder="Search role or company" />
        <div className="filter">
          <label htmlFor="search-status">Status</label>
          <select id="search-status" value={status} onChange={(event) => filters.set('status')(event.target.value === 'open' ? '' : event.target.value)}>
            <option value="open">Open</option>
            <option value="all">All</option>
            {options(SEARCH_STATUS).map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </div>
        <FilterSelect label="Track" value={track} onChange={filters.set('track')} options={options(TRACK)} />
      </div>

      {searches.error && <ErrorNote>{errorMessage(searches.error)}</ErrorNote>}
      {!searches.data && searches.loading && <Loading label="Loading searches" />}

      {searches.data && rows.length === 0 && (
        <Empty title={searches.data.length === 0 ? 'No searches yet' : 'No searches match these filters'}>
          {searches.data.length === 0 ? 'Open a search when a client engages the firm on a role.' : null}
        </Empty>
      )}

      {rows.length > 0 && (
        <div className="table-wrap">
          <table className="table">
            <caption className="visually-hidden">Searches</caption>
            <thead>
              <tr>
                <th scope="col">Role</th>
                <th scope="col">Status</th>
                <th scope="col">Terms</th>
                <th scope="col">Pipeline</th>
                <th scope="col">Lead</th>
                <th scope="col">Opened</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((search) => (
                <tr key={search.id} className="row-link" onClick={() => navigate(`/searches/${search.id}`)}>
                  <td>
                    <Link to={`/searches/${search.id}`} className="cell-title" onClick={(event) => event.stopPropagation()}>
                      {search.title}
                    </Link>
                    <span className="cell-sub">
                      {search.companyName}, {TRACK[search.track].toLowerCase()}
                    </span>
                  </td>
                  <td>
                    <Pill value={search.status} labels={SEARCH_STATUS} />
                  </td>
                  <td>
                    {ENGAGEMENT_TYPE[search.engagementType]}
                    {search.feePercent ? `, ${percent(search.feePercent)}` : ''}
                    {(search.baseMin || search.baseMax) && <span className="cell-sub">{salaryRange(search.baseMin, search.baseMax)}</span>}
                  </td>
                  <td>
                    <StageBar counts={search.stageCounts} />
                  </td>
                  <td>{search.ownerName ?? <span className="muted">Unassigned</span>}</td>
                  <td>{formatDate(search.openedDate)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {adding && (
        <Modal title="Open a search" onClose={() => setAdding(false)} wide>
          <SearchForm
            onCancel={() => setAdding(false)}
            onSaved={(saved) => {
              setAdding(false);
              toast(`Opened ${saved.title}`);
              navigate(`/searches/${saved.id}`);
            }}
          />
        </Modal>
      )}
    </>
  );
}
