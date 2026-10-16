// Telling whether two names are the same company, however they were written.
import { fold } from './quickSearch.js';

/** Words that don't tell companies apart: "Ledgerlight AI, Inc." is the same as "Ledgerlight". */
const LEGAL = new Set(['inc', 'incorporated', 'llc', 'llp', 'lp', 'ltd', 'limited', 'corp', 'corporation', 'co', 'company', 'plc', 'gmbh', 'ag', 'sa', 'bv']);
const GENERIC = new Set(['ai', 'tech', 'technology', 'technologies', 'labs', 'software', 'systems', 'group', 'holdings', 'hq']);

/**
 * The ways a company's name can be written, for comparing: without legal endings, then also
 * without words like "AI" or "Technologies", each with and without spaces. "Open AI" and
 * "OpenAI" match; so do "Ledgerlight AI" and "Ledgerlight, Inc.".
 */
export function companyKeys(name) {
  // "Ledgerlight AI · Full-time" or "Ledgerlight (acquired by Northwind)": the name comes first.
  const lead = String(name ?? '')
    .split(/\s[·|•]\s|\(/)[0]
    .replace(/&/g, ' and ');
  const words = fold(lead)
    .split(/[^\p{L}\p{N}]+/u)
    .filter(Boolean);
  if (words[0] === 'the' && words.length > 1) words.shift();
  while (words.length > 1 && LEGAL.has(words[words.length - 1])) words.pop();
  const core = [...words];
  while (core.length > 1 && (GENERIC.has(core[core.length - 1]) || LEGAL.has(core[core.length - 1]))) core.pop();
  if (words.length === 0) return [];
  return [...new Set([words.join(' '), words.join(''), core.join(' '), core.join('')])];
}

/** True when two names are the same company. */
export function sameCompany(first, second) {
  const keys = new Set(companyKeys(first));
  return companyKeys(second).some((key) => keys.has(key));
}
