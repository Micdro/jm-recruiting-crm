// A progress report for a client: how far their search has come, what moved lately, who is with
// them and what's needed from them. Worked out from the pipeline and the search's history.
import { profileReference } from './blindProfile.js';
import { addBusinessDays, formatLongDay, parseDate, toIsoDate } from './format.js';
import { STAGE } from './labels.js';

/** The stages in order, as far as a candidate can get. */
const ORDER = ['SOURCED', 'CONTACTED', 'ASSESSING', 'SHORTLISTED', 'SUBMITTED', 'INTERVIEWING', 'OFFER', 'PLACED'];
const rank = (stage) => ORDER.indexOf(stage);

/** How far someone who dropped out got at least, when the history doesn't say. */
const AT_LEAST = { CLIENT_PASSED: 'SUBMITTED', WITHDREW: 'CONTACTED' };

/** The rows of the funnel, as the client reads them. */
const FUNNEL = [
  ['SOURCED', 'Identified'],
  ['CONTACTED', 'Contacted'],
  ['ASSESSING', 'Assessed'],
  ['SHORTLISTED', 'Shortlisted'],
  ['SUBMITTED', 'Submitted to you'],
  ['INTERVIEWING', 'Interviewed'],
  ['OFFER', 'Offers'],
  ['PLACED', 'Placed'],
];

/** What reaching each stage reads as in a sentence: "3 contacted". */
const MOVED = {
  CONTACTED: 'contacted',
  ASSESSING: 'assessed',
  SHORTLISTED: 'shortlisted',
  SUBMITTED: 'submitted to you',
  INTERVIEWING: 'moved to interviews',
  OFFER: 'received offers',
  PLACED: 'placed',
  WITHDREW: 'withdrew',
};

/** Where the report says each candidate is with the client. */
const WITH_CLIENT = { OFFER: 'Offer made', INTERVIEWING: 'Interviewing', SUBMITTED: 'Submitted' };

/** The day an instant fell on, on this computer's calendar. */
const dayOf = (instant) => (instant ? toIsoDate(new Date(instant)) : null);
const daysBetween = (fromIso, toIso) => Math.round((parseDate(toIso) - parseDate(fromIso)) / 86_400_000);
const count = (number, one, many) => `${number} ${number === 1 ? one : many}`;

/** "Priya Natarajan", "Priya Natarajan and Sam Ortiz", "A, B and C". */
export function joinNames(names) {
  return names.length < 3 ? names.join(' and ') : `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`;
}

/**
 * The move a history line records: "FDE: Contacted to Assessing" is { from: 'CONTACTED', to:
 * 'ASSESSING' }; "Added to FDE as Sourced" is { from: null, to: 'SOURCED' }. Null for anything else.
 */
export function stageMove(summary) {
  const text = String(summary ?? '');
  for (const [to, label] of Object.entries(STAGE)) {
    if (text.startsWith('Added to ') && text.endsWith(` as ${label}`)) return { from: null, to };
    if (text.endsWith(` to ${label}`)) {
      const before = text.slice(0, -` to ${label}`.length);
      const from = Object.entries(STAGE).find(([, prior]) => before.endsWith(`: ${prior}`))?.[0] ?? null;
      return from ? { from, to } : null;
    }
  }
  return null;
}

/** The furthest stage each candidate reached in the search, by candidate id. */
function furthest(entries, history) {
  const best = new Map();
  const raise = (id, stage) => {
    if (rank(stage) < 0) return;
    if (!best.has(id) || rank(stage) > rank(best.get(id))) best.set(id, stage);
  };
  for (const activity of history ?? []) {
    if (activity.type !== 'STAGE_CHANGE' || !activity.candidateId) continue;
    const move = stageMove(activity.summary);
    if (move) {
      raise(activity.candidateId, move.from);
      raise(activity.candidateId, move.to);
    }
  }
  for (const entry of entries ?? []) {
    raise(entry.candidateId, entry.stage);
    raise(entry.candidateId, AT_LEAST[entry.stage]);
  }
  return best;
}

/** How many of the candidates in the search got at least as far as each stage. */
export function funnel(entries, history) {
  const reached = furthest(entries, history);
  const rows = FUNNEL.map(([stage, label]) => ({
    stage,
    label,
    count: (entries ?? []).filter((entry) => rank(reached.get(entry.candidateId)) >= rank(stage)).length,
  }));
  // Placed only shows once someone is.
  return rows.filter((row) => row.stage !== 'PLACED' || row.count > 0);
}

/**
 * What moved on or after {@code sinceIso}, as phrases: "4 new candidates identified", "2 submitted
 * to you". Each candidate counts once per stage.
 */
export function movesSince(history, sinceIso) {
  const added = new Set();
  const reached = new Map();
  for (const activity of history ?? []) {
    if (activity.type !== 'STAGE_CHANGE' || !activity.candidateId) continue;
    if (dayOf(activity.occurredAt) < sinceIso) continue;
    const move = stageMove(activity.summary);
    if (!move) continue;
    if (move.from === null) added.add(activity.candidateId);
    if (MOVED[move.to]) {
      if (!reached.has(move.to)) reached.set(move.to, new Set());
      reached.get(move.to).add(activity.candidateId);
    }
  }
  const phrases = added.size ? [count(added.size, 'new candidate identified', 'new candidates identified')] : [];
  for (const stage of [...ORDER, 'WITHDREW']) {
    if (reached.has(stage)) phrases.push(`${reached.get(stage).size} ${MOVED[stage]}`);
  }
  return phrases;
}

/** "Since Thursday, October 1: 4 new candidates identified and 2 submitted to you." */
export function sinceSentence(history, sinceIso) {
  const phrases = movesSince(history, sinceIso);
  const since = formatLongDay(sinceIso);
  return phrases.length ? `Since ${since}: ${joinNames(phrases)}.` : `No changes since ${since}.`;
}

/** How a candidate is named in the report: their name, or a reference like JM-GJDH17. */
export function namer(useReferences) {
  return (entry) => (useReferences ? profileReference(entry.candidateId) : entry.candidateName);
}

/** The candidates with the client now, furthest along first, with where each one stands. */
export function withClient(entries, today, who) {
  return (entries ?? [])
    .filter((entry) => WITH_CLIENT[entry.stage])
    .sort((first, second) => rank(second.stage) - rank(first.stage) || String(first.stageChangedAt).localeCompare(String(second.stageChangedAt)))
    .map((entry) => {
      const since = dayOf(entry.stageChangedAt);
      const days = since ? daysBetween(since, today) : null;
      let status = WITH_CLIENT[entry.stage];
      if (since) status += ` ${formatLongDay(since)}`;
      if (entry.stage === 'SUBMITTED' && days > 0) status += `. Waiting ${count(days, 'day', 'days')} for your feedback`;
      return { id: entry.id, who: who(entry), title: entry.candidateTitle ?? '', status: `${status}.` };
    });
}

/** A first draft of what the firm does next. */
export function suggestedNextSteps(entries) {
  const at = (stage) => (entries ?? []).filter((entry) => entry.stage === stage).length;
  const steps = [];
  if (at('SHORTLISTED')) steps.push(`Present ${count(at('SHORTLISTED'), 'shortlisted candidate', 'shortlisted candidates')} to you this week.`);
  if (at('ASSESSING')) steps.push(`Finish assessing ${count(at('ASSESSING'), 'candidate', 'candidates')}.`);
  const early = at('SOURCED') + at('CONTACTED');
  if (early) steps.push(`Keep working through the ${count(early, 'other candidate', 'other candidates')} identified so far.`);
  if (steps.length === 0) steps.push('Keep mapping the market for more candidates.');
  return steps.join('\n');
}

/** A first draft of what the firm needs from the client, with dates, so it's a clear ask. */
export function suggestedAsks(entries, today, who) {
  const at = (stage) => (entries ?? []).filter((entry) => entry.stage === stage).map(who);
  const asks = [];
  if (at('SUBMITTED').length) asks.push(`Your feedback on ${joinNames(at('SUBMITTED'))} by ${formatLongDay(addBusinessDays(today, 2))}.`);
  if (at('INTERVIEWING').length) asks.push(`Interview feedback and next steps for ${joinNames(at('INTERVIEWING'))}.`);
  if (at('OFFER').length) asks.push(`An update on the offer to ${joinNames(at('OFFER'))}.`);
  if (asks.length === 0) asks.push('A 15-minute call this week to review the search.');
  return asks.join('\n');
}

/** The day the last progress report was prepared, from the history, or null. */
export function lastReportDay(history) {
  const last = (history ?? []).find((activity) => activity.type === 'NOTE' && /^Progress report prepared\b/.test(activity.summary ?? ''));
  return last ? dayOf(last.occurredAt) : null;
}

/** What the history notes when a report is made. */
export function reportNote(preparedFor) {
  return `Progress report prepared${preparedFor ? ` for ${preparedFor}` : ''}.`;
}
