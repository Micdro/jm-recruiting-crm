import { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { api, errorMessage } from '../api.js';
import { DueTag, Empty, ErrorNote, Loading, PageHeader, Pill, ShowMore } from '../components/Bits.jsx';
import { FilterCheck, FilterSelect, SearchBox } from '../components/ListFilters.jsx';
import Modal from '../components/Modal.jsx';
import CandidateForm from '../forms/CandidateForm.jsx';
import { CANDIDATE_STATUS, EMPLOYMENT_TYPE, ROLE_AREA, TRACK, WORK_MODEL, options } from '../labels.js';
import { todayIso } from '../format.js';
import { matchesQuery, useFilterParams, useLoader, useTitle, useToast, useUsers } from '../hooks.js';


const PAGE_SIZE = 200;
export default function CandidatesPage() {
  useTitle('Candidates');
  const navigate = useNavigate();
  const toast = useToast();
  const users = useUsers();
  const candidates = useLoader(() => api.candidates.list(), [], 'candidates');
  const filters = useFilterParams();
  const [adding, setAdding] = useState(false);
  const [limit, setLimit] = useState(PAGE_SIZE);

  const q = filters.get('q');
  const status = filters.get('status');
  const track = filters.get('track');
  const owner = filters.get('owner');
  const roleArea = filters.get('role');
  const regulated = filters.get('regulated') === 'true';
  const today = todayIso();

  const rows = (candidates.data ?? []).filter(
    (candidate) =>
      (!status || candidate.status === status) &&
      (!track || candidate.track === track) &&
      (!owner || String(candidate.ownerId) === owner) &&
      (!roleArea || (candidate.roleAreas ?? []).includes(roleArea)) &&
      (!regulated || candidate.regulatedExperience) &&
      matchesQuery(
        q,
        candidate.name,
        candidate.email,
        candidate.currentTitle,
        candidate.currentCompany,
        candidate.location,
        candidate.productionEvidence,
      ),
  );

  return (
    <>
      <PageHeader title="Candidates" sub="Production AI engineers and technical leaders, judged by what they owned in production.">
        <button type="button" className="btn btn-primary" onClick={() => setAdding(true)}>
          Add candidate
        </button>
      </PageHeader>

      <div className="filters">
        <SearchBox value={q} onChange={filters.set('q')} placeholder="Search name, title, company or evidence" />
        <FilterSelect label="Status" value={status} onChange={filters.set('status')} options={options(CANDIDATE_STATUS)} />
        <FilterSelect label="Track" value={track} onChange={filters.set('track')} options={options(TRACK)} />
        <FilterSelect label="Role" value={roleArea} onChange={filters.set('role')} options={options(ROLE_AREA)} />
        <FilterSelect
          label="Owner"
          value={owner}
          onChange={filters.set('owner')}
          options={users.map((member) => ({ value: String(member.id), label: member.displayName }))}
          allLabel="Everyone"
        />
        <FilterCheck label="Regulated experience" checked={regulated} onChange={filters.set('regulated')} />
      </div>

      {candidates.error && <ErrorNote>{errorMessage(candidates.error)}</ErrorNote>}
      {!candidates.data && candidates.loading && <Loading label="Loading candidates" />}

      {candidates.data && rows.length === 0 && (
        <Empty title={candidates.data.length === 0 ? 'No candidates yet' : 'No candidates match these filters'}>
          {candidates.data.length === 0 ? (
            <>
              Add one, or <Link to="/import">import a list</Link>.
            </>
          ) : null}
        </Empty>
      )}

      {rows.length > 0 && (
        <div className="table-wrap">
          <table className="table">
            <caption className="visually-hidden">Candidates</caption>
            <thead>
              <tr>
                <th scope="col">Name</th>
                <th scope="col">Track</th>
                <th scope="col">Status</th>
                <th scope="col" className="num">Active searches</th>
                <th scope="col">Next action</th>
                <th scope="col">Owner</th>
              </tr>
            </thead>
            <tbody>
              {rows.slice(0, limit).map((candidate) => (
                <tr key={candidate.id} className="row-link" onClick={() => navigate(`/candidates/${candidate.id}`)}>
                  <td>
                    <Link to={`/candidates/${candidate.id}`} className="cell-title" onClick={(event) => event.stopPropagation()}>
                      {candidate.name}
                    </Link>
                    <span className="cell-sub">
                      {[candidate.currentTitle, candidate.currentCompany].filter(Boolean).join(', ')}
                      {candidate.regulatedExperience && <span className="tag">Regulated</span>}
                      {(candidate.roleAreas ?? []).map((area) => (
                        <span key={area} className="tag">
                          {ROLE_AREA[area]}
                        </span>
                      ))}
                      {candidate.workPreference && <span className="tag">{WORK_MODEL[candidate.workPreference]}</span>}
                      {candidate.employmentType === 'CONTRACT' && <span className="tag">{EMPLOYMENT_TYPE.CONTRACT}</span>}
                    </span>
                  </td>
                  <td>{TRACK[candidate.track] ?? <span className="muted">Not set</span>}</td>
                  <td>
                    <Pill value={candidate.status} labels={CANDIDATE_STATUS} />
                  </td>
                  <td className="num">{candidate.activeSearchCount}</td>
                  <td>
                    {candidate.nextActionDate ? <DueTag date={candidate.nextActionDate} today={today} /> : <span className="muted">None</span>}
                  </td>
                  <td>{candidate.ownerName ?? <span className="muted">Unassigned</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="table-count">
            Showing {Math.min(limit, rows.length)} of {rows.length}
            {rows.length < candidates.data.length ? `, filtered from ${candidates.data.length}` : ''}
            <ShowMore shown={limit} total={rows.length} onMore={() => setLimit((value) => value + PAGE_SIZE)} />
          </p>
        </div>
      )}

      {adding && (
        <Modal title="Add candidate" onClose={() => setAdding(false)} wide>
          <CandidateForm
            onCancel={() => setAdding(false)}
            onSaved={(saved) => {
              setAdding(false);
              toast(`Added ${saved.name}`);
              navigate(`/candidates/${saved.id}`);
            }}
          />
        </Modal>
      )}
    </>
  );
}
