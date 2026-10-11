import { useState } from 'react';
import { Link } from 'react-router';
import Papa from 'papaparse';
import { api, errorMessage } from '../api.js';
import { ErrorNote, PageHeader } from '../components/Bits.jsx';
import { CANDIDATE_STATUS, COMPANY_STATUS, OUTREACH_STATUS, options } from '../labels.js';
import { IMPORT_FIELDS, STATUS_GUESSERS, buildRow, guessMapping, templateCsv } from '../importMapping.js';
import { plural } from '../format.js';
import { useTitle, useToast } from '../hooks.js';

const CHUNK = 1000;

const KINDS = {
  contacts: {
    label: 'Contacts',
    noun: 'contact',
    hint: 'ClickUp’s outbound pipeline or buyer list. One row per person; companies are created from the Company column when they don’t exist yet.',
    statuses: OUTREACH_STATUS,
    statusHint: 'Each status becomes one of the CRM’s outreach statuses. “From touches” uses New, or In sequence when the row has touches.',
    send: (body) => api.imports.contacts(body),
    view: '/contacts',
  },
  companies: {
    label: 'Companies',
    noun: 'company',
    plural: 'companies',
    hint: 'ClickUp’s client list. One row per company, with its stage, priority, hiring need and follow-up date.',
    statuses: COMPANY_STATUS,
    statusHint: 'Each status becomes one of the client stages. “Keep as is” leaves existing companies at their stage and starts new ones at Lead.',
    send: (body) => api.imports.companies(body),
    view: '/companies',
  },
  candidates: {
    label: 'Candidates',
    noun: 'candidate',
    hint: 'ClickUp’s candidate list. One row per candidate.',
    statuses: CANDIDATE_STATUS,
    statusHint: 'Each status becomes a candidate status. Offer accepted, onboarding and placed become Placed; dq becomes Not a fit.',
    send: (body) => api.imports.candidates(body),
    view: '/candidates',
  },
};

export default function ImportPage() {
  useTitle('Import');
  const toast = useToast();
  const [kind, setKind] = useState('contacts');
  const [file, setFile] = useState(null);
  const [mapping, setMapping] = useState({});
  const [statusMap, setStatusMap] = useState({});
  const [duplicateMode, setDuplicateMode] = useState('SKIP');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [result, setResult] = useState(null);

  const fields = IMPORT_FIELDS[kind];
  const config = KINDS[kind];

  function reset(nextKind = kind) {
    setKind(nextKind);
    setFile(null);
    setMapping({});
    setStatusMap({});
    setResult(null);
    setError('');
  }

  function readFile(selected) {
    setResult(null);
    setError('');
    if (!selected) return;

    Papa.parse(selected, {
      header: true,
      skipEmptyLines: 'greedy',
      complete: (parsed) => {
        const headers = (parsed.meta.fields ?? []).filter((header) => header && header.trim() !== '');
        if (headers.length === 0 || parsed.data.length === 0) {
          setError('That file has no rows. Check that the first row holds the column names.');
          setFile(null);
          return;
        }
        const guessed = guessMapping(fields, headers);
        setFile({ name: selected.name, headers, rows: parsed.data });
        setMapping(guessed);
        setStatusMap(statusGuesses(parsed.data, guessed.status));
      },
      error: () => setError('That file couldn’t be read. Save it as CSV (comma separated) and try again.'),
    });
  }

  function statusGuesses(rows, column) {
    if (!column) return {};
    const values = [...new Set(rows.map((row) => String(row[column] ?? '').trim()).filter(Boolean))];
    return Object.fromEntries(values.map((value) => [value, STATUS_GUESSERS[kind](value)]));
  }

  const built = file ? file.rows.map((row) => buildRow(kind, row, mapping, statusMap)) : [];
  const problemCount = built.filter((item) => item.problems.length > 0).length;
  const hasName = mapping.name || (kind !== 'companies' && (mapping.firstName || mapping.lastName));
  const ready = file && hasName && (kind !== 'contacts' || mapping.companyName);

  async function runImport() {
    setBusy(true);
    setError('');
    const totals = { created: 0, updated: 0, skipped: 0, companiesCreated: 0, errors: [] };

    try {
      for (let start = 0; start < built.length; start += CHUNK) {
        const rows = built.slice(start, start + CHUNK).map((item) => item.row);
        const body = { rows, duplicateMode };
        const response = await config.send(body);
        totals.created += response.created;
        totals.updated += response.updated;
        totals.skipped += response.skipped;
        totals.companiesCreated += response.companiesCreated;
        totals.errors.push(...response.errors.map((rowError) => ({ ...rowError, row: rowError.row + start })));
      }
      setResult(totals);
      toast(`Imported ${plural(totals.created, config.noun, config.plural)}`);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  const templateHref = `data:text/csv;charset=utf-8,${encodeURIComponent(templateCsv(kind))}`;

  return (
    <>
      <PageHeader title="Import" sub="Bring in your ClickUp lists, or any spreadsheet saved as CSV." />

      <section className="panel import-step">
        <h2>1. What are you importing?</h2>
        <fieldset className="segmented">
          <legend className="visually-hidden">Import type</legend>
          {Object.entries(KINDS).map(([value, { label }]) => (
            <label key={value} className={kind === value ? 'segment segment-on' : 'segment'}>
              <input type="radio" name="import-kind" checked={kind === value} onChange={() => reset(value)} />
              {label}
            </label>
          ))}
        </fieldset>
        <p className="muted">
          {config.hint}{' '}
          <a href={templateHref} download={`${kind}-template.csv`}>
            Download a template
          </a>
        </p>
        <p className="muted">
          From ClickUp: open the list, export the view as CSV with all columns, then choose that file below. Field types in the column
          names, like “(date)”, are ignored.
        </p>
      </section>

      <section className="panel import-step">
        <h2>2. Choose a CSV file</h2>
        <label className="file-input">
          <span className="visually-hidden">CSV file</span>
          <input
            type="file"
            accept=".csv,text/csv"
            onChange={(event) => readFile(event.target.files?.[0])}
            aria-label="CSV file"
          />
        </label>
        {file && (
          <p>
            {file.name}: {plural(file.rows.length, 'row')}, {plural(file.headers.length, 'column')}.
          </p>
        )}
        <ErrorNote>{error}</ErrorNote>
      </section>

      {file && (
        <section className="panel import-step">
          <h2>3. Match the columns</h2>
          <p className="muted">Columns were matched by their names. Change any that are wrong.</p>
          <div className="mapping">
            {fields.map((field) => (
              <div key={field.key} className="mapping-row">
                <label htmlFor={`map-${field.key}`}>
                  {field.label}
                  {field.required && <span className="required"> (required)</span>}
                </label>
                <select
                  id={`map-${field.key}`}
                  value={mapping[field.key] ?? ''}
                  onChange={(event) => {
                    const value = event.target.value;
                    setMapping((current) => ({ ...current, [field.key]: value || undefined }));
                    if (field.key === 'status') setStatusMap(statusGuesses(file.rows, value));
                  }}
                >
                  <option value="">Don’t import</option>
                  {file.headers.map((header) => (
                    <option key={header} value={header}>
                      {header}
                    </option>
                  ))}
                </select>
              </div>
            ))}
          </div>
          {!hasName && (
            <p className="error-note">
              {kind === 'companies' ? 'Match the Company name column.' : 'Match a Full name column, or First name and Last name.'}
            </p>
          )}
          {kind === 'contacts' && !mapping.companyName && <p className="error-note">Match the Company column.</p>}
        </section>
      )}

      {file && Object.keys(statusMap).length > 0 && (
        <section className="panel import-step">
          <h2>4. Translate statuses</h2>
          <p className="muted">{config.statusHint}</p>
          <div className="mapping">
            {Object.entries(statusMap).map(([value, mapped]) => (
              <div key={value} className="mapping-row">
                <label htmlFor={`status-${value}`}>{value}</label>
                <select
                  id={`status-${value}`}
                  value={mapped}
                  onChange={(event) => setStatusMap((current) => ({ ...current, [value]: event.target.value }))}
                >
                  <option value="">{kind === 'contacts' ? 'From touches' : 'Keep as is'}</option>
                  {options(config.statuses).map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
                </select>
              </div>
            ))}
          </div>
        </section>
      )}

      {file && (
        <section className="panel import-step">
          <h2>{Object.keys(statusMap).length > 0 ? '5' : '4'}. Check and import</h2>

          <fieldset className="radio-group">
            <legend>When a record is already in the CRM</legend>
            <label className="radio">
              <input type="radio" name="duplicates" checked={duplicateMode === 'SKIP'} onChange={() => setDuplicateMode('SKIP')} />
              Skip them
            </label>
            <label className="radio">
              <input type="radio" name="duplicates" checked={duplicateMode === 'UPDATE'} onChange={() => setDuplicateMode('UPDATE')} />
              Update them with the file’s values
            </label>
          </fieldset>
          <p className="muted">
            {kind === 'companies'
              ? 'Companies are matched by name.'
              : 'Duplicates are matched by email, then LinkedIn URL, then name.'}{' '}
            Owners are matched by name, initials (MD, DD) or email; anyone else’s rows are assigned to you.
          </p>

          <Preview kind={kind} built={built} />

          {problemCount > 0 && (
            <p className="warn-note">
              {plural(problemCount, 'row')} {problemCount === 1 ? 'has' : 'have'} dates that couldn’t be read. Those dates will be left
              empty.
            </p>
          )}

          <div className="form-actions form-actions-start">
            <button type="button" className="btn btn-primary" disabled={!ready || busy} onClick={runImport}>
              {busy ? 'Importing…' : `Import ${plural(built.length, 'row')}`}
            </button>
          </div>

          {result && <ImportResult kind={kind} result={result} />}
        </section>
      )}
    </>
  );
}

function Preview({ kind, built }) {
  const sample = built.slice(0, 5);
  if (sample.length === 0) return null;

  const missing = <span className="muted">Missing</span>;
  const name = (row) => row.name || [row.firstName, row.lastName].filter(Boolean).join(' ') || missing;

  const columns = {
    contacts: [
      ['Company', (row) => row.companyName ?? missing],
      ['Email', (row) => row.email],
      ['Status', (row) => (row.status ? OUTREACH_STATUS[row.status] : <span className="muted">From touches</span>)],
      ['Touches', (row) => row.touchCount ?? '', 'num'],
      ['Next action', (row) => row.nextActionDate],
      ['Owner', (row) => row.owner],
    ],
    companies: [
      ['Stage', (row) => (row.status ? COMPANY_STATUS[row.status] : <span className="muted">Lead</span>)],
      ['Priority', (row) => row.priority?.toLowerCase()],
      ['Follow up', (row) => row.followUpDate],
      ['Hires', (row) => row.anticipatedHires ?? '', 'num'],
      ['Owner', (row) => row.owner],
    ],
    candidates: [
      ['Status', (row) => (row.status ? CANDIDATE_STATUS[row.status] : <span className="muted">Active</span>)],
      ['Email', (row) => row.email],
      ['Roles', (row) => (row.roleAreas ?? []).map((area) => area.toLowerCase()).join(', ')],
      ['Owner', (row) => row.owner],
    ],
  }[kind];

  return (
    <div className="table-wrap">
      <table className="table table-compact">
        <caption>First {sample.length} rows as they will be imported</caption>
        <thead>
          <tr>
            <th scope="col">Name</th>
            {columns.map(([label, , className]) => (
              <th key={label} scope="col" className={className}>
                {label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {sample.map(({ row }, index) => (
            <tr key={index}>
              <td>{name(row)}</td>
              {columns.map(([label, cell, className]) => (
                <td key={label} className={className}>
                  {cell(row)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function ImportResult({ kind, result }) {
  const { noun, plural: nouns, view } = KINDS[kind];
  return (
    <div className="import-result" role="status">
      <p>
        <strong>Import finished.</strong> {plural(result.created, `new ${noun}`, `new ${nouns ?? `${noun}s`}`)}, {result.updated} updated,{' '}
        {result.skipped} skipped as duplicates
        {kind === 'contacts' ? `, ${plural(result.companiesCreated, 'new company', 'new companies')}` : ''}.
      </p>
      {result.errors.length > 0 && (
        <>
          <p>{plural(result.errors.length, 'row')} couldn’t be imported:</p>
          <ul className="plain-list import-errors">
            {result.errors.slice(0, 100).map((rowError) => (
              <li key={`${rowError.row}-${rowError.message}`}>
                Row {rowError.row}: {rowError.message}
              </li>
            ))}
          </ul>
        </>
      )}
      <p>
        <Link to={view}>View {nouns ?? `${noun}s`}</Link>
        {kind === 'contacts' && (
          <>
            {' '}
            or go to <Link to="/">Today</Link> to pull new contacts into your queue.
          </>
        )}
      </p>
    </div>
  );
}
