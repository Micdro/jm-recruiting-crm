import { useId } from 'react';

/** The search box and dropdowns above a list. Values live in the URL so links and refreshes keep them. */
export function SearchBox({ value, onChange, placeholder }) {
  const id = useId();
  return (
    <div className="filter filter-search">
      <label htmlFor={id} className="visually-hidden">
        Search
      </label>
      <input id={id} type="search" value={value} onChange={(event) => onChange(event.target.value)} placeholder={placeholder} />
    </div>
  );
}

export function FilterSelect({ label, value, onChange, options, allLabel = 'All' }) {
  const id = useId();
  return (
    <div className="filter">
      <label htmlFor={id}>{label}</label>
      <select id={id} value={value} onChange={(event) => onChange(event.target.value)}>
        <option value="">{allLabel}</option>
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    </div>
  );
}

export function FilterCheck({ label, checked, onChange }) {
  const id = useId();
  return (
    <div className="filter filter-check">
      <input id={id} type="checkbox" checked={checked} onChange={(event) => onChange(event.target.checked)} />
      <label htmlFor={id}>{label}</label>
    </div>
  );
}
