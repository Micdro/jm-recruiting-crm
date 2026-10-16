// Off-limits: the firm doesn't recruit a client's people while it works for them, nor for a year
// after its last placement there starts. The server works out which companies are off-limits;
// this matches a candidate's employer to them.
import { sameCompany } from './companyNames.js';
import { formatDate } from './format.js';

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
