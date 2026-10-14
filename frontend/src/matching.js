// Which candidates fit a search, and why: scored from the brief and the candidate's record.
import { compactMoney } from './format.js';
import { EXPERIENCE } from './labels.js';
import { fold } from './quickSearch.js';

/** Statuses worth suggesting. Placed, not looking, not a fit and do not contact are left out. */
const OPEN = new Set(['ACTIVE', 'PASSIVE']);
const SENIOR = /\b(staff|principal|lead|head|director|senior|sr|vp|chief|architect)\b/i;
const SENIOR_ENOUGH = new Set(['YEARS_8_9', 'YEARS_10_PLUS']);
const JUNIOR = new Set(['YEARS_1_2', 'YEARS_3_4']);

/** Short words that still mean something in this field. */
const KEEP_SHORT = new Set(['ml', 'ai', 'llm', 'aml', 'kyc', 'gpu', 'api', 'aws', 'gcp', 'rag', 'nlp', 'etl', 'sre', 'sox', 'pci']);
const STOP = new Set(
  `about above across after also among and any are around based been before being both build builds built building
  but can company could during each engineer engineers engineering experience from have help helps into its just lead
  leads led like make more most must need needs new not only other over own owned owner owning owns part plus role
  same senior should staff such team teams than that the their them then there these they this those through under
  using used uses very want was well were what when where which while who will with within work worked working works
  would year years your`.split(/\s+/),
);

/** The meaningful words in a text: folded, without common words, plurals trimmed. */
export function terms(text) {
  const found = new Set();
  // Hyphenated words stay whole: "real-time", "tier-1".
  for (const word of fold(text).split(/[^\p{L}\p{N}-]+/u)) {
    const raw = word.replace(/^-+|-+$/g, '');
    if (!raw || STOP.has(raw) || /^\d+$/.test(raw)) continue;
    if (raw.length < 4 && !KEEP_SHORT.has(raw)) continue;
    found.add(raw.length > 4 && raw.endsWith('s') && !raw.endsWith('ss') ? raw.slice(0, -1) : raw);
  }
  return found;
}

/**
 * The lowest pay figure in a candidate's expectation ("$240k base", "220-250k", "250,000"), in
 * dollars, or null. Only amounts that look like salaries count, so "401k" or "10% bonus" don't.
 */
export function expectedPay(text) {
  if (!text) return null;
  const amounts = [];
  const pattern = /(\$)?\s*(\d{1,3}(?:,\d{3})+|\d+(?:\.\d+)?)\s*(k\b)?/gi;
  let match;
  while ((match = pattern.exec(String(text))) !== null) {
    const [whole, dollar, digits, thousands] = match;
    const number = Number(digits.replace(/,/g, ''));
    if (/401\s*k/i.test(whole)) continue;
    let amount = null;
    if (thousands || (dollar && number < 1000)) amount = number * 1000;
    else if (number >= 40_000) amount = number;
    if (amount && amount >= 40_000 && amount <= 2_000_000) amounts.push(amount);
  }
  // "220-250k": the k covers both numbers.
  const range = String(text).match(/(\d{2,3})\s*(?:-|to|–)\s*\$?\s*(\d{2,3})\s*k\b/i);
  if (range) amounts.push(Number(range[1]) * 1000);
  return amounts.length ? Math.min(...amounts) : null;
}

/** "New York, NY" as its city and region, folded, for comparing places. */
function place(location) {
  const parts = fold(location)
    .split(',')
    .map((part) => part.trim())
    .filter(Boolean);
  if (parts.length === 0) return null;
  return { city: parts[0], region: parts.length > 1 ? parts[parts.length - 1] : null };
}

/**
 * How well one candidate fits a search, from 0 up (about 100 for a very strong fit), with the
 * reasons: what fits ({ good: true }) and what might not.
 */
export function scoreCandidate(search, candidate, searchTerms = briefTerms(search)) {
  let score = 0;
  const reasons = [];
  const good = (points, text) => {
    score += points;
    if (text) reasons.push({ good: true, text });
  };
  const concern = (points, text) => {
    score -= points;
    reasons.push({ good: false, text });
  };

  if (search.track && candidate.track) {
    if (search.track === candidate.track) good(30, 'Same track');
    else concern(15, 'Different track');
  }
  if (candidate.regulatedExperience) good(20, 'Regulated financial services');

  const wanted = place(search.location);
  const theirs = place(candidate.location);
  if (search.workModel === 'REMOTE') {
    good(15, 'Remote role');
  } else if (wanted && theirs) {
    if (wanted.city === theirs.city) good(15, `Based in ${candidate.location}`);
    else if (wanted.region && wanted.region === theirs.region) good(8, `Nearby, in ${candidate.location}`);
    else if (candidate.willingToRelocate) good(8, 'Open to relocating');
    else concern(10, `Based in ${candidate.location} and not open to relocating`);
  } else if (wanted && candidate.willingToRelocate) {
    good(5, 'Open to relocating');
  }
  if (search.workModel && candidate.workPreference) {
    if (search.workModel === candidate.workPreference) good(5);
    else if (candidate.workPreference === 'REMOTE' && search.workModel === 'ONSITE') concern(10, 'Wants remote; the role is on-site');
    else if (candidate.workPreference === 'REMOTE' && search.workModel === 'HYBRID') concern(5, 'Prefers remote; the role is hybrid');
  }

  const pay = expectedPay(candidate.compExpectation);
  if (pay && search.baseMax) {
    if (pay <= search.baseMax) good(10, `Asks ${compactMoney(pay)}, within the range`);
    else if (pay > search.baseMax * 1.1) concern(15, `Asks ${compactMoney(pay)}, above the ${compactMoney(search.baseMax)} top`);
    else concern(5, `Asks ${compactMoney(pay)}, a little above the range`);
  }

  if (SENIOR.test(search.title ?? '') && candidate.experience) {
    if (SENIOR_ENOUGH.has(candidate.experience)) good(5, EXPERIENCE[candidate.experience]);
    else if (JUNIOR.has(candidate.experience)) concern(10, `${EXPERIENCE[candidate.experience]} for a senior role`);
  }

  const theirTerms = terms(`${candidate.currentTitle ?? ''} ${candidate.productionEvidence ?? ''}`);
  const shared = [...searchTerms].filter((term) => theirTerms.has(term));
  if (shared.length) good(Math.min(24, shared.length * 3), `Mentions ${shared.slice(0, 4).join(', ')}`);

  if (candidate.status === 'ACTIVE') good(5, 'Actively looking');

  return { score, reasons };
}

/** The words in a search's brief that a candidate's record could share. */
export function briefTerms(search) {
  return terms(
    [search.title, search.technicalScope, search.productionOutcome, search.accountability, search.regulatoryConstraints]
      .filter(Boolean)
      .join(' '),
  );
}

export function matchLevel(score) {
  if (score >= 60) return 'Strong match';
  if (score >= 35) return 'Good match';
  return 'Possible match';
}

/**
 * Candidates worth adding to a search, best first: open to a move, not in it already, and with
 * at least something in their favor.
 */
export function matchCandidates(search, candidates, alreadyIn = new Set()) {
  const searchTerms = briefTerms(search);
  return (candidates ?? [])
    .filter((candidate) => OPEN.has(candidate.status) && !alreadyIn.has(candidate.id))
    .map((candidate) => ({ candidate, ...scoreCandidate(search, candidate, searchTerms) }))
    .filter((match) => match.score >= 15 && match.reasons.some((reason) => reason.good))
    .sort((first, second) => second.score - first.score || first.candidate.name.localeCompare(second.candidate.name))
    .map((match) => ({ ...match, level: matchLevel(match.score) }));
}
