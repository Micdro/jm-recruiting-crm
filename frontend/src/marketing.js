// Small helpers for the Marketing page, the post editor and the Today card.
import { api } from './api.js';
import { formatWeekday, parseDate, toIsoDate } from './format.js';
import { useLoader } from './hooks.js';

/** LinkedIn's limit for a post. */
export const POST_LIMIT = 3000;
export const TITLE_LIMIT = 200;

export const WEEKDAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];
export const WEEKDAY_NAMES = {
  MONDAY: 'Monday',
  TUESDAY: 'Tuesday',
  WEDNESDAY: 'Wednesday',
  THURSDAY: 'Thursday',
  FRIDAY: 'Friday',
  SATURDAY: 'Saturday',
  SUNDAY: 'Sunday',
};

/** The Monday of the week holding a YYYY-MM-DD day. */
export function mondayOf(iso) {
  const date = parseDate(iso);
  date.setDate(date.getDate() - ((date.getDay() + 6) % 7));
  return toIsoDate(date);
}

export function addDays(iso, days) {
  const date = parseDate(iso);
  date.setDate(date.getDate() + days);
  return toIsoDate(date);
}

/** YYYY-MM of a YYYY-MM-DD day, moved by some months. */
export function monthOf(iso, months = 0) {
  const date = parseDate(iso);
  date.setDate(1);
  date.setMonth(date.getMonth() + months);
  return toIsoDate(date).slice(0, 7);
}

export function monthName(month) {
  const [year, number] = month.split('-').map(Number);
  return new Intl.DateTimeFormat('en-US', { month: 'long', year: 'numeric' }).format(new Date(year, number - 1, 1));
}

/** "$0.02" from cents. */
export function dollars(cents) {
  return `$${(cents / 100).toFixed(2)}`;
}

/** "Today", "Tomorrow", or "Tue, Oct 20". */
export function dayLabel(iso, today) {
  if (iso === today) return 'Today';
  if (iso === addDays(today, 1)) return 'Tomorrow';
  return formatWeekday(iso);
}

/** Who a post goes up as: the company page, or its owner. */
export function postedAs(post) {
  return post.account === 'COMPANY_PAGE' ? 'Company page' : post.ownerName ?? 'Unassigned';
}

/** The signed-in person's LinkedIn connection on this computer, shared by every Post to LinkedIn button. */
export function useLinkedIn() {
  return useLoader(() => api.linkedin.status(), [], 'linkedin:status');
}

/**
 * What can be done to get a post onto LinkedIn from here: post it straight to the owner's profile
 * ('ready'), connect first, leave it to its owner, or copy it for the company page.
 */
export function postingState(post, user, linkedin) {
  if (!post || post.status === 'POSTED') return 'done';
  if (!post.body || !post.body.trim()) return 'unwritten';
  if (post.account === 'COMPANY_PAGE') return 'page';
  if (post.ownerId && user && post.ownerId !== user.id) return 'someone-else';
  if (!linkedin) return 'loading';
  if (!linkedin.me || linkedin.me.expired) return 'connect';
  return 'ready';
}
