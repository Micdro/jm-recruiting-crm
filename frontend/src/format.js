// Dates come from the API as YYYY-MM-DD (dates) or ISO instants (timestamps).

const MONTH_DAY = new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric' });
const MONTH_DAY_YEAR = new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
const WEEKDAY = new Intl.DateTimeFormat('en-US', { weekday: 'short', month: 'short', day: 'numeric' });
const LONG_DAY = new Intl.DateTimeFormat('en-US', { weekday: 'long', month: 'long', day: 'numeric' });
const TIME = new Intl.DateTimeFormat('en-US', { hour: 'numeric', minute: '2-digit' });
const MONEY = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });

/** Parses YYYY-MM-DD as a local calendar date (not UTC midnight). */
export function parseDate(iso) {
  if (!iso) return null;
  const [year, month, day] = iso.split('-').map(Number);
  return new Date(year, month - 1, day);
}

export function toIsoDate(date) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

export function todayIso() {
  return toIsoDate(new Date());
}

export function formatDate(iso) {
  const date = parseDate(iso);
  if (!date) return '';
  return date.getFullYear() === new Date().getFullYear() ? MONTH_DAY.format(date) : MONTH_DAY_YEAR.format(date);
}

export function formatWeekday(iso) {
  const date = parseDate(iso);
  return date ? WEEKDAY.format(date) : '';
}

export function formatLongDay(iso) {
  const date = parseDate(iso);
  return date ? LONG_DAY.format(date) : '';
}

export function formatTimestamp(instant) {
  if (!instant) return '';
  const date = new Date(instant);
  const today = new Date();
  const sameDay = date.toDateString() === today.toDateString();
  const yesterday = new Date(today);
  yesterday.setDate(today.getDate() - 1);

  if (sameDay) return `Today ${TIME.format(date)}`;
  if (date.toDateString() === yesterday.toDateString()) return `Yesterday ${TIME.format(date)}`;
  return `${formatDate(toIsoDate(date))}, ${TIME.format(date)}`;
}

function daysBetween(fromIso, toIso) {
  const from = parseDate(fromIso);
  const to = parseDate(toIso);
  return Math.round((to - from) / 86_400_000);
}

/** "Overdue 3 days", "Today", "Tomorrow" or a date, plus a tone for styling. */
export function dueInfo(iso, today = todayIso()) {
  if (!iso) return { label: 'Nothing scheduled', tone: 'none' };
  const diff = daysBetween(today, iso);
  if (diff < 0) {
    const days = -diff;
    return { label: `Overdue ${days} ${days === 1 ? 'day' : 'days'}`, tone: 'overdue' };
  }
  if (diff === 0) return { label: 'Today', tone: 'today' };
  if (diff === 1) return { label: 'Tomorrow', tone: 'soon' };
  return { label: formatWeekday(iso), tone: 'later' };
}

export function addBusinessDays(iso, count) {
  const date = parseDate(iso);
  let added = 0;
  while (added < count) {
    date.setDate(date.getDate() + 1);
    const day = date.getDay();
    if (day !== 0 && day !== 6) added += 1;
  }
  return toIsoDate(date);
}

export function money(value) {
  if (value === null || value === undefined || value === '') return '';
  return MONEY.format(Number(value));
}

export function compactMoney(value) {
  if (value === null || value === undefined) return '';
  const number = Number(value);
  if (number >= 1000) return `$${Math.round(number / 1000)}k`;
  return MONEY.format(number);
}

export function salaryRange(min, max) {
  if (min && max) return `${compactMoney(min)} to ${compactMoney(max)}`;
  if (min) return `From ${compactMoney(min)}`;
  if (max) return `Up to ${compactMoney(max)}`;
  return '';
}

export function percent(value) {
  if (value === null || value === undefined || value === '') return '';
  return `${Number(value)}%`;
}

/** Turns a website or LinkedIn value into a clickable URL. */
export function href(url) {
  if (!url) return null;
  return /^https?:\/\//i.test(url) ? url : `https://${url}`;
}

export function plural(count, one, many = `${one}s`) {
  return `${count} ${count === 1 ? one : many}`;
}
