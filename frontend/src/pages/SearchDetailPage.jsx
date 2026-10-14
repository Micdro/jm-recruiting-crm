import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { api, errorMessage } from '../api.js';
import { useUndoableDelete } from '../undo.js';
import { ConfirmButton, Empty, ErrorNote, Loading, Pill } from '../components/Bits.jsx';
import { SelectField, TextArea } from '../components/Fields.jsx';
import Modal from '../components/Modal.jsx';
import NoteForm from '../components/NoteForm.jsx';
import SearchMatches from '../components/SearchMatches.jsx';
import Timeline from '../components/Timeline.jsx';
import CandidateForm from '../forms/CandidateForm.jsx';
import PlacementForm from '../forms/PlacementForm.jsx';
import SearchForm from '../forms/SearchForm.jsx';
import {
  BOARD_STAGES,
  CLOSED_STAGES,
  ENGAGEMENT_TYPE,
  SEARCH_STATUS,
  SHORTLIST_STAGES,
  STAGE,
  TIMELINE,
  TRACK,
  WORK_MODEL,
  options,
} from '../labels.js';
import { formatDate, formatTimestamp, percent, salaryRange, todayIso } from '../format.js';
import { useLoader, useTitle, useToast } from '../hooks.js';

const TABS = [
  ['pipeline', 'Pipeline'],
  ['matches', 'Matches'],
  ['brief', 'Brief'],
  ['shortlist', 'Shortlist'],
  ['history', 'History'],
];

const STAGE_OPTIONS = options(STAGE);

export default function SearchDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const toast = useToast();
  const undoable = useUndoableDelete();
  const search = useLoader(() => api.searches.get(id), [id], `search:${id}`);
  const pipeline = useLoader(() => api.searches.pipeline(id), [id], `search:${id}:pipeline`);
  const history = useLoader(() => api.activities.list({ searchId: id }), [id], `search:${id}:history`);
  const [tab, setTab] = useState('pipeline');
  const [modal, setModal] = useState(null);
  const [error, setError] = useState('');

  const record = search.data;
  useTitle(record?.title ?? 'Search');

  if (search.error) {
    return (
      <>
        <Link to="/searches" className="back">
          Searches
        </Link>
        <ErrorNote>{errorMessage(search.error)}</ErrorNote>
      </>
    );
  }
  if (!record) return <Loading label="Loading search" />;

  function refreshAll() {
    search.reload();
    pipeline.reload();
    history.reload();
  }

  async function moveStage(entry, stage, rationale = entry.rationale) {
    setError('');
    try {
      await api.pipeline.update(entry.id, { stage, rationale });
      toast(`${entry.candidateName} moved to ${STAGE[stage]}`);
      refreshAll();
      if (stage === 'PLACED' && !entry.placed) {
        setModal({ kind: 'placement', entry });
      }
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  function requestStage(entry, stage) {
    if (SHORTLIST_STAGES.includes(stage) && !entry.rationale) {
      setModal({ kind: 'rationale', entry, stage });
    } else {
      moveStage(entry, stage);
    }
  }

  return (
    <>
      <Link to="/searches" className="back">
        Searches
      </Link>
      <div className="page-header">
        <div className="page-heading">
          <h1>{record.title}</h1>
          <div className="page-sub page-sub-row">
            <Link to={`/companies/${record.companyId}`}>{record.companyName}</Link>
            <Pill value={record.status} labels={SEARCH_STATUS} />
            <span>{TRACK[record.track]}</span>
          </div>
        </div>
        <div className="page-actions">
          <button type="button" className="btn" onClick={() => setModal({ kind: 'edit' })}>
            Edit
          </button>
          <ConfirmButton
            onConfirm={async () => {
              try {
                await api.searches.remove(record.id);
                undoable('SEARCH', record.id, `Deleted ${record.title}`);
                navigate('/searches');
              } catch (err) {
                setError(errorMessage(err));
              }
            }}
          />
        </div>
      </div>

      <dl className="fact-strip">
        <div>
          <dt>Terms</dt>
          <dd>
            {ENGAGEMENT_TYPE[record.engagementType]}
            {record.feePercent ? `, ${percent(record.feePercent)} fee` : ''}
          </dd>
        </div>
        <div>
          <dt>Base</dt>
          <dd>{salaryRange(record.baseMin, record.baseMax) || 'Not set'}</dd>
        </div>
        <div>
          <dt>Where</dt>
          <dd>{[record.location, WORK_MODEL[record.workModel]].filter(Boolean).join(', ') || 'Not set'}</dd>
        </div>
        <div>
          <dt>Timeline</dt>
          <dd>{TIMELINE[record.timeline] ?? 'Not set'}</dd>
        </div>
        <div>
          <dt>Hiring manager</dt>
          <dd>
            {record.hiringManagerId ? (
              <Link to={`/contacts/${record.hiringManagerId}`}>{record.hiringManagerName}</Link>
            ) : (
              'Not set'
            )}
          </dd>
        </div>
        <div>
          <dt>Lead</dt>
          <dd>{record.ownerName ?? 'Unassigned'}</dd>
        </div>
        <div>
          <dt>Opened</dt>
          <dd>{formatDate(record.openedDate)}</dd>
        </div>
      </dl>

      <ErrorNote>{error}</ErrorNote>

      <div className="tabs" role="tablist" aria-label="Search sections">
        {TABS.map(([value, label]) => (
          <button
            key={value}
            type="button"
            role="tab"
            id={`tab-${value}`}
            aria-selected={tab === value}
            aria-controls={`panel-${value}`}
            className={tab === value ? 'tab tab-on' : 'tab'}
            onClick={() => setTab(value)}
          >
            {label}
          </button>
        ))}
      </div>

      <div role="tabpanel" id={`panel-${tab}`} aria-labelledby={`tab-${tab}`} className="tab-panel">
        {tab === 'pipeline' && (
          <PipelineTab
            search={record}
            entries={pipeline.data}
            loading={pipeline.loading}
            onAdded={refreshAll}
            onStage={requestStage}
            onEditRationale={(entry) => setModal({ kind: 'rationale', entry, stage: entry.stage })}
            onPlace={(entry) => setModal({ kind: 'placement', entry })}
            onRemove={async (entry) => {
              try {
                await api.pipeline.remove(entry.id);
                toast(`Removed ${entry.candidateName} from this search`);
                refreshAll();
              } catch (err) {
                setError(errorMessage(err));
              }
            }}
            onNewCandidate={() => setModal({ kind: 'candidate' })}
          />
        )}
        {tab === 'matches' && (
          <SearchMatches
            search={record}
            entries={pipeline.data}
            onAdded={refreshAll}
            onEditBrief={() => setModal({ kind: 'edit' })}
          />
        )}
        {tab === 'brief' && <BriefTab search={record} onEdit={() => setModal({ kind: 'edit' })} />}
        {tab === 'shortlist' && <ShortlistTab search={record} entries={pipeline.data ?? []} />}
        {tab === 'history' && (
          <section className="panel">
            <NoteForm
              onSave={async (body) => {
                await api.activities.note({ ...body, searchId: record.id });
                history.reload();
              }}
            />
            <Timeline activities={history.data} loading={history.loading} show={['candidate']} />
          </section>
        )}
      </div>

      {modal?.kind === 'edit' && (
        <Modal title={`Edit ${record.title}`} onClose={() => setModal(null)} wide>
          <SearchForm
            search={record}
            onCancel={() => setModal(null)}
            onSaved={() => {
              setModal(null);
              toast('Search saved');
              refreshAll();
            }}
          />
        </Modal>
      )}

      {modal?.kind === 'rationale' && (
        <Modal title={`Why ${modal.entry.candidateName}?`} onClose={() => setModal(null)}>
          <RationaleForm
            entry={modal.entry}
            stage={modal.stage}
            onCancel={() => setModal(null)}
            onSave={async (rationale) => {
              setModal(null);
              await moveStage(modal.entry, modal.stage, rationale);
            }}
          />
        </Modal>
      )}

      {modal?.kind === 'placement' && (
        <Modal title={`Record placement: ${modal.entry.candidateName}`} onClose={() => setModal(null)}>
          <PlacementForm
            placement={{ searchId: record.id, candidateId: modal.entry.candidateId, candidateName: modal.entry.candidateName }}
            onCancel={() => setModal(null)}
            onSaved={() => {
              setModal(null);
              toast('Placement recorded');
              refreshAll();
            }}
          />
        </Modal>
      )}

      {modal?.kind === 'candidate' && (
        <Modal title="New candidate for this search" onClose={() => setModal(null)} wide>
          <CandidateForm
            candidate={{ track: record.track }}
            onCancel={() => setModal(null)}
            onSaved={async (saved) => {
              setModal(null);
              try {
                await api.pipeline.add({ searchId: record.id, candidateId: saved.id, stage: 'SOURCED' });
                toast(`Added ${saved.name} to this search`);
              } catch (err) {
                setError(errorMessage(err));
              }
              refreshAll();
            }}
          />
        </Modal>
      )}
    </>
  );
}

function PipelineTab({ search, entries, loading, onAdded, onStage, onEditRationale, onPlace, onRemove, onNewCandidate }) {
  if (!entries && loading) return <Loading label="Loading pipeline" />;
  const list = entries ?? [];
  const closed = list.filter((entry) => CLOSED_STAGES.includes(entry.stage));

  return (
    <>
      <AddToSearch search={search} entries={list} onAdded={onAdded} onNewCandidate={onNewCandidate} />

      {list.length === 0 ? (
        <Empty title="No candidates in this search yet">Add people you’ve mapped in the market, then move them through assessment.</Empty>
      ) : (
        <div className="board" aria-label="Pipeline by stage">
          {BOARD_STAGES.map((stage) => {
            const column = list.filter((entry) => entry.stage === stage);
            return (
              <section key={stage} className="board-col" aria-label={`${STAGE[stage]}, ${column.length}`}>
                <h3 className="board-head">
                  {STAGE[stage]} <span className="count">{column.length}</span>
                </h3>
                {column.map((entry) => (
                  <PipelineCard
                    key={entry.id}
                    entry={entry}
                    onStage={onStage}
                    onEditRationale={onEditRationale}
                    onPlace={onPlace}
                    onRemove={onRemove}
                  />
                ))}
              </section>
            );
          })}
        </div>
      )}

      {closed.length > 0 && (
        <section className="panel closed-list">
          <h3 className="panel-subhead">No longer in process</h3>
          <ul className="plain-list">
            {closed.map((entry) => (
              <li key={entry.id}>
                <Link to={`/candidates/${entry.candidateId}`}>{entry.candidateName}</Link>, {STAGE[entry.stage].toLowerCase()}{' '}
                {formatTimestamp(entry.stageChangedAt).toLowerCase()}
                <select
                  className="inline-select"
                  aria-label={`Move ${entry.candidateName}`}
                  value={entry.stage}
                  onChange={(event) => onStage(entry, event.target.value)}
                >
                  {STAGE_OPTIONS.map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
                </select>
              </li>
            ))}
          </ul>
        </section>
      )}
    </>
  );
}

function PipelineCard({ entry, onStage, onEditRationale, onPlace, onRemove }) {
  const needsRationale = SHORTLIST_STAGES.includes(entry.stage) && !entry.rationale;

  return (
    <article className="card" data-testid="pipeline-card">
      <Link to={`/candidates/${entry.candidateId}`} className="card-title">
        {entry.candidateName}
      </Link>
      {(entry.candidateTitle || entry.candidateCompany) && (
        <p className="card-sub">{[entry.candidateTitle, entry.candidateCompany].filter(Boolean).join(', ')}</p>
      )}
      {entry.rationale ? (
        <button type="button" className="card-rationale" onClick={() => onEditRationale(entry)}>
          {entry.rationale}
        </button>
      ) : (
        <button
          type="button"
          className={`link-button card-add-rationale ${needsRationale ? 'card-needs' : ''}`}
          onClick={() => onEditRationale(entry)}
        >
          {needsRationale ? 'Add the rationale for the client' : 'Add rationale'}
        </button>
      )}
      <div className="card-foot">
        <label className="visually-hidden" htmlFor={`stage-${entry.id}`}>
          Stage for {entry.candidateName}
        </label>
        <select
          id={`stage-${entry.id}`}
          className="inline-select"
          value={entry.stage}
          onChange={(event) => onStage(entry, event.target.value)}
        >
          {STAGE_OPTIONS.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
        {(entry.stage === 'OFFER' || entry.stage === 'PLACED') && !entry.placed && (
          <button type="button" className="btn btn-sm btn-primary" onClick={() => onPlace(entry)}>
            Record placement
          </button>
        )}
        {entry.placed && <span className="card-placed">Placement recorded</span>}
        <ConfirmButton
          label="Remove"
          confirmLabel="Remove"
          className="link-button card-remove"
          onConfirm={() => onRemove(entry)}
        />
      </div>
    </article>
  );
}

function AddToSearch({ search, entries, onAdded, onNewCandidate }) {
  const toast = useToast();
  const candidates = useLoader(() => api.candidates.list(), [], 'candidates');
  const [candidateId, setCandidateId] = useState('');
  const [stage, setStage] = useState('SOURCED');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const inSearch = new Set(entries.map((entry) => entry.candidateId));
  const available = (candidates.data ?? [])
    .filter((candidate) => !inSearch.has(candidate.id) && candidate.status !== 'DO_NOT_CONTACT')
    .map((candidate) => ({
      value: String(candidate.id),
      label: [candidate.name, candidate.currentTitle].filter(Boolean).join(', '),
    }));

  return (
    <form
      className="add-to-search"
      onSubmit={async (event) => {
        event.preventDefault();
        setBusy(true);
        setError('');
        try {
          const entry = await api.pipeline.add({ searchId: search.id, candidateId: Number(candidateId), stage });
          toast(`Added ${entry.candidateName} as ${STAGE[stage].toLowerCase()}`);
          setCandidateId('');
          onAdded();
        } catch (err) {
          setError(errorMessage(err));
        } finally {
          setBusy(false);
        }
      }}
    >
      <SelectField
        label="Add a candidate"
        value={candidateId}
        onChange={setCandidateId}
        options={available}
        placeholder={candidates.loading ? 'Loading candidates…' : available.length ? 'Choose a candidate' : 'Everyone is already here'}
        required
      />
      <SelectField label="At stage" value={stage} onChange={setStage} options={STAGE_OPTIONS} required />
      <div className="add-to-search-actions">
        <button type="submit" className="btn btn-primary" disabled={busy || !candidateId}>
          Add to search
        </button>
        <button type="button" className="btn" onClick={onNewCandidate}>
          New candidate
        </button>
      </div>
      <ErrorNote>{error}</ErrorNote>
    </form>
  );
}

function RationaleForm({ entry, stage, onSave, onCancel }) {
  const [rationale, setRationale] = useState(entry.rationale ?? '');

  return (
    <form
      className="form"
      onSubmit={(event) => {
        event.preventDefault();
        onSave(rationale.trim());
      }}
    >
      <p className="form-intro">
        {SHORTLIST_STAGES.includes(stage)
          ? `The client sees this on the shortlist. Say why ${entry.candidateName.split(' ')[0]} fits: what they owned in production and how it maps to the brief.`
          : 'Why this candidate fits the search.'}
      </p>
      <TextArea label="Rationale" value={rationale} onChange={setRationale} rows={5} maxLength={2000} required autoFocus />
      <div className="form-actions">
        <button type="button" className="btn" onClick={onCancel}>
          Cancel
        </button>
        <button type="submit" className="btn btn-primary" disabled={!rationale.trim()}>
          {stage !== entry.stage ? `Save and move to ${STAGE[stage]}` : 'Save rationale'}
        </button>
      </div>
    </form>
  );
}

function BriefTab({ search, onEdit }) {
  const sections = [
    ['Production outcome', search.productionOutcome],
    ['Technical scope', search.technicalScope],
    ['Accountability', search.accountability],
    ['Regulatory constraints', search.regulatoryConstraints],
    ['Notes', search.notes],
  ];
  const filled = sections.filter(([, value]) => value);

  if (filled.length === 0) {
    return (
      <Empty title="The brief is empty">
        Capture the production outcome, scope, accountability and regulatory constraints from the intake call.{' '}
        <button type="button" className="link-button" onClick={onEdit}>
          Write the brief
        </button>
      </Empty>
    );
  }

  return (
    <section className="panel brief">
      {sections.map(([label, value]) => (
        <div key={label} className="brief-section">
          <h3>{label}</h3>
          {value ? <p className="notes">{value}</p> : <p className="muted">Not written yet.</p>}
        </div>
      ))}
    </section>
  );
}

function ShortlistTab({ search, entries }) {
  const slate = entries.filter((entry) => SHORTLIST_STAGES.includes(entry.stage));
  const missing = slate.filter((entry) => !entry.rationale).length;

  return (
    <>
      <div className="shortlist-tools">
        <p>
          The focused shortlist the client receives: everyone shortlisted or further along, with the rationale for each.
          {missing > 0 && ` ${missing} still need a rationale.`}
        </p>
        <button type="button" className="btn" onClick={() => window.print()} disabled={slate.length === 0}>
          Print or save as PDF
        </button>
      </div>

      {slate.length === 0 ? (
        <Empty title="Nobody is shortlisted yet">Move candidates to Shortlisted on the pipeline when the evidence holds up.</Empty>
      ) : (
        <article className="slate">
          <header className="slate-head">
            <p className="slate-firm">Jane Michael AI Talent Search</p>
            <h2>
              {search.title}, {search.companyName}
            </h2>
            <p className="slate-date">Shortlist prepared {formatDate(todayIso())}</p>
          </header>
          <ol className="slate-list">
            {slate.map((entry) => (
              <li key={entry.id} className="slate-item">
                <h3>{entry.candidateName}</h3>
                <p className="slate-role">
                  {[entry.candidateTitle, entry.candidateCompany, entry.candidateLocation].filter(Boolean).join(', ')}
                </p>
                <h4>Why this candidate</h4>
                <p>{entry.rationale || <span className="muted">Rationale not written yet.</span>}</p>
                {entry.candidateEvidence && (
                  <>
                    <h4>Production evidence</h4>
                    <p>{entry.candidateEvidence}</p>
                  </>
                )}
              </li>
            ))}
          </ol>
        </article>
      )}
    </>
  );
}
