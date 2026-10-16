// Off-limits: the firm doesn't recruit a client's people while it works for them, nor for a year
// after its last placement there starts. The server works out which companies are off-limits;
// this matches a candidate's employer to them.
import { formatDate } from './format.js';
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

/** The off-limits company someone works at ({@code employer} is the name they gave), or null. */
export function offLimitsEmployer(employer, companies) {
  if (!employer || !String(employer).trim()) return null;
  return (companies ?? []).find((company) => company.offLimits && sameCompany(company.name, employer)) ?? null;
}

/** The off-limits company a candidate works at, or null. */
export function candidateOffLimits(candidate, companies) {
  return offLimitsEmployer(candidate?.currentCompany, companies);
}

/** "Off-limits" or "Off-limits until Oct 15, 2027", for a tag. */
export function offLimitsLabel(company) {
  const { client, until } = company.offLimits;
  return !client && until ? `Off-limits until ${formatDate(until)}` : 'Off-limits';
}

/** What off-limits means for this company, for its page. */
export function offLimitsNote(company) {
  const { client, until } = company.offLimits;
  return client
    ? `Its people are off-limits while you work for ${company.name}, and for a year after the last placement starts.`
    : `Its people are off-limits until ${formatDate(until)}.`;
}

/** The warning for someone who works at an off-limits company. */
export function offLimitsWarning(name, company) {
  const who = String(name ?? '').trim().split(/\s+/)[0] || 'This candidate';
  const { client, until } = company.offLimits;
  return client
    ? `Off-limits: ${who} works at ${company.name}, a client. Don’t recruit from a client while you work for them.`
    : `Off-limits until ${formatDate(until)}: ${who} works at ${company.name}, a recent client.`;
}
