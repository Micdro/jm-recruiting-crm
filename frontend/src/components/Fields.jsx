import { useId } from 'react';

function Field({ label, hint, id, children, className = '' }) {
  return (
    <div className={`field ${className}`}>
      <label htmlFor={id}>{label}</label>
      {children}
      {hint && <p className="field-hint">{hint}</p>}
    </div>
  );
}

export function TextField({ label, hint, value, onChange, className, ...props }) {
  const id = useId();
  return (
    <Field label={label} hint={hint} id={id} className={className}>
      <input id={id} value={value ?? ''} onChange={(event) => onChange(event.target.value)} {...props} />
    </Field>
  );
}

export function TextArea({ label, hint, value, onChange, rows = 3, className, ...props }) {
  const id = useId();
  return (
    <Field label={label} hint={hint} id={id} className={className}>
      <textarea id={id} rows={rows} value={value ?? ''} onChange={(event) => onChange(event.target.value)} {...props} />
    </Field>
  );
}

/** options: [{ value, label }]. An empty choice is added unless `required`. */
export function SelectField({ label, hint, value, onChange, options, placeholder = 'None', required, className, ...props }) {
  const id = useId();
  return (
    <Field label={label} hint={hint} id={id} className={className}>
      <select
        id={id}
        value={value ?? ''}
        onChange={(event) => onChange(event.target.value)}
        required={required}
        {...props}
      >
        {(!required || !value) && <option value="">{placeholder}</option>}
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    </Field>
  );
}

export function CheckboxField({ label, checked, onChange, hint }) {
  const id = useId();
  return (
    <div className="field field-check">
      <input id={id} type="checkbox" checked={!!checked} onChange={(event) => onChange(event.target.checked)} />
      <label htmlFor={id}>{label}</label>
      {hint && <p className="field-hint">{hint}</p>}
    </div>
  );
}

export function OwnerField({ users, value, onChange, label = 'Owner' }) {
  return (
    <SelectField
      label={label}
      value={value}
      onChange={onChange}
      placeholder="Unassigned"
      options={users.filter((user) => user.active || String(user.id) === String(value)).map((user) => ({
        value: String(user.id),
        label: user.displayName,
      }))}
    />
  );
}
