import { useEffect, useRef, useState } from 'react';
import { formatWeekday } from '../format.js';
import { LATER_OPTIONS, laterDate } from '../later.js';

/**
 * "Later" on a Today row: moves the follow-up to the next business day or a week out without
 * opening the record. Each choice shows the day it lands on.
 */
export default function LaterMenu({ name, today, disabled = false, onPick }) {
  const [open, setOpen] = useState(false);
  const wrapper = useRef(null);
  const toggle = useRef(null);

  useEffect(() => {
    if (!open) return undefined;
    // Start on the first choice, so the keyboard can pick with Enter or move on with Tab.
    wrapper.current?.querySelector('.later-option')?.focus();

    function clickAway(event) {
      if (!wrapper.current?.contains(event.target)) setOpen(false);
    }
    function escape(event) {
      if (event.key === 'Escape') {
        setOpen(false);
        toggle.current?.focus();
      }
    }
    document.addEventListener('mousedown', clickAway);
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('mousedown', clickAway);
      document.removeEventListener('keydown', escape);
    };
  }, [open]);

  return (
    <div className="later" ref={wrapper}>
      <button
        ref={toggle}
        type="button"
        className="btn btn-sm btn-quiet"
        aria-expanded={open}
        aria-label={`Move ${name} to later`}
        disabled={disabled}
        onClick={() => setOpen((value) => !value)}
      >
        Later
      </button>
      {open && (
        <div className="later-menu" role="group" aria-label={`Move ${name} to`}>
          {LATER_OPTIONS.map(([until, label]) => {
            const day = formatWeekday(laterDate(until, today));
            return (
              <button
                key={until}
                type="button"
                className="later-option"
                aria-label={`${label}, ${day}`}
                onClick={() => {
                  setOpen(false);
                  onPick(until);
                }}
              >
                <span>{label}</span>
                <span className="later-day">{day}</span>
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}
