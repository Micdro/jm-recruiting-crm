import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { api, errorMessage } from '../api.js';
import { useUndoableDelete } from '../undo.js';
import { ConfirmButton, Details, DueTag, Empty, ErrorNote, ExternalLink, Loading, Pill } from '../components/Bits.jsx';
import BlindProfileForm from '../components/BlindProfileForm.jsx';
import CandidateFiles from '../components/CandidateFiles.jsx';
import { SelectField } from '../components/Fields.jsx';
import LogActivityForm from '../components/LogActivityForm.jsx';
import Modal from '../components/Modal.jsx';
import Timeline from '../components/Timeline.jsx';
import CandidateForm from '../forms/CandidateForm.jsx';
import {
  ACTION_TYPE,
  CANDIDATE_SOURCE,
  CANDIDATE_STATUS,
  EMPLOYMENT_TYPE,
  EXPERIENCE,
  OPEN_SEARCH_STATUSES,
  ROLE_AREA,
  STAGE,
  TRACK,
  WORK_MODEL,
  options,
} from '../labels.js';
import { formatDate, href, todayIso } from '../format.js';
import { useLoader, useTitle, useToast } from '../hooks.js';
import { candidateOffLimits, offLimitsWarning } from '../offLimits.js';
import { interviewWhen } from '../interviews.js';

export default function CandidateDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const toast = useToast();
  const undoable = useUndoableDelete();
  const candidate = useLoader(() => api.candidates.get(id), [id], `candidate:${id}`);
  const pipeline = useLoader(() => api.candidates.pipeline(id), [id], `candidate:${id}:pipeline`);
  const history = useLoader(() => api.activities.list({ candidateId: id }), [id], `candidate:${id}:history`);
  const companies = useLoader(() => api.companies.list(), [], 'companies');
  const [editing, setEditing] = useState(false);
  const [profiling, setProfiling] = useState(false);
  const [error, setError] = useState('');

  const record = candidate.data;
  useTitle(record?.name ?? 'Candidate');

  if (candidate.error) {
    return (
      <>
        <Link to="/candidates" className="back">
          Candidates
        </Link>
        <ErrorNote>{errorMessage(candidate.error)}</ErrorNote>
      </>
    );
  }
  if (!record) return <Loading label="Loading candidate" />;

  const offLimits = candidateOffLimits(record, companies.data);

  const refresh = (updated) => {
    if (updated) candidate.setData(updated);
    history.reload();
    pipeline.reload();
  };

  return (
    <>
      <Link to="/candidates" className="back">
        Candidates
      </Link>
      <div className="page-header">
        <div className="page-heading">
          <h1>{record.name}</h1>
          <div className="page-sub page-sub-row">
            <Pill value={record.status} labels={CANDIDATE_STATUS} />
            {(record.currentTitle || record.currentCompany) && (
              <span>{[record.currentTitle, record.currentCompany].filter(Boolean).join(' at ')}</span>
            )}
            {record.regulatedExperience && <span className="tag">Regulated experience</span>}
          </div>
        </div>
        <div className="page-actions">
          <button type="button" className="btn" onClick={() => setProfiling(true)}>
            Blind profile
          </button>
          <button type="button" className="btn" onClick={() => setEditing(true)}>
            Edit
          </button>
          <ConfirmButton
            onConfirm={async () => {
              try {
                await api.candidates.remove(record.id);
                undoable('CANDIDATE', record.id, `Deleted ${record.name}`);
                navigate('/candidates');
              } catch (err) {
                setError(errorMessage(err));
              }
            }}
          />
        </div>
      </div>
      <ErrorNote>{error}</ErrorNote>
      {offLimits && (
        <p className="warn-note" role="note">
          {offLimitsWarning(record.name, offLimits)}{' '}
          <Link to={`/companies/${offLimits.id}`}>Open {offLimits.name}</Link>
        </p>
      )}

      <div className="detail-grid">
        <div className="detail-main">
          <section className="panel evidence" aria-labelledby="evidence-heading">
            <div className="panel-head">
              <h2 id="evidence-heading">Production evidence</h2>
            </div>
            {record.productionEvidence ? (
              <p className="notes">{record.productionEvidence}</p>
            ) : (
              <p className="muted">
                Not written yet. Record the systems they owned in production, the decisions they made and the incidents they
                handled.{' '}
                <button type="button" className="link-button" onClick={() => setEditing(true)}>
                  Add evidence
                </button>
              </p>
            )}
          </section>

          <CandidateFiles candidateId={record.id} />

          <CandidateSearches candidate={record} entries={pipeline.data} offLimits={offLimits} onChanged={() => refresh()} />

          <section className="panel" aria-labelledby="candidate-log">
            <div className="panel-head">
              <h2 id="candidate-log">Follow-up</h2>
              <span className="panel-meta">
                Next: <DueTag date={record.nextActionDate} today={todayIso()} />
                {record.nextActionDate && record.nextActionType && ` ${ACTION_TYPE[record.nextActionType]}`}
              </span>
            </div>
            <LogActivityForm kind="candidate" record={record} searches={pipeline.data ?? []} onLogged={refresh} />
          </section>

          <section className="panel">
            <div className="panel-head">
              <h2>Details</h2>
            </div>
            <Details
              items={[
                { label: 'Email', value: record.email && <a href={`mailto:${record.email}`}>{record.email}</a> },
                { label: 'Phone', value: record.phone && <a href={`tel:${record.phone}`}>{record.phone}</a> },
                { label: 'LinkedIn', value: record.linkedinUrl && <ExternalLink href={href(record.linkedinUrl)}>Profile</ExternalLink> },
                { label: 'Resume link', value: record.resumeUrl && <ExternalLink href={href(record.resumeUrl)}>Open resume</ExternalLink> },
                { label: 'Location', value: record.location },
                { label: 'Track', value: TRACK[record.track] },
                { label: 'Roles', value: (record.roleAreas ?? []).map((area) => ROLE_AREA[area]).join(', ') },
                { label: 'Experience', value: EXPERIENCE[record.experience] },
                { label: 'Works', value: WORK_MODEL[record.workPreference] },
                { label: 'Employment', value: EMPLOYMENT_TYPE[record.employmentType] },
                { label: 'Relocation', value: record.willingToRelocate ? 'Willing to relocate' : null },
                { label: 'Compensation', value: record.compExpectation },
                { label: 'Source', value: CANDIDATE_SOURCE[record.source] },
                { label: 'Last contacted', value: record.lastContactedDate && formatDate(record.lastContactedDate) },
                { label: 'Owner', value: record.ownerName ?? 'Unassigned' },
              ]}
            />
            {record.notes && <p className="notes">{record.notes}</p>}
          </section>
        </div>

        <section className="panel detail-side" aria-labelledby="candidate-history">
          <div className="panel-head">
            <h2 id="candidate-history">History</h2>
          </div>
          <Timeline activities={history.data} loading={history.loading} show={['search']} />
        </section>
      </div>

      {profiling && (
        <Modal title="Blind profile for a client" onClose={() => setProfiling(false)} wide>
          <BlindProfileForm
            candidate={record}
            entries={pipeline.data}
            onCancel={() => setProfiling(false)}
            onDone={(reference) => {
              setProfiling(false);
              toast(`Blind profile ${reference} downloaded. It's noted in the history.`);
              history.reload();
            }}
          />
        </Modal>
      )}

      {editing && (
        <Modal title={`Edit ${record.name}`} onClose={() => setEditing(false)} wide>
          <CandidateForm
            candidate={record}
            onCancel={() => setEditing(false)}
            onSaved={(saved) => {
              setEditing(false);
              toast('Candidate saved');
              refresh(saved);
            }}
          />
        </Modal>
      )}
    </>
  );
}

function CandidateSearches({ candidate, entries, offLimits, onChanged }) {
  const toast = useToast();
  const searches = useLoader(() => api.searches.list(), [], 'searches');
  const [searchId, setSearchId] = useState('');
  const [error, setError] = useState('');

  const inSearch = new Set((entries ?? []).map((entry) => entry.searchId));
  const openSearches = (searches.data ?? [])
    .filter((search) => OPEN_SEARCH_STATUSES.includes(search.status) && !inSearch.has(search.id))
    .map((search) => ({ value: String(search.id), label: `${search.title}, ${search.companyName}` }));

  return (
    <section className="panel" aria-labelledby="candidate-searches">
      <div className="panel-head">
        <h2 id="candidate-searches">Searches</h2>
      </div>
      {entries && entries.length === 0 && <Empty title="Not in any search yet" />}
      {entries && entries.length > 0 && (
        <ul className="people">
          {entries.map((entry) => (
            <li key={entry.id}>
              <div>
                <Link to={`/searches/${entry.searchId}`} className="cell-title">
                  {entry.searchTitle}
                </Link>
                <span className="cell-sub">{entry.companyName}</span>
                {entry.interviewDate && (
                  <span className={entry.debriefDue ? 'cell-sub interview-when interview-over' : 'cell-sub interview-when'}>
                    Interview {interviewWhen(entry, todayIso())}
                    {entry.debriefDue ? ', debrief due' : ''}
                  </span>
                )}
              </div>
              <label className="visually-hidden" htmlFor={`cand-stage-${entry.id}`}>
                Stage in {entry.searchTitle}
              </label>
              <select
                id={`cand-stage-${entry.id}`}
                className="inline-select"
                value={entry.stage}
                onChange={async (event) => {
                  setError('');
                  try {
                    await api.pipeline.update(entry.id, { stage: event.target.value, rationale: entry.rationale });
                    toast(`Moved to ${STAGE[event.target.value]}`);
                    onChanged();
                  } catch (err) {
                    setError(errorMessage(err));
                  }
                }}
              >
                {options(STAGE).map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
              {entry.placed && <span className="card-placed">Placed</span>}
            </li>
          ))}
        </ul>
      )}

      {openSearches.length > 0 && (
        <form
          className="inline-form"
          onSubmit={async (event) => {
            event.preventDefault();
            setError('');
            try {
              await api.pipeline.add({ searchId: Number(searchId), candidateId: candidate.id, stage: 'SOURCED' });
              toast('Added to search');
              setSearchId('');
              onChanged();
            } catch (err) {
              setError(errorMessage(err));
            }
          }}
        >
          <SelectField
            label="Add to an open search"
            value={searchId}
            onChange={setSearchId}
            options={openSearches}
            placeholder="Choose a search"
            required
          />
          <button type="submit" className="btn btn-sm" disabled={!searchId}>
            {offLimits ? 'Add anyway' : 'Add'}
          </button>
        </form>
      )}
      <ErrorNote>{error}</ErrorNote>
    </section>
  );
}
