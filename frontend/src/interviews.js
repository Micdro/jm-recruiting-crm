import { formatWeekday, parseDate, toIsoDate } from './format.js';

/** Stages where an interview can be scheduled from a pipeline card. Scheduling moves earlier ones to Interviewing. */
export const INTERVIEW_STAGES = ['SHORTLISTED', 'SUBMITTED', 'INTERVIEWING', 'OFFER'];

const CLOCK = new Intl.DateTimeFormat('en-US', { hour: 'numeric', minute: '2-digit' });

/** "14:00:00" or "14:00" as "2:00 PM". */
export function formatClock(time) {
  if (!time) return '';
  const [hours, minutes] = time.split(':').map(Number);
  return CLOCK.format(new Date(2000, 0, 1, hours, minutes));
}

/** "today at 2:00 PM", "yesterday", "Tue, Oct 20 at 2:00 PM": when an interview is, from the server's today. */
export function interviewWhen(entry, today) {
  const yesterday = parseDate(today);
  yesterday?.setDate(yesterday.getDate() - 1);
  let day = formatWeekday(entry.interviewDate);
  if (entry.interviewDate === today) day = 'today';
  else if (yesterday && entry.interviewDate === toIsoDate(yesterday)) day = 'yesterday';
  return entry.interviewTime ? `${day} at ${formatClock(entry.interviewTime)}` : day;
}
