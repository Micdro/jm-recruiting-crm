import { parseDate, toIsoDate } from './format.js';

/** The choices under Later on a Today row. The server works out the dates; these are for the labels. */
export const LATER_OPTIONS = [
  ['TOMORROW', 'Tomorrow'],
  ['NEXT_WEEK', 'Next week'],
];

/**
 * Where Later moves a follow-up, by the same rules as the server: Tomorrow is the next business
 * day (Friday goes to Monday), Next week is the same weekday a week out, and neither lands on a
 * weekend. Counted from the server's today, so the label matches what will be saved.
 */
export function laterDate(until, todayIso) {
  const date = parseDate(todayIso);
  if (!date) return null;
  date.setDate(date.getDate() + (until === 'NEXT_WEEK' ? 7 : 1));
  while (date.getDay() === 0 || date.getDay() === 6) {
    date.setDate(date.getDate() + 1);
  }
  return toIsoDate(date);
}

/** Days after a sequence ends with no reply before the contact is back on Today to restart. */
export const REVISIT_DAYS = 90;

/** When a sequence that ends today comes back, by the server's rule: 90 days on, never a weekend. */
export function revisitDate(todayIso) {
  const date = parseDate(todayIso);
  if (!date) return null;
  date.setDate(date.getDate() + REVISIT_DAYS);
  while (date.getDay() === 0 || date.getDay() === 6) {
    date.setDate(date.getDate() + 1);
  }
  return toIsoDate(date);
}
