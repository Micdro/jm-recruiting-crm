import { useEffect, useRef, useState } from 'react';

/**
 * A small button that opens a list of choices and closes once one is picked. Used for Later on
 * Today and for the bulk actions on lists. Escape or a click elsewhere closes it.
 *
 * choices: [{ value, label, detail, ariaLabel }]; detail is shown faintly on the right.
 */
export default function ChoiceMenu({
  label,
  buttonLabel,
  groupLabel,
  choices,
  onPick,
  disabled = false,
  buttonClass = 'btn btn-sm btn-quiet',
}) {
  const [open, setOpen] = useState(false);
  const wrapper = useRef(null);
  const toggle = useRef(null);

  useEffect(() => {
    if (!open) return undefined;
    // Start on the first choice, so the keyboard can pick with Enter or move on with Tab.
    wrapper.current?.querySelector('.choice-option')?.focus();

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
    <div className="choice-menu" ref={wrapper}>
      <button
        ref={toggle}
        type="button"
        className={buttonClass}
        aria-expanded={open}
        aria-label={buttonLabel}
        disabled={disabled}
        onClick={() => setOpen((value) => !value)}
      >
        {label}
      </button>
      {open && (
        <div className="choice-list" role="group" aria-label={groupLabel}>
          {choices.map((choice) => (
            <button
              key={choice.value ?? 'none'}
              type="button"
              className="choice-option"
              aria-label={choice.ariaLabel}
              onClick={() => {
                setOpen(false);
                onPick(choice.value);
              }}
            >
              <span>{choice.label}</span>
              {choice.detail && <span className="choice-detail">{choice.detail}</span>}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
