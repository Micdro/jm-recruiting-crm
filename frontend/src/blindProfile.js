// Blind candidate profiles: what to leave out, and checking that it was left out.
import { fold } from './quickSearch.js';

/** A short reference for the candidate the client can quote back, such as JM-GJDGXS. */
export function profileReference(candidateId) {
  return `JM-${Number(candidateId).toString(36).toUpperCase()}`;
}

const escape = (text) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/** Everything about the candidate that could identify them, by what it is. */
export function identifiers(candidate) {
  const name = String(candidate.name ?? '').trim();
  const parts = name.split(/\s+/).filter((part) => part.length >= 3);
  const digits = String(candidate.phone ?? '').replace(/\D/g, '');
  const slug = String(candidate.linkedinUrl ?? '').match(/linkedin\.com\/in\/([^/?#]+)/i)?.[1];
  return [
    { label: 'their name', values: [name, ...parts] },
    { label: 'their email', values: [candidate.email] },
    { label: 'their phone number', values: [candidate.phone, digits.length >= 7 ? digits : null] },
    { label: 'their LinkedIn profile', values: [candidate.linkedinUrl, slug] },
    { label: 'their current employer', values: [candidate.currentCompany] },
  ].map((group) => ({ ...group, values: group.values.filter((value) => value && String(value).trim().length >= 3) }));
}

/** Replaced with words rather than removed. */
const NAMED = new Set(['their name', 'their current employer']);

/** The folded text, and for each of its characters where it came from in the original. */
function foldWithMap(text) {
  let folded = '';
  const map = [];
  let offset = 0;
  for (const char of text) {
    const part = fold(char);
    for (let step = 0; step < part.length; step += 1) map.push(offset);
    folded += part;
    offset += char.length;
  }
  map.push(text.length);
  return { folded, map };
}

function pattern(value) {
  // Whole words only, ignoring case and accents, so "Al" in "Alpha" isn't a match.
  return new RegExp(`(^|[^\\p{L}\\p{N}])(${escape(fold(value).trim())})(?=$|[^\\p{L}\\p{N}])`, 'giu');
}

/** Which identifying details still appear in any of these texts. */
export function mentions(texts, candidate) {
  const haystack = fold(texts.filter(Boolean).join('\n'));
  return identifiers(candidate)
    .filter((group) => group.values.some((value) => pattern(value).test(haystack)))
    .map((group) => group.label);
}

/**
 * Takes the candidate's identifying details out of a text written about them (their production
 * evidence, say): their name becomes "the candidate", their employer "their current employer", and
 * contact details are removed. Accented letters are kept as written where nothing is replaced.
 */
export function scrub(text, candidate) {
  if (!text) return '';
  const replacements = [];
  // Contact details first, so a name inside an email address goes with the address.
  const groups = identifiers(candidate);
  const ordered = [...groups.filter((group) => !NAMED.has(group.label)), ...groups.filter((group) => NAMED.has(group.label))];
  for (const group of ordered) {
    const replacement =
      group.label === 'their name' ? 'the candidate' : group.label === 'their current employer' ? 'their current employer' : '';
    // Longest first, so a full name goes before its parts.
    for (const value of [...group.values].sort((first, second) => second.length - first.length)) {
      replacements.push({ value, replacement });
    }
  }
  let result = text;
  for (const { value, replacement } of replacements) {
    // Match against the folded text, then cut the same span out of the original.
    const { folded, map } = foldWithMap(result);
    const regex = pattern(value);
    let match;
    let output = '';
    let last = 0;
    while ((match = regex.exec(folded)) !== null) {
      const start = map[match.index + match[1].length];
      const end = map[match.index + match[1].length + match[2].length];
      output += result.slice(last, start) + replacement;
      last = end;
    }
    if (last > 0) result = output + result.slice(last);
  }
  return result
    .replace(/(^|[.!?]\s+)the candidate/g, (whole, before) => `${before}The candidate`)
    .replace(/\(\s*\)/g, '')
    .replace(/[ \t]{2,}/g, ' ')
    .replace(/ +([,.;:])/g, '$1')
    .trim();
}
