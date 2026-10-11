import { useState } from 'react';
import { Link } from 'react-router';
import Papa from 'papaparse';
import { api, errorMessage } from '../api.js';
import { ErrorNote, PageHeader } from '../components/Bits.jsx';
import { OUTREACH_STATUS, options } from '../labels.js';
import {
  CANDIDATE_FIELDS,
  CONTACT_FIELDS,
  buildRow,
  guessMapping,
  guessOutreachStatus,
  templateCsv,
} from '../importMapping.js';
import { plural } from '../format.js';
import { useTitle, useToast } from '../hooks.js';

const CHUNK = 1000;

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

  const fields = kind === 'contacts' ? CONTACT_FIELDS : CANDIDATE_FIELDS;

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
    if (!column || kind !== 'contacts') return {};
    const values = [...new Set(rows.map((row) => String(row[column] ?? '').trim()).filter(Boolean))];
    return Object.fromEntries(values.map((value) => [value, guessOutreachStatus(value)]));
  }

  const built = file ? file.rows.map((row) => buildRow(kind, row, mapping, statusMap)) : [];
  const problemCount = built.filter((item) => item.problems.length > 0).length;
  const hasName = mapping.name || mapping.firstName || mapping.lastName;
  const ready = file && hasName && (kind === 'candidates' || mapping.companyName);

  async function runImport() {
    setBusy(true);
    setError('');
    const totals = { created: 0, updated: 0, skipped: 0, companiesCreated: 0, errors: [] };

    try {
      for (let start = 0; start < built.length; start += CHUNK) {
        const rows = built.slice(start, start + CHUNK).map((item) => item.row);
        const body = { rows, duplicateMode };
        const response = kind === 'contacts' ? await api.imports.contacts(body) : await api.imports.candidates(body);
        totals.created += response.created;
        totals.updated += response.updated;
        totals.skipped += response.skipped;
        totals.companiesCreated += response.companiesCreated;
        totals.errors.push(...response.errors.map((rowError) => ({ ...rowError, row: rowError.row + start })));
      }
      setResult(totals);
      toast(`Imported ${plural(totals.created, kind === 'contacts' ? 'contact' : 'candidate')}`);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  const templateHref = `data:text/csv;charset=utf-8,${encodeURIComponent(templateCsv(kind))}`;

  return (
    <>
      <PageHeader title="Import" sub="Bring in a buyer list or a candidate list from a spreadsheet." />

      <section className="panel import-step">
        <h2>1. What are you importing?</h2>
        <fieldset className="segmented">
          <legend className="visually-hidden">Import type</legend>
          {[
            ['contacts', 'Contacts and companies'],
            ['candidates', 'Candidates'],
          ].map(([value, label]) => (
            <label key={value} className={kind === value ? 'segment segment-on' : 'segment'}>
              <input type="radio" name="import-kind" checked={kind === value} onChange={() => reset(value)} />
              {label}
            </label>
          ))}
        </fieldset>
        <p className="muted">
          {kind === 'contacts'
            ? 'One row per person. Companies are created from the Company column when they don’t exist yet.'
            : 'One row per candidate.'}{' '}
          <a href={templateHref} download={`${kind}-template.csv`}>
            Download a template
          </a>
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
          {!hasName && <p className="error-note">Match a Full name column, or First name and Last name.</p>}
          {kind === 'contacts' && !mapping.companyName && <p className="error-note">Match the Company column.</p>}
        </section>
      )}

      {file && kind === 'contacts' && Object.keys(statusMap).length > 0 && (
        <section className="panel import-step">
          <h2>4. Translate statuses</h2>
          <p className="muted">
            Each status in your file becomes one of the CRM’s outreach statuses. “From touches” uses New, or In sequence when the
            row has touches.
          </p>
          <div className="mapping">
            {Object.entries(statusMap).map(([value, mapped]) => (
              <div key={value} className="mapping-row">
                <label htmlFor={`status-${value}`}>{value}</label>
                <select
                  id={`status-${value}`}
                  value={mapped}
                  onChange={(event) => setStatusMap((current) => ({ ...current, [value]: event.target.value }))}
                >
                  <option value="">From touches</option>
                  {options(OUTREACH_STATUS).map((option) => (
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
          <h2>{kind === 'contacts' && Object.keys(statusMap).length > 0 ? '5' : '4'}. Check and import</h2>

          <fieldset className="radio-group">
            <legend>When someone is already in the CRM</legend>
            <label className="radio">
              <input type="radio" name="duplicates" checked={duplicateMode === 'SKIP'} onChange={() => setDuplicateMode('SKIP')} />
              Skip them
            </label>
            <label className="radio">
              <input type="radio" name="duplicates" checked={duplicateMode === 'UPDATE'} onChange={() => setDuplicateMode('UPDATE')} />
              Update them with the file’s values
            </label>
          </fieldset>
          <p className="muted">Duplicates are matched by email, then LinkedIn URL, then name.</p>

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

  const name = (row) => row.name || [row.firstName, row.lastName].filter(Boolean).join(' ') || <span className="muted">Missing</span>;

  return (
    <div className="table-wrap">
      <table className="table table-compact">
        <caption>First {sample.length} rows as they will be imported</caption>
        <thead>
          <tr>
            <th scope="col">Name</th>
            {kind === 'contacts' ? (
              <>
                <th scope="col">Company</th>
                <th scope="col">Email</th>
                <th scope="col">Status</th>
                <th scope="col" className="num">Touches</th>
                <th scope="col">Next action</th>
              </>
            ) : (
              <>
                <th scope="col">Title</th>
                <th scope="col">Company</th>
                <th scope="col">Email</th>
                <th scope="col">Track</th>
              </>
            )}
          </tr>
        </thead>
        <tbody>
          {sample.map(({ row }, index) => (
            <tr key={index}>
              <td>{name(row)}</td>
              {kind === 'contacts' ? (
                <>
                  <td>{row.companyName ?? <span className="muted">Missing</span>}</td>
                  <td>{row.email}</td>
                  <td>{row.status ? OUTREACH_STATUS[row.status] : <span className="muted">From touches</span>}</td>
                  <td className="num">{row.touchCount ?? ''}</td>
                  <td>{row.nextActionDate}</td>
                </>
              ) : (
                <>
                  <td>{row.currentTitle}</td>
                  <td>{row.currentCompany}</td>
                  <td>{row.email}</td>
                  <td>{row.track ? row.track.replaceAll('_', ' ').toLowerCase() : ''}</td>
                </>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function ImportResult({ kind, result }) {
  const noun = kind === 'contacts' ? 'contact' : 'candidate';
  return (
    <div className="import-result" role="status">
      <p>
        <strong>Import finished.</strong> {plural(result.created, `new ${noun}`)}, {result.updated} updated, {result.skipped} skipped as
        duplicates
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
        <Link to={kind === 'contacts' ? '/contacts' : '/candidates'}>View {noun}s</Link>
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
