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

/**
 * "Log to JM CRM": runs on a LinkedIn conversation (the messaging page or a chat window) or a
 * profile. It reads who the conversation is with, who sent the last message and what it said, and
 * opens the CRM's log page with them, where the person confirms what to log. LinkedIn doesn't
 * document its pages, so every guess here is shown in the CRM to check. Plain old JavaScript.
 */
const LOGGER = String.raw`(function () {
  var crm = __CRM__;
  if (!/(^|\.)linkedin\.com$/.test(location.hostname)) {
    alert('Open a LinkedIn conversation or profile first, then click Log to JM CRM.');
    return;
  }
  function clean(value) {
    return String(value || '').replace(/\s+/g, ' ').trim();
  }
  function profile(href) {
    var match = String(href || '').match(/linkedin\.com\/(in|sales\/lead|sales\/people)\/[^\/?#]+/i);
    return match ? 'https://www.' + match[0] + '/' : '';
  }
  function within(scope, selectors) {
    for (var i = 0; i < selectors.length; i++) {
      var found = scope.querySelector(selectors[i]);
      if (found) return found;
    }
    return null;
  }
  function all(scope, selector) {
    return Array.prototype.slice.call(scope.querySelectorAll(selector));
  }

  var meImage = document.querySelector('img.global-nav__me-photo, .global-nav__me img');
  var me = meImage ? clean(meImage.getAttribute('alt')) : '';

  // The open conversation: the messaging page's thread, or the chat window that's open.
  var scope = within(document, ['.msg-convo-wrapper', '.msg-thread', '.msg-s-message-list-container']);
  if (!scope) {
    var windows = all(document, '.msg-overlay-conversation-bubble').filter(function (bubble) {
      return !/minimized|collapsed/.test(bubble.className) && bubble.querySelector('.msg-s-message-list, .msg-s-event-listitem');
    });
    scope = windows.length ? windows[windows.length - 1] : null;
  }

  var params = { page: location.origin + location.pathname };
  if (scope) {
    // Their name and profile from the conversation's header, else the first profile link in it.
    var header = within(scope, ['.msg-thread__link-to-profile', '.msg-entity-lockup__entity-title a',
      '.msg-overlay-bubble-header a[href*="/in/"]', 'header a[href*="/in/"]', 'a[href*="/in/"]']);
    var title = within(scope, ['.msg-entity-lockup__entity-title', '#thread-detail-jump-target',
      '.msg-overlay-bubble-header__title', 'h2']);
    params.name = clean(title ? title.textContent : header ? header.textContent : '');
    params.url = profile(header && header.href);
    var others = [];
    all(scope, 'a[href*="/in/"], a[href*="/sales/lead/"]').forEach(function (link) {
      var url = profile(link.href);
      if (url && url !== params.url && others.indexOf(url) < 0 && clean(link.textContent) !== me) others.push(url);
    });
    params.others = others.slice(0, 8).join(' ');

    // Who wrote last: the last sender shown in the thread, or LinkedIn's mark on others' messages.
    var senders = all(scope, '.msg-s-message-group__name, .msg-s-message-group__profile-link');
    var last = senders.length ? senders[senders.length - 1] : null;
    var events = all(scope, '.msg-s-event-listitem');
    var lastEvent = events.length ? events[events.length - 1] : null;
    var from = '';
    if (last) {
      var lastName = clean(last.textContent);
      var lastUrl = profile(last.href || (last.querySelector('a') || {}).href);
      if ((me && lastName === me) || (lastUrl && params.url && lastUrl !== params.url)) from = 'me';
      else if ((params.name && lastName === params.name) || (lastUrl && lastUrl === params.url)) from = 'them';
    }
    if (!from && lastEvent && scope.querySelector('.msg-s-event-listitem--other')) {
      from = /msg-s-event-listitem--other/.test(lastEvent.className) ? 'them' : 'me';
    }
    params.from = from;
    var bodies = all(scope, '.msg-s-event-listitem__body');
    params.text = bodies.length ? clean(bodies[bodies.length - 1].textContent).slice(0, 400) : '';
    var times = all(scope, '.msg-s-message-group__timestamp, time');
    params.when = times.length ? clean(times[times.length - 1].textContent) : '';
  } else {
    // A profile: just the person.
    var heading = document.querySelector('[data-anonymize="person-name"], main h1, h1');
    params.name = clean(heading ? heading.textContent : document.title.split(' - ')[0].replace(/^\(\d+\)\s*/, ''));
    params.url = profile(location.href);
  }
  if (!params.name && !params.url) {
    alert('JM CRM could not find who this is. Open the conversation, or their profile, and try again.');
    return;
  }
  window.open(crm + '/linkedin/log?' + new URLSearchParams(params).toString(), '_blank');
})();`;

/** The Log to JM CRM bookmark's address, for a CRM at {@code origin}. */
export function logBookmarklet(origin) {
  return `javascript:${encodeURIComponent(LOGGER.replace('__CRM__', JSON.stringify(origin)))}`;
}

/**
 * Who a logged conversation is with, among contacts and candidates: by the profile in the
 * conversation's header first, then any other profile linked in it, then by name.
 */
export function findPerson({ name, url, others }, contacts, candidates) {
  const byUrl = (link) => findExisting({ name: '', url: link }, contacts, candidates);
  const first = url ? byUrl(url) : [];
  if (first.length) return first;
  for (const link of String(others ?? '').split(/\s+/).filter(Boolean)) {
    const found = byUrl(link);
    if (found.length) return found;
  }
  return name ? findExisting({ name, url: '' }, contacts, candidates) : [];
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
