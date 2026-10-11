import { useState } from 'react';
import { api, errorMessage } from '../api.js';
import { ConfirmButton, ErrorNote } from '../components/Bits.jsx';
import { SelectField, TextArea, TextField } from '../components/Fields.jsx';
import { INVOICE_STATUS, options } from '../labels.js';
import { money } from '../format.js';
import { useLoader } from '../hooks.js';
import { asForm, blankToNull, toId, toNumber } from './formUtils.js';

const FIELDS = {
  searchId: '',
  candidateId: '',
  startDate: '',
  baseSalary: '',
  feePercent: '',
  feeAmount: '',
  invoiceStatus: 'NOT_INVOICED',
  invoiceDate: '',
  paidDate: '',
  guaranteeEndDate: '',
  notes: '',
};

/**
 * Records a hire. Leave the fee amount empty to calculate it from base salary and fee percent;
 * leave the fee percent empty to use the search's terms.
 */
export default function PlacementForm({ placement, onSaved, onCancel, onDeleted }) {
  const [form, setForm] = useState(() => asForm(placement, FIELDS));
  const searches = useLoader(() => api.searches.list(), [], 'searches');
  const pipeline = useLoader(
    () => (form.searchId ? api.searches.pipeline(form.searchId) : Promise.resolve([])),
    [form.searchId],
  );
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const set = (key) => (value) => setForm((current) => ({ ...current, [key]: value }));

  const search = (searches.data ?? []).find((item) => String(item.id) === form.searchId);
  const percent = toNumber(form.feePercent) ?? search?.feePercent ?? null;
  const base = toNumber(form.baseSalary);
  const estimatedFee = toNumber(form.feeAmount) ?? (base && percent ? (base * percent) / 100 : null);

  async function submit(event) {
    event.preventDefault();
    setBusy(true);
    setError('');

    const body = {
      searchId: toId(form.searchId),
      candidateId: toId(form.candidateId),
      startDate: form.startDate || null,
      baseSalary: toNumber(form.baseSalary),
      feePercent: toNumber(form.feePercent),
      feeAmount: toNumber(form.feeAmount),
      invoiceStatus: form.invoiceStatus || null,
      invoiceDate: form.invoiceDate || null,
      paidDate: form.paidDate || null,
      guaranteeEndDate: form.guaranteeEndDate || null,
      notes: blankToNull(form.notes),
    };

    try {
      const saved = placement?.id ? await api.placements.update(placement.id, body) : await api.placements.create(body);
      onSaved(saved);
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  const candidateOptions = (pipeline.data ?? []).map((entry) => ({
    value: String(entry.candidateId),
    label: entry.candidateName,
  }));
  if (placement?.candidateId && !candidateOptions.some((option) => option.value === String(placement.candidateId))) {
    candidateOptions.push({ value: String(placement.candidateId), label: placement.candidateName });
  }

  return (
    <form className="form" onSubmit={submit}>
      <div className="form-row">
        <SelectField
          label="Search"
          value={form.searchId}
          onChange={(value) => setForm((current) => ({ ...current, searchId: value, candidateId: '' }))}
          options={(searches.data ?? []).map((item) => ({ value: String(item.id), label: `${item.title}, ${item.companyName}` }))}
          placeholder={searches.loading ? 'Loading searches…' : 'Select a search'}
          required
        />
        <SelectField
          label="Candidate"
          value={form.candidateId}
          onChange={set('candidateId')}
          options={candidateOptions}
          placeholder={form.searchId ? 'Select from this search' : 'Choose a search first'}
          required
        />
      </div>
      <div className="form-row">
        <TextField label="Start date" type="date" value={form.startDate} onChange={set('startDate')} />
        <TextField label="Base salary" inputMode="numeric" value={form.baseSalary} onChange={set('baseSalary')} placeholder="200000" />
      </div>
      <div className="form-row">
        <TextField
          label="Fee percent"
          inputMode="decimal"
          value={form.feePercent}
          onChange={set('feePercent')}
          placeholder={search?.feePercent ? `${search.feePercent} (from the search)` : '25'}
        />
        <TextField
          label="Fee amount"
          inputMode="decimal"
          value={form.feeAmount}
          onChange={set('feeAmount')}
          placeholder="Calculated if left empty"
          hint={estimatedFee ? `Fee: ${money(estimatedFee)}` : null}
        />
      </div>
      <div className="form-row">
        <SelectField
          label="Invoice status"
          value={form.invoiceStatus}
          onChange={set('invoiceStatus')}
          options={options(INVOICE_STATUS)}
          required
        />
        <TextField
          label="Guarantee ends"
          type="date"
          value={form.guaranteeEndDate}
          onChange={set('guaranteeEndDate')}
          hint="Defaults to 90 days after the start date."
        />
      </div>
      <div className="form-row">
        <TextField label="Invoice date" type="date" value={form.invoiceDate} onChange={set('invoiceDate')} />
        <TextField label="Paid date" type="date" value={form.paidDate} onChange={set('paidDate')} />
      </div>
      <TextArea label="Notes" value={form.notes} onChange={set('notes')} maxLength={2000} />

      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        {placement?.id && onDeleted && (
          <ConfirmButton
            label="Delete placement"
            onConfirm={async () => {
              try {
                await api.placements.remove(placement.id);
                onDeleted();
              } catch (err) {
                setError(errorMessage(err));
              }
            }}
          />
        )}
        <span className="spacer" />
        <button type="button" className="btn" onClick={onCancel}>
          Cancel
        </button>
        <button type="submit" className="btn btn-primary" disabled={busy}>
          {busy ? 'Saving…' : 'Save placement'}
        </button>
      </div>
    </form>
  );
}
