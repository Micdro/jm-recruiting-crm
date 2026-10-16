import { useState } from 'react';
import { api, errorMessage } from './api.js';
import { plural } from './format.js';
import { useToast } from './hooks.js';

/**
 * Runs a bulk action for a list (kind is 'contacts' or 'companies'), then offers Undo for the
 * whole batch and keeps the rows that were left alone for the note.
 */
export function useBulkAction(kind, noun, selection, onDone) {
  const toast = useToast();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [skipped, setSkipped] = useState([]);

  async function undo(undoId) {
    try {
      const result = await api[kind].undoBulk(undoId);
      setSkipped(result.skipped);
      toast(
        `Undone for ${plural(result.changed, noun[0], noun[1])}.` +
          (result.skipped.length > 0 ? ` ${result.skipped.length} changed since and were left alone.` : ''),
      );
      onDone();
    } catch (err) {
      toast(errorMessage(err));
    }
  }

  /** describe(changed) says what happened, for the confirmation. */
  async function run(body, describe) {
    setBusy(true);
    setError('');
    try {
      const result = await api[kind].bulk({ ids: selection.ids, ...body });
      setSkipped(result.skipped);
      if (result.changed === 0) {
        toast(`No ${noun[1]} changed.`);
      } else {
        toast(describe(plural(result.changed, noun[0], noun[1])), {
          action: { label: 'Undo', onClick: () => undo(result.undoId) },
        });
        selection.clear();
      }
      onDone();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return { run, busy, error, skipped, dismiss: () => setSkipped([]) };
}

/** Owner choices for a bulk action: active teammates, you first, then Unassigned. */
export function ownerChoices(users, me) {
  const active = users.filter((user) => user.active);
  const sorted = [...active.filter((user) => user.id === me?.id), ...active.filter((user) => user.id !== me?.id)];
  return [
    ...sorted.map((user) => ({ value: user.id, label: user.id === me?.id ? `${user.displayName} (you)` : user.displayName })),
    { value: null, label: 'Unassigned' },
  ];
}
