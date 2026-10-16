import { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { api, errorMessage } from '../api.js';
import { ConfirmButton, DueTag, Empty, ErrorNote, Loading, PageHeader, Pill, ShowMore } from '../components/Bits.jsx';
import { BulkBar, DuplicatesNote, RowBox, SelectAllBox, SkippedNote } from '../components/Bulk.jsx';
import MergeDialog from '../components/MergeDialog.jsx';
import { ownerChoices, useBulkAction } from '../bulk.js';
import ChoiceMenu from '../components/ChoiceMenu.jsx';
import ExportButton from '../components/ExportButton.jsx';
import { FilterCheck, FilterSelect, SearchBox } from '../components/ListFilters.jsx';
import Modal from '../components/Modal.jsx';
import CompanyForm from '../forms/CompanyForm.jsx';
import { COMPANY_STATUS, INDUSTRY, PARKED_COMPANY_STAGES, PRIORITY, SEGMENT, options } from '../labels.js';
import { plural, todayIso } from '../format.js';
import { matchesQuery, useAuth, useFilterParams, useLoader, useTitle, useToast, useUsers } from '../hooks.js';
import { offLimitsLabel } from '../offLimits.js';
import { duplicateGroups, duplicateRows } from '../duplicates.js';
import { COMPANY_MERGE } from '../mergeFields.js';
import { useSelection } from '../selection.js';

const PAGE_SIZE = 200;
const NOUN = ['company', 'companies'];
// AI found is set by the prospect finder, so it isn't offered as a stage to move companies to.
const STAGE_CHOICES = options(COMPANY_STATUS).filter((stage) => stage.value !== 'AI_FOUND');

export default function CompaniesPage() {
  useTitle('Companies');
  const navigate = useNavigate();
  const toast = useToast();
  const companies = useLoader(() => api.companies.list(), [], 'companies');
  const users = useUsers();
  const { user: me } = useAuth();
  const filters = useFilterParams();
  const [adding, setAdding] = useState(false);
  const [merging, setMerging] = useState(null);
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

  const waiting = (companies.data ?? []).filter((company) => company.status === 'AI_FOUND').length;
  const filtered = (companies.data ?? []).filter(
    (company) =>
      (!status || company.status === status) &&
      (showParked || !PARKED_COMPANY_STAGES.includes(company.status)) &&
      // Companies the prospect finder found wait on the AI Found page until someone accepts them.
      (status === 'AI_FOUND' || company.status !== 'AI_FOUND') &&
      (!priority || company.priority === priority) &&
      (!industry || company.industry === industry) &&
      (!segment || (company.segments ?? []).includes(segment)) &&
      (!owner || String(company.ownerId) === owner) &&
      (!due || (company.followUpDate && company.followUpDate <= today)) &&
      matchesQuery(q, company.name, company.focus, company.location, company.website),
  );
  // Possible duplicates among the companies the filters show; "Show them" lists just those, each group together.
  const dupes = duplicateGroups('companies', filtered);
  const showDupes = filters.get('dupes') === 'true';
  const rows = showDupes ? duplicateRows(filtered, dupes) : filtered;
  const selection = useSelection(rows);
  const bulk = useBulkAction('companies', NOUN, selection, companies.reload);
  const owners = ownerChoices(users, me);

  return (
    <>
      <PageHeader title="Companies" sub="AI-native vendors selling into banks, insurers and other regulated institutions.">
        <ExportButton part="companies" rows={rows} noun="companies" />
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

      <SkippedNote skipped={bulk.skipped} noun={NOUN} onDismiss={bulk.dismiss} />
      <DuplicatesNote
        count={dupes.size}
        noun={NOUN}
        showing={showDupes}
        onShow={() => filters.set('dupes')(true)}
        onHide={() => filters.set('dupes')('')}
      />
      <BulkBar selection={selection} noun={NOUN} busy={bulk.busy} error={bulk.error}>
        <button
          type="button"
          className="btn btn-sm"
          disabled={bulk.busy || selection.count !== 2}
          title={selection.count === 2 ? undefined : 'Tick two to merge them'}
          onClick={() => setMerging(selection.ids.map((id) => companies.data.find((record) => record.id === id)))}
        >
          Merge
        </button>
        <ChoiceMenu
          label="Owner"
          buttonLabel="Change owner"
          groupLabel="Change owner to"
          buttonClass="btn btn-sm"
          disabled={bulk.busy}
          choices={owners}
          onPick={(ownerId) => {
            const name = owners.find((choice) => choice.value === ownerId)?.label.replace(' (you)', '');
            bulk.run({ action: 'OWNER', ownerId }, (count) => `${count} now ${ownerId ? `owned by ${name}` : 'unassigned'}.`);
          }}
        />
        <ChoiceMenu
          label="Stage"
          buttonLabel="Change stage"
          groupLabel="Move to stage"
          buttonClass="btn btn-sm"
          disabled={bulk.busy}
          choices={STAGE_CHOICES}
          onPick={(status) => bulk.run({ action: 'STAGE', status }, (count) => `${count} moved to ${COMPANY_STATUS[status]}.`)}
        />
        <ChoiceMenu
          label="Priority"
          buttonLabel="Change priority"
          groupLabel="Set priority to"
          buttonClass="btn btn-sm"
          disabled={bulk.busy}
          choices={options(PRIORITY)}
          onPick={(priority) =>
            bulk.run({ action: 'PRIORITY', priority }, (count) => `${count} set to ${PRIORITY[priority]} priority.`)
          }
        />
        <button
          type="button"
          className="btn btn-sm"
          disabled={bulk.busy}
          onClick={() => bulk.run({ action: 'FOLLOW_UP_TODAY' }, (count) => `${count} set to follow up today.`)}
        >
          Follow up today
        </button>
        <ConfirmButton
          label="Delete"
          confirmLabel={`Delete ${selection.count}`}
          className="btn btn-sm btn-danger"
          onConfirm={() => bulk.run({ action: 'DELETE' }, (count) => `Moved ${count} to the Trash.`)}
        />
      </BulkBar>

      {companies.error && <ErrorNote>{errorMessage(companies.error)}</ErrorNote>}
      {!companies.data && companies.loading && <Loading label="Loading companies" />}
      {waiting > 0 && status !== 'AI_FOUND' && (
        <p className="panel-meta waiting-note">
          {plural(waiting, 'company', 'companies')} found by the prospect finder {waiting === 1 ? 'is' : 'are'} waiting for review on{' '}
          <Link to="/prospects">AI Found</Link>.
        </p>
      )}

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
                <SelectAllBox selection={selection} total={rows.length} noun={NOUN} />
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
              {rows.slice(0, limit).map((company, index, shown) => (
                <tr
                  key={company.id}
                  className={[
                    'row-link',
                    selection.has(company.id) && 'row-ticked',
                    showDupes && index > 0 && dupes.get(shown[index - 1].id) !== dupes.get(company.id) && 'dupe-group-start',
                  ]
                    .filter(Boolean)
                    .join(' ')}
                  onClick={() => navigate(`/companies/${company.id}`)}
                >
                  <RowBox selection={selection} id={company.id} name={company.name} />
                  <td>
                    <Link to={`/companies/${company.id}`} className="cell-title" onClick={(event) => event.stopPropagation()}>
                      {company.name}
                    </Link>
                    {company.offLimits && <span className="tag tag-warn">{offLimitsLabel(company)}</span>}
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

      {merging && (
        <Modal title="Merge duplicates" onClose={() => setMerging(null)} wide>
          <MergeDialog
            kind="companies"
            records={merging}
            fields={COMPANY_MERGE.fields}
            moves={COMPANY_MERGE.moves}
            onCancel={() => setMerging(null)}
            onMerged={(result) => {
              setMerging(null);
              selection.clear();
              toast(`Merged ${result.mergedName} into ${result.keptName}.`);
              companies.reload();
            }}
          />
        </Modal>
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
