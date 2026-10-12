import { useState } from 'react';
import { api, errorMessage } from '../api.js';
import { ErrorNote, PageHeader } from '../components/Bits.jsx';
import { todayIso } from '../format.js';
import { useTitle, useToast } from '../hooks.js';

const SHEETS = [
  ['Companies', 'Status, tier, hiring need, owner, follow-up date and notes.'],
  ['Contacts', 'Contact details, outreach status, touches, next action and notes.'],
  ['Searches', 'Status, fee, base range, scope and notes.'],
  ['Candidates', 'Contact details, track, status, experience, production evidence and notes.'],
  ['Pipeline', 'Every candidate in every search, with stage and rationale.'],
  ['Placements', 'Start date, salary, fee and invoice dates.'],
  ['Activity', 'Every logged touch, reply, meeting and note.'],
];

export default function ExportPage() {
  useTitle('Export');
  const toast = useToast();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function exportEverything() {
    setBusy(true);
    setError('');
    try {
      const [{ downloadWorkbook }, data] = await Promise.all([import('../exportExcel.js'), api.exportData()]);
      await downloadWorkbook(`JM CRM export ${todayIso()}.xlsx`, data);
      toast('Exported everything to Excel');
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <PageHeader title="Export" sub="Download the CRM as an Excel file, one sheet per list." />
      <section className="panel narrow-panel" aria-labelledby="export-heading">
        <div className="panel-head">
          <h2 id="export-heading">Everything</h2>
        </div>
        <ul className="export-sheets">
          {SHEETS.map(([sheet, what]) => (
            <li key={sheet}>
              <strong>{sheet}</strong> {what}
            </li>
          ))}
        </ul>
        <p className="warn-note">
          The file isn’t encrypted. Keep it somewhere private, and delete it when you’re done with it.
        </p>
        <ErrorNote>{error}</ErrorNote>
        <div className="form-actions form-actions-start">
          <button type="button" className="btn btn-primary" disabled={busy} onClick={exportEverything}>
            {busy ? 'Exporting…' : 'Download Excel file'}
          </button>
        </div>
        <p className="panel-foot">
          To export part of a list, filter it on its page (Contacts, Companies, Candidates, Searches or Placements) and
          click Export there.
        </p>
      </section>
    </>
  );
}
