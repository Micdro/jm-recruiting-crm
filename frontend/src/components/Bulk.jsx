import { useEffect, useRef } from 'react';
import { plural } from '../format.js';
import { ErrorNote } from './Bits.jsx';

/**
 * The pieces of bulk actions on a list: tick boxes for the rows and the header, the bar of
 * actions that appears once something is ticked, and the note naming any rows left unchanged.
 * noun is [one, many], for example ['contact', 'contacts'].
 */

export function SelectAllBox({ selection, total, noun }) {
  const box = useRef(null);
  useEffect(() => {
    if (box.current) box.current.indeterminate = selection.some;
  }, [selection.some]);

  return (
    <th scope="col" className="select-cell">
      <input
        ref={box}
        type="checkbox"
        checked={selection.all}
        onChange={selection.toggleAll}
        aria-label={selection.all ? `Untick all ${noun[1]}` : `Tick all ${plural(total, noun[0], noun[1])} shown`}
      />
    </th>
  );
}

export function RowBox({ selection, id, name }) {
  return (
    // The row opens the record; clicks in this cell only tick the box.
    <td className="select-cell" onClick={(event) => event.stopPropagation()}>
      <input
        type="checkbox"
        checked={selection.has(id)}
        onChange={(event) => selection.toggle(id, event.nativeEvent.shiftKey)}
        aria-label={`Tick ${name}`}
      />
    </td>
  );
}

export function BulkBar({ selection, noun, busy, error, children }) {
  if (selection.count === 0) return null;
  return (
    <div className="bulk-bar" role="region" aria-label="Bulk actions">
      <strong className="bulk-count">{plural(selection.count, noun[0], noun[1])} ticked</strong>
      <div className="bulk-actions" aria-busy={busy}>
        {children}
      </div>
      <button type="button" className="btn btn-sm btn-quiet bulk-clear" onClick={selection.clear}>
        Untick all
      </button>
      {error && (
        <div className="bulk-error">
          <ErrorNote>{error}</ErrorNote>
        </div>
      )}
    </div>
  );
}

/** Rows a bulk action left as they were, grouped by why. */
export function SkippedNote({ skipped, noun, onDismiss }) {
  if (!skipped || skipped.length === 0) return null;

  const groups = new Map();
  skipped.forEach((row) => {
    const names = groups.get(row.reason) ?? [];
    names.push(row.name ?? `#${row.id}`);
    groups.set(row.reason, names);
  });

  return (
    <div className="bulk-note" role="status">
      <p className="bulk-note-head">
        {plural(skipped.length, noun[0], noun[1])} {skipped.length === 1 ? 'was' : 'were'} left as {skipped.length === 1 ? 'it was' : 'they were'}:
      </p>
      <ul>
        {[...groups].map(([reason, names]) => (
          <li key={reason}>
            <strong>{reason}</strong>: {names.slice(0, 6).join(', ')}
            {names.length > 6 ? ` and ${names.length - 6} more` : ''}
          </li>
        ))}
      </ul>
      <button type="button" className="btn btn-sm btn-quiet" onClick={onDismiss}>
        Dismiss
      </button>
    </div>
  );
}

/** "4 contacts look like duplicates. Show them", above a list; and the way back when they're shown. */
export function DuplicatesNote({ count, noun, showing, onShow, onHide }) {
  if (count === 0 && !showing) return null;
  if (showing) {
    return (
      <p className="dupes-note">
        {count === 0
          ? `No ${noun[1]} look like duplicates now.`
          : `Possible duplicates, each group together. Tick two and choose Merge.`}{' '}
        <button type="button" className="link-button" onClick={onHide}>
          Show all {noun[1]}
        </button>
      </p>
    );
  }
  return (
    <p className="dupes-note">
      {plural(count, noun[0], noun[1])} look like duplicates (same email, LinkedIn profile or name).{' '}
      <button type="button" className="link-button" onClick={onShow}>
        Show them
      </button>
    </p>
  );
}
