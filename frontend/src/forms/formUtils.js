/** Blank strings become null so the API stores nothing rather than "". */
export function blankToNull(value) {
  if (value === undefined || value === null) return null;
  if (typeof value === 'string') {
    const trimmed = value.trim();
    return trimmed === '' ? null : trimmed;
  }
  return value;
}

export function toNumber(value) {
  if (value === undefined || value === null || value === '') return null;
  const number = Number(String(value).replace(/[$,\s]/g, ''));
  return Number.isFinite(number) ? number : null;
}

export function toId(value) {
  return value === undefined || value === null || value === '' ? null : Number(value);
}

/** Turns API records into form state, where every field is a string. */
export function asForm(record, fields) {
  const form = {};
  for (const [key, fallback] of Object.entries(fields)) {
    const value = record?.[key];
    if (value === undefined || value === null) form[key] = fallback;
    else if (typeof value === 'boolean' || Array.isArray(value)) form[key] = value;
    else form[key] = String(value);
  }
  return form;
}
