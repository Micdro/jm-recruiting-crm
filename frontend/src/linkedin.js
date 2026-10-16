// "Add to JM CRM": a bookmark that, on a LinkedIn profile, opens the CRM with that person's details.
import { sameCompany } from './companyNames.js';
import { fold } from './quickSearch.js';

/**
 * Runs on the LinkedIn page when the bookmark is clicked. It reads what's on the screen (name,
 * headline, current company, location, profile link) and opens the CRM's Add from LinkedIn page
 * with them. Nothing goes anywhere but this CRM. Kept as plain old JavaScript, since it runs in
 * LinkedIn's page rather than the app.
 */
const SCRAPER = String.raw`(function () {
  var crm = __CRM__;
  function text(selector) {
    var element = document.querySelector(selector);
    return element ? element.textContent.replace(/\s+/g, ' ').trim() : '';
  }
  function first() {
    for (var i = 0; i < arguments.length; i++) {
      var value = text(arguments[i]);
      if (value) return value;
    }
    return '';
  }
  if (!/(^|\.)linkedin\.com$/.test(location.hostname)) {
    alert('Open a LinkedIn profile first, then click Add to JM CRM.');
    return;
  }
  var title = document.title.replace(/^\(\d+\)\s*/, '').replace(/\s*\|\s*LinkedIn.*$/, '');
  var name = first('[data-anonymize="person-name"]', 'main h1', 'h1') || title.split(' - ')[0];
  var headline = first('[data-anonymize="headline"]', 'main .text-body-medium.break-words', 'main .text-body-medium');
  var place = first('[data-anonymize="location"]', 'main .text-body-small.inline.t-black--light.break-words',
    'main span.text-body-small.inline');
  var company = first('[data-anonymize="company-name"]');
  var current = document.querySelector('[aria-label^="Current company"]');
  if (!company && current) {
    company = current.getAttribute('aria-label').replace(/^Current company:\s*/, '').replace(/\.\s*Click.*$/, '').trim();
  }
  var params = new URLSearchParams({
    name: name, headline: headline, company: company, location: place, url: location.origin + location.pathname
  });
  window.open(crm + '/linkedin?' + params.toString(), '_blank');
})();`;

/** The bookmark's address, for a CRM at {@code origin} (http://localhost:4780 on the desktop). */
export function bookmarklet(origin) {
  return `javascript:${encodeURIComponent(SCRAPER.replace('__CRM__', JSON.stringify(origin)))}`;
}

/** "VP Engineering at Ledgerlight AI | Fraud ML" becomes a title and a company. */
export function parseHeadline(headline) {
  const lead = String(headline ?? '').split(/\s+[|•·]\s+/)[0].trim();
  const match = lead.match(/^(.*?)\s+(?:at|@)\s+(.+)$/i);
  return match ? { title: match[1].trim(), company: match[2].trim() } : { title: lead, company: '' };
}

/** The part of a LinkedIn link that identifies the person ("in/dana-reyes"), or null. */
export function profileKey(url) {
  const match = String(url ?? '')
    .toLowerCase()
    .match(/linkedin\.com\/(in|pub|sales\/lead|sales\/people|talent\/profile)\/([^/?#]+)/);
  if (!match) return null;
  try {
    return `${match[1]}/${decodeURIComponent(match[2])}`;
  } catch {
    return `${match[1]}/${match[2]}`;
  }
}

/** Contacts and candidates already in the CRM with the same LinkedIn profile or the same name. */
export function findExisting({ name, url }, contacts, candidates) {
  const key = profileKey(url);
  const sameName = fold(name).trim();
  const matches = (record) =>
    (key && profileKey(record.linkedinUrl) === key) || (sameName && fold(record.name).trim() === sameName);
  return [
    ...(contacts ?? []).filter(matches).map((record) => ({ kind: 'contact', record })),
    ...(candidates ?? []).filter(matches).map((record) => ({ kind: 'candidate', record })),
  ];
}

/** The company with this name, however it's written ("Ledgerlight AI, Inc." finds Ledgerlight AI). */
export function findCompany(name, companies) {
  const wanted = fold(name).trim();
  if (!wanted) return null;
  const list = companies ?? [];
  return (
    list.find((company) => fold(company.name).trim() === wanted) ?? list.find((company) => sameCompany(company.name, name)) ?? null
  );
}

/** "Staff ML Engineer at Cardwise", or whichever part there is. */
export function describeJob(title, company) {
  return [title, company].filter(Boolean).join(' at ');
}

/**
 * How someone's job on LinkedIn differs from their record, or null when it doesn't: a new employer
 * or a new title. {@code job} is what LinkedIn shows; leave its title empty when the headline
 * doesn't clearly give one ("Helping banks ship AI" isn't a title).
 */
export function jobChange(kind, record, job) {
  const was = kind === 'contact' ? { title: record.title, company: record.companyName } : { title: record.currentTitle, company: record.currentCompany };
  const companyChanged = Boolean(job.company) && !sameCompany(job.company, was.company);
  const titleChanged = Boolean(job.title) && fold(job.title).trim() !== fold(was.title ?? '').trim();
  if (!companyChanged && !titleChanged) return null;
  return {
    companyChanged,
    title: job.title || (companyChanged ? '' : was.title),
    company: job.company || was.company,
    now: describeJob(job.title || (companyChanged ? '' : was.title), job.company || was.company),
    was: describeJob(was.title, was.company),
  };
}
