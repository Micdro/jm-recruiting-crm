import { useCallback } from 'react';
import { useNavigate } from 'react-router';
import { api, errorMessage } from './api.js';
import { useToast } from './hooks.js';

/**
 * After a delete: a confirmation with Undo, which puts the record back from the Trash and opens
 * it (or calls {@code onRestored} instead, to refresh the page that's open).
 */
export function useUndoableDelete() {
  const toast = useToast();
  const navigate = useNavigate();
  return useCallback(
    (kind, recordId, message, onRestored) => {
      toast(message, {
        action: {
          label: 'Undo',
          onClick: async () => {
            try {
              const restored = await api.trash.undo(kind, recordId);
              toast(`Restored ${restored.label}`);
              if (onRestored) onRestored(restored);
              else navigate(restored.path);
            } catch (err) {
              toast(errorMessage(err));
            }
          },
        },
      });
    },
    [toast, navigate],
  );
}

/**
 * After logging a touch or moving a follow-up: a confirmation with Undo, which puts the record
 * back the way it was and removes the history entry, so the day's tally drops it too. The server
 * keeps the change for 10 minutes, on the computer it was made on.
 *
 * kind is 'contacts', 'candidates' or 'companies'; onUndone gets the restored record.
 */
export function useUndoableChange() {
  const toast = useToast();
  return useCallback(
    (kind, recordId, message, onUndone) => {
      toast(message, {
        action: {
          label: 'Undo',
          onClick: async () => {
            try {
              const restored = await api[kind].undo(recordId);
              toast('Undone.');
              if (onUndone) onUndone(restored);
            } catch (err) {
              toast(errorMessage(err));
            }
          },
        },
      });
    },
    [toast],
  );
}
