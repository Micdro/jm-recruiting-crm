import { useState } from 'react';
import { api, errorMessage } from '../api.js';
import { money, parseDate, toIsoDate, todayIso } from '../format.js';
import { useLoader } from '../hooks.js';
import { ErrorNote, Loading } from './Bits.jsx';
import { TextArea, TextField } from './Fields.jsx';

function addDays(iso, days) {
  const date = parseDate(iso);
  if (!date) return '';
  date.setDate(date.getDate() + days);
  return toIsoDate(date);
}

/** The firm's details haven't been filled in yet, so an invoice would only show its name. */
const notSetUp = (settings) => !settings.address && !settings.email && !settings.paymentInstructions;

/**
 * Makes (or remakes) the invoice for a placement: the firm's details the first time, then the
 * invoice itself. Saving marks the placement invoiced and downloads the PDF.
 */
export default function InvoiceForm({ placement, onDone, onCancel }) {
  const settings = useLoader(() => api.firm.invoiceSettings(), [], 'firm:invoice');
  const next = useLoader(
    () => (placement.invoiceNumber ? Promise.resolve({ invoiceNumber: placement.invoiceNumber }) : api.placements.nextInvoiceNumber()),
    [placement.id],
  );
  const [editingFirm, setEditingFirm] = useState(false);

  if (!settings.data || !next.data) {
    return settings.error || next.error ? (
      <ErrorNote>{errorMessage(settings.error || next.error)}</ErrorNote>
    ) : (
      <Loading label="Getting the invoice ready" />
    );
  }
  if (editingFirm || notSetUp(settings.data)) {
    return (
      <FirmForm
        settings={settings.data}
        first={notSetUp(settings.data)}
        onSaved={(saved) => {
          settings.setData(saved);
          setEditingFirm(false);
        }}
        onCancel={notSetUp(settings.data) ? onCancel : () => setEditingFirm(false)}
      />
    );
  }
  return (
    <InvoiceDetails
      placement={placement}
      firm={settings.data}
      suggestedNumber={next.data.invoiceNumber}
      onEditFirm={() => setEditingFirm(true)}
      onDone={onDone}
      onCancel={onCancel}
    />
  );
}

function InvoiceDetails({ placement, firm, suggestedNumber, onEditFirm, onDone, onCancel }) {
  const invoiceDate = placement.invoiceDate ?? todayIso();
  const [form, setForm] = useState({
    invoiceNumber: suggestedNumber,
    invoiceDate,
    dueDate: placement.invoiceNumber && placement.invoiceDueDate ? placement.invoiceDueDate : addDays(invoiceDate, firm.termsDays),
    billToName: placement.billToName ?? placement.companyName ?? '',
    billToEmail: placement.billToEmail ?? '',
    billToAddress: placement.billToAddress ?? '',
    description:
      placement.invoiceDescription ?? `Placement fee: ${placement.candidateName}, ${placement.searchTitle}`,
  });
  const [dueEdited, setDueEdited] = useState(Boolean(placement.invoiceNumber));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const set = (key) => (value) => setForm((current) => ({ ...current, [key]: value }));

  async function submit(event) {
    event.preventDefault();
    setBusy(true);
    setError('');
    const body = {
      ...form,
      billToEmail: form.billToEmail.trim() || null,
      billToAddress: form.billToAddress.trim() || null,
    };
    try {
      const saved = await api.placements.invoice(placement.id, body);
      const { downloadInvoice } = await import('../invoicePdf.js');
      await downloadInvoice({ firm, invoice: { ...body, invoiceNumber: saved.invoiceNumber }, placement: saved });
      onDone(saved);
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  const noFee = placement.feeAmount === null || placement.feeAmount === undefined;

  return (
    <form className="form invoice-form" onSubmit={submit}>
      <p className="invoice-from">
        From <strong>{firm.firmName}</strong>.{' '}
        <button type="button" className="link-button" onClick={onEditFirm}>
          Edit your firm’s details
        </button>
      </p>
      <div className="form-row">
        <TextField label="Invoice number" value={form.invoiceNumber} onChange={set('invoiceNumber')} required maxLength={40} />
        <div className="invoice-amount">
          <span className="field-label">Amount</span>
          <strong>{noFee ? 'No fee yet' : money(placement.feeAmount)}</strong>
          <span className="field-hint">The placement’s fee. Change it on the placement.</span>
        </div>
      </div>
      <div className="form-row">
        <TextField
          label="Invoice date"
          type="date"
          value={form.invoiceDate}
          onChange={(value) =>
            setForm((current) => ({
              ...current,
              invoiceDate: value,
              dueDate: dueEdited ? current.dueDate : addDays(value, firm.termsDays),
            }))
          }
          required
        />
        <TextField
          label="Due date"
          type="date"
          hint={`Your terms are ${firm.termsDays} days.`}
          value={form.dueDate}
          onChange={(value) => {
            setDueEdited(true);
            set('dueDate')(value);
          }}
          required
        />
      </div>
      <TextField label="Bill to" value={form.billToName} onChange={set('billToName')} required maxLength={200} hint="The client, and who it’s for if that helps: Ledgerlight AI, Attn: Accounts Payable." />
      <div className="form-row">
        <TextField label="Billing email" type="email" value={form.billToEmail} onChange={set('billToEmail')} maxLength={255} />
        <TextArea label="Billing address" rows={2} value={form.billToAddress} onChange={set('billToAddress')} maxLength={500} />
      </div>
      <TextField label="Line on the invoice" value={form.description} onChange={set('description')} required maxLength={500} />

      {noFee && <p className="warn-note">Add the fee to the placement first. The invoice is for that amount.</p>}
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="button" className="btn" onClick={onCancel}>
          Cancel
        </button>
        <button type="submit" className="btn btn-primary" disabled={busy || noFee}>
          {busy ? 'Saving…' : placement.invoiceNumber ? 'Save and download PDF' : 'Create invoice and download PDF'}
        </button>
      </div>
    </form>
  );
}

function FirmForm({ settings, first, onSaved, onCancel }) {
  const [form, setForm] = useState({
    firmName: settings.firmName ?? '',
    address: settings.address ?? '',
    email: settings.email ?? '',
    phone: settings.phone ?? '',
    website: settings.website ?? '',
    paymentInstructions: settings.paymentInstructions ?? '',
    termsDays: String(settings.termsDays ?? 30),
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const set = (key) => (value) => setForm((current) => ({ ...current, [key]: value }));

  async function submit(event) {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      onSaved(await api.firm.saveInvoiceSettings({ ...form, termsDays: Number(form.termsDays) }));
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  return (
    <form className="form" onSubmit={submit}>
      <p className="form-intro">
        {first
          ? 'First, your firm’s details for the top of every invoice. You only do this once; the team shares them.'
          : 'These go on every invoice. The team shares them.'}
      </p>
      <div className="form-row">
        <TextField label="Firm name" value={form.firmName} onChange={set('firmName')} required maxLength={200} />
        <TextField label="Payment terms (days)" type="number" min={0} max={365} value={form.termsDays} onChange={set('termsDays')} required />
      </div>
      <TextArea label="Address" rows={2} value={form.address} onChange={set('address')} maxLength={500} />
      <div className="form-row">
        <TextField label="Billing email" type="email" value={form.email} onChange={set('email')} maxLength={255} />
        <TextField label="Phone" value={form.phone} onChange={set('phone')} maxLength={50} />
      </div>
      <TextField label="Website" value={form.website} onChange={set('website')} maxLength={255} />
      <TextArea
        label="How to pay"
        rows={3}
        value={form.paymentInstructions}
        onChange={set('paymentInstructions')}
        maxLength={1000}
        hint="Bank, routing and account numbers for ACH or wire, or a check address. Stored encrypted."
      />
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="button" className="btn" onClick={onCancel}>
          Cancel
        </button>
        <button type="submit" className="btn btn-primary" disabled={busy}>
          {busy ? 'Saving…' : first ? 'Save and continue' : 'Save'}
        </button>
      </div>
    </form>
  );
}
