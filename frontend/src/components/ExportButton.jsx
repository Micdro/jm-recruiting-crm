import { useState } from 'react';
import { api, errorMessage } from '../api.js';
import { todayIso } from '../format.js';
import { useToast } from '../hooks.js';

/** "companies" to "company", "searches" to "search", "contacts" to "contact". */
function singular(noun) {
  if (noun.endsWith('ies')) return `${noun.slice(0, -3)}y`;
  if (noun.endsWith('ches')) return noun.slice(0, -2);
  return noun.slice(0, -1);
}

/**
 * Downloads the records a list page shows (after its filters) as an Excel file, with every field
 * and the notes.
 *
 * @param part which list: contacts, companies, candidates, searches or placements
 * @param rows the records on the page, in the page's order
 * @param noun what to call them in the file name and the confirmation ("contacts")
 */
export default function ExportButton({ part, rows, noun }) {
  const toast = useToast();
  const [busy, setBusy] = useState(false);

  async function run() {
    setBusy(true);
    try {
      const [{ downloadWorkbook }, data] = await Promise.all([import('../exportExcel.js'), api.exportData([part])]);
      const order = new Map(rows.map((row, index) => [row.id, index]));
      const records = data[part]
        .filter((record) => order.has(record.id))
        .sort((first, second) => order.get(first.id) - order.get(second.id));
      await downloadWorkbook(`JM CRM ${noun} ${todayIso()}.xlsx`, { [part]: records });
      toast(`Exported ${records.length} ${records.length === 1 ? singular(noun) : noun} to Excel`);
    } catch (err) {
      toast(`Couldn’t export: ${errorMessage(err)}`);
    } finally {
      setBusy(false);
    }
  }

  return (
    <button
      type="button"
      className="btn"
      disabled={busy || rows.length === 0}
      onClick={run}
      title={`Download the ${rows.length} ${noun} shown as an Excel file`}
    >
      {busy ? 'Exporting…' : 'Export'}
    </button>
  );
}
