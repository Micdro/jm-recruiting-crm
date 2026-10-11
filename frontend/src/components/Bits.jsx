import { useState } from 'react';
import { MAX_TOUCHES, toneFor } from '../labels.js';
import { dueInfo } from '../format.js';

/** A status label. `labels` maps the enum value to its display name. */
export function Pill({ value, labels }) {
  if (!value) return null;
  return <span className={`pill pill-${toneFor(value)}`}>{labels[value] ?? value}</span>;
}

/** Four boxes, one per touch in the cold sequence. */
export function TouchPips({ count, max = MAX_TOUCHES }) {
  return (
    <span className="pips" role="img" aria-label={`${count} of ${max} touches`}>
      {Array.from({ length: max }, (_, index) => (
        <span key={index} className={index < count ? 'pip pip-on' : 'pip'} />
      ))}
    </span>
  );
}

export function DueTag({ date, today }) {
  const { label, tone } = dueInfo(date, today);
  return <span className={`due due-${tone}`}>{label}</span>;
}

export function ErrorNote({ children }) {
  if (!children) return null;
  return (
    <p className="error-note" role="alert">
      {children}
    </p>
  );
}

export function Loading({ label = 'Loading' }) {
  return <p className="loading">{label}…</p>;
}

export function Empty({ title, children }) {
  return (
    <div className="empty">
      <p className="empty-title">{title}</p>
      {children && <div className="empty-body">{children}</div>}
    </div>
  );
}

export function PageHeader({ title, sub, children }) {
  return (
    <div className="page-header">
      <div className="page-heading">
        <h1>{title}</h1>
        {sub && <div className="page-sub">{sub}</div>}
      </div>
      {children && <div className="page-actions">{children}</div>}
    </div>
  );
}

/** A delete button that asks once before acting. */
export function ConfirmButton({ label = 'Delete', confirmLabel = 'Confirm delete', onConfirm, className = 'btn btn-danger' }) {
  const [armed, setArmed] = useState(false);
  const [busy, setBusy] = useState(false);

  if (!armed) {
    return (
      <button type="button" className={className} onClick={() => setArmed(true)}>
        {label}
      </button>
    );
  }

  return (
    <span className="confirm">
      <button
        type="button"
        className="btn btn-danger-solid"
        disabled={busy}
        onClick={async () => {
          setBusy(true);
          try {
            await onConfirm();
          } finally {
            setBusy(false);
            setArmed(false);
          }
        }}
      >
        {busy ? 'Deleting…' : confirmLabel}
      </button>
      <button type="button" className="btn btn-quiet" onClick={() => setArmed(false)}>
        Cancel
      </button>
    </span>
  );
}

/** Label and value pairs for record details. Empty values are skipped. */
export function Details({ items }) {
  const shown = items.filter((item) => item.value !== null && item.value !== undefined && item.value !== '' && item.value !== false);
  if (shown.length === 0) return null;
  return (
    <dl className="details">
      {shown.map((item) => (
        <div key={item.label} className="details-row">
          <dt>{item.label}</dt>
          <dd>{item.value}</dd>
        </div>
      ))}
    </dl>
  );
}

export function ExternalLink({ href, children }) {
  if (!href) return null;
  return (
    <a href={href} target="_blank" rel="noreferrer">
      {children}
    </a>
  );
}
