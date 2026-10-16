import { useState } from 'react';
import { Link } from 'react-router';
import { api, errorMessage } from '../api.js';
import { useUndoableDelete } from '../undo.js';
import { Empty, ErrorNote, Loading, PageHeader, Pill } from '../components/Bits.jsx';
import ExportButton from '../components/ExportButton.jsx';
import InvoiceForm from '../components/InvoiceForm.jsx';
import Modal from '../components/Modal.jsx';
import PlacementForm from '../forms/PlacementForm.jsx';
import { INVOICE_STATUS } from '../labels.js';
import { formatDate, money, percent, todayIso } from '../format.js';
import { useLoader, useTitle, useToast } from '../hooks.js';

export default function PlacementsPage() {
  useTitle('Placements');
  const toast = useToast();
  const undoable = useUndoableDelete();
  const placements = useLoader(() => api.placements.list(), [], 'placements');
  const [editing, setEditing] = useState(null);
  const [invoicing, setInvoicing] = useState(null);

  const list = placements.data ?? [];
  const year = new Date().getFullYear();
  const today = todayIso();

  const thisYear = list.filter((placement) => (placement.startDate ?? '').startsWith(String(year)));
  const sum = (items) => items.reduce((total, placement) => total + Number(placement.feeAmount ?? 0), 0);
  const booked = sum(thisYear);
  const collected = sum(list.filter((placement) => placement.invoiceStatus === 'PAID' && (placement.paidDate ?? '').startsWith(String(year))));
  const outstanding = sum(list.filter((placement) => placement.invoiceStatus === 'INVOICED'));

  return (
    <>
      <PageHeader title="Placements" sub="Hires, fees and invoices.">
        <ExportButton part="placements" rows={list} noun="placements" />
        <button type="button" className="btn btn-primary" onClick={() => setEditing({})}>
          Record placement
        </button>
      </PageHeader>

      {placements.error && <ErrorNote>{errorMessage(placements.error)}</ErrorNote>}
      {!placements.data && placements.loading && <Loading label="Loading placements" />}

      {placements.data && (
        <dl className="money money-wide">
          <div>
            <dt>Fees booked in {year}</dt>
            <dd>{money(booked)}</dd>
          </div>
          <div>
            <dt>Collected in {year}</dt>
            <dd>{money(collected)}</dd>
          </div>
          <div>
            <dt>Invoiced, unpaid</dt>
            <dd>{money(outstanding)}</dd>
          </div>
        </dl>
      )}

      {placements.data && list.length === 0 && (
        <Empty title="No placements yet">Record one from a search when a candidate accepts an offer.</Empty>
      )}

      {list.length > 0 && (
        <div className="table-wrap">
          <table className="table">
            <caption className="visually-hidden">Placements</caption>
            <thead>
              <tr>
                <th scope="col">Candidate</th>
                <th scope="col">Search</th>
                <th scope="col">Start</th>
                <th scope="col" className="num">Base</th>
                <th scope="col" className="num">Fee</th>
                <th scope="col">Invoice</th>
                <th scope="col">Guarantee ends</th>
              </tr>
            </thead>
            <tbody>
              {list.map((placement) => {
                const guaranteeOpen = placement.guaranteeEndDate && placement.guaranteeEndDate >= today;
                return (
                  <tr key={placement.id} className="row-link" onClick={() => setEditing(placement)}>
                    <td>
                      <Link to={`/candidates/${placement.candidateId}`} className="cell-title" onClick={(event) => event.stopPropagation()}>
                        {placement.candidateName}
                      </Link>
                    </td>
                    <td>
                      <Link to={`/searches/${placement.searchId}`} onClick={(event) => event.stopPropagation()}>
                        {placement.searchTitle}
                      </Link>
                      <span className="cell-sub">{placement.companyName}</span>
                    </td>
                    <td>{formatDate(placement.startDate) || <span className="muted">Not set</span>}</td>
                    <td className="num">{money(placement.baseSalary)}</td>
                    <td className="num">
                      {money(placement.feeAmount)}
                      {placement.feePercent && <span className="cell-sub">{percent(placement.feePercent)}</span>}
                    </td>
                    <td>
                      <InvoiceCell placement={placement} today={today} onInvoice={() => setInvoicing(placement)} />
                    </td>
                    <td>
                      {placement.guaranteeEndDate ? (
                        <span className={guaranteeOpen ? '' : 'muted'}>{formatDate(placement.guaranteeEndDate)}</span>
                      ) : (
                        <span className="muted">Not set</span>
                      )}
                      {placement.checkInDays && (
                        <span className={placement.checkInDue ? 'cell-sub check-in-due' : 'cell-sub'}>
                          {placement.checkInDays}-day check-in {placement.checkInDue ? 'due' : formatDate(placement.checkInDate)}
                        </span>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          <p className="table-count">Select a row to update the invoice or dates.</p>
        </div>
      )}

      {invoicing && (
        <Modal
          title={invoicing.invoiceNumber ? `Invoice ${invoicing.invoiceNumber}` : `Invoice for ${invoicing.candidateName}`}
          onClose={() => setInvoicing(null)}
          wide
        >
          <InvoiceForm
            placement={invoicing}
            onCancel={() => setInvoicing(null)}
            onDone={(saved) => {
              setInvoicing(null);
              toast(`Invoice ${saved.invoiceNumber} saved and downloaded. Send it to ${saved.billToEmail || saved.billToName}.`);
              placements.reload();
            }}
          />
        </Modal>
      )}

      {editing && (
        <Modal title={editing.id ? `Placement: ${editing.candidateName}` : 'Record placement'} onClose={() => setEditing(null)}>
          <PlacementForm
            placement={editing.id ? editing : null}
            onCancel={() => setEditing(null)}
            onDeleted={() => {
              const deleted = editing;
              setEditing(null);
              undoable('PLACEMENT', deleted.id, `Deleted the placement of ${deleted.candidateName}`, () => placements.reload());
              placements.reload();
            }}
            onSaved={() => {
              setEditing(null);
              toast('Placement saved');
              placements.reload();
            }}
          />
        </Modal>
      )}
    </>
  );
}

/** The invoice's status, its number (which reopens it) or a button to make one, and whether it's late. */
function InvoiceCell({ placement, today, onInvoice }) {
  const open = (event) => {
    event.stopPropagation();
    onInvoice();
  };
  const overdue =
    placement.invoiceStatus === 'INVOICED' && placement.invoiceDueDate && placement.invoiceDueDate < today;
  return (
    <div className="invoice-cell">
      <Pill value={placement.invoiceStatus} labels={INVOICE_STATUS} />
      {placement.invoiceNumber ? (
        <button type="button" className="link-button" onClick={open}>
          Invoice {placement.invoiceNumber}
        </button>
      ) : (
        placement.invoiceStatus === 'NOT_INVOICED' &&
        placement.feeAmount != null && (
          <button type="button" className="btn btn-sm" onClick={open}>
            Create invoice
          </button>
        )
      )}
      {overdue ? (
        <span className="invoice-overdue">Overdue since {formatDate(placement.invoiceDueDate)}</span>
      ) : (
        placement.invoiceStatus === 'INVOICED' &&
        placement.invoiceDueDate && <span className="cell-sub">Due {formatDate(placement.invoiceDueDate)}</span>
      )}
    </div>
  );
}
