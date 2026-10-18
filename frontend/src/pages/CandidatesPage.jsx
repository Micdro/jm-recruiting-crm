import { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { api, errorMessage } from '../api.js';
import { DueTag, Empty, ErrorNote, Loading, PageHeader, Pill, ShowMore } from '../components/Bits.jsx';
import { BulkBar, DuplicatesNote, RowBox, SelectAllBox } from '../components/Bulk.jsx';
import ExportButton from '../components/ExportButton.jsx';
import { FilterCheck, FilterSelect, SearchBox } from '../components/ListFilters.jsx';
import AddFromResume from '../components/AddFromResume.jsx';
import MergeDialog from '../components/MergeDialog.jsx';
import Modal from '../components/Modal.jsx';
import CandidateForm from '../forms/CandidateForm.jsx';
import { CANDIDATE_STATUS, EMPLOYMENT_TYPE, ROLE_AREA, TRACK, WORK_MODEL, options } from '../labels.js';
import { todayIso } from '../format.js';
import { matchesQuery, useFilterParams, useLoader, useTitle, useToast, useUsers } from '../hooks.js';
import { candidateOffLimits } from '../offLimits.js';
import { duplicateGroups, duplicateRows } from '../duplicates.js';
import { CANDIDATE_MERGE } from '../mergeFields.js';
import { useSelection } from '../selection.js';

const PAGE_SIZE = 200;
const NOUN = ['candidate', 'candidates'];

export default function CandidatesPage() {
  useTitle('Candidates');
  const navigate = useNavigate();
  const toast = useToast();
  const users = useUsers();
  const candidates = useLoader(() => api.candidates.list(), [], 'candidates');
  const companies = useLoader(() => api.companies.list(), [], 'companies');
  const filters = useFilterParams();
  const [adding, setAdding] = useState(false);
  const [merging, setMerging] = useState(null);
  const [limit, setLimit] = useState(PAGE_SIZE);

  const q = filters.get('q');
  const status = filters.get('status');
  const track = filters.get('track');
  const owner = filters.get('owner');
  const roleArea = filters.get('role');
  const regulated = filters.get('regulated') === 'true';
  const today = todayIso();

  const filtered = (candidates.data ?? []).filter(
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
  // Possible duplicates among the candidates the filters show; "Show them" lists just those, each group together.
  const dupes = duplicateGroups('candidates', filtered);
  const showDupes = filters.get('dupes') === 'true';
  const rows = showDupes ? duplicateRows(filtered, dupes) : filtered;
  const selection = useSelection(rows);

  return (
    <>
      <PageHeader title="Candidates" sub="Production AI engineers and technical leaders, judged by what they owned in production.">
        <ExportButton part="candidates" rows={rows} noun="candidates" />
        <button type="button" className="btn" onClick={() => setAdding('resume')}>
          Add from resume
        </button>
        <button type="button" className="btn btn-primary" onClick={() => setAdding('form')}>
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

      <DuplicatesNote
        count={dupes.size}
        noun={NOUN}
        showing={showDupes}
        onShow={() => filters.set('dupes')(true)}
        onHide={() => filters.set('dupes')('')}
      />
      <BulkBar selection={selection} noun={NOUN} busy={false} error="">
        <button
          type="button"
          className="btn btn-sm"
          disabled={selection.count !== 2}
          title={selection.count === 2 ? undefined : 'Tick two to merge them'}
          onClick={() => setMerging(selection.ids.map((id) => candidates.data.find((record) => record.id === id)))}
        >
          Merge
        </button>
      </BulkBar>

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
                <SelectAllBox selection={selection} total={rows.length} noun={NOUN} />
                <th scope="col">Name</th>
                <th scope="col">Track</th>
                <th scope="col">Status</th>
                <th scope="col" className="num">Active searches</th>
                <th scope="col">Next action</th>
                <th scope="col">Owner</th>
              </tr>
            </thead>
            <tbody>
              {rows.slice(0, limit).map((candidate, index, shown) => (
                <tr
                  key={candidate.id}
                  className={[
                    'row-link',
                    selection.has(candidate.id) && 'row-ticked',
                    showDupes && index > 0 && dupes.get(shown[index - 1].id) !== dupes.get(candidate.id) && 'dupe-group-start',
                  ]
                    .filter(Boolean)
                    .join(' ')}
                  onClick={() => navigate(`/candidates/${candidate.id}`)}
                >
                  <RowBox selection={selection} id={candidate.id} name={candidate.name} />
                  <td>
                    <Link to={`/candidates/${candidate.id}`} className="cell-title" onClick={(event) => event.stopPropagation()}>
                      {candidate.name}
                    </Link>
                    <span className="cell-sub">
                      {[candidate.currentTitle, candidate.currentCompany].filter(Boolean).join(', ')}
                      {candidateOffLimits(candidate, companies.data) && <span className="tag tag-warn">Off-limits</span>}
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

      {merging && (
        <Modal title="Merge duplicates" onClose={() => setMerging(null)} wide>
          <MergeDialog
            kind="candidates"
            records={merging}
            fields={CANDIDATE_MERGE.fields}
            moves={CANDIDATE_MERGE.moves}
            onCancel={() => setMerging(null)}
            onMerged={(result) => {
              setMerging(null);
              selection.clear();
              toast(`Merged ${result.mergedName} into ${result.keptName}.`);
              candidates.reload();
            }}
          />
        </Modal>
      )}

      {adding === 'resume' && (
        <Modal title="Add from resume" onClose={() => setAdding(false)} wide>
          <AddFromResume
            onCancel={() => setAdding(false)}
            onByHand={() => setAdding('form')}
            onAdded={(saved, attached) => {
              setAdding(false);
              toast(
                attached
                  ? `Added ${saved.name} with their resume`
                  : `Added ${saved.name}. The resume didn’t attach, so add it on their page.`,
              );
              navigate(`/candidates/${saved.id}`);
            }}
          />
        </Modal>
      )}

      {adding === 'form' && (
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
