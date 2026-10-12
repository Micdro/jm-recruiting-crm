import { useCallback, useEffect, useRef, useState } from 'react';
import { ToastContext } from '../contexts.js';

/**
 * One short confirmation at a time, announced to screen readers. A toast can carry one action
 * (Undo, say); it then stays up a little longer so there's time to click it.
 *
 * toast('Saved') or toast('Deleted Dana Reyes', { action: { label: 'Undo', onClick } })
 */
export default function ToastProvider({ children }) {
  const [toast, setToast] = useState(null);
  const timer = useRef(null);

  const show = useCallback((message, options = {}) => {
    const { tone = 'good', action = null } = typeof options === 'string' ? { tone: options } : options;
    clearTimeout(timer.current);
    setToast({ message, tone, action, id: Date.now() });
    timer.current = setTimeout(() => setToast(null), action ? 9000 : 4200);
  }, []);

  useEffect(() => () => clearTimeout(timer.current), []);

  return (
    <ToastContext.Provider value={show}>
      {children}
      <div className="toast-region" role="status" aria-live="polite">
        {toast && (
          <div key={toast.id} className={`toast toast-${toast.tone}`}>
            {toast.message}
            {toast.action && (
              <button
                type="button"
                className="toast-action"
                onClick={() => {
                  clearTimeout(timer.current);
                  setToast(null);
                  toast.action.onClick();
                }}
              >
                {toast.action.label}
              </button>
            )}
          </div>
        )}
      </div>
    </ToastContext.Provider>
  );
}
