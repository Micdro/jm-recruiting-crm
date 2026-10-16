// Spotting records that are probably the same person or company, so they can be merged. It works
// in the browser on the lists already loaded, so it sees the decrypted names and emails.
import { companyKeys } from './companyNames.js';
import { profileKey } from './linkedin.js';
import { fold } from './quickSearch.js';

const email = (value) => (value && String(value).trim() ? `email:${String(value).trim().toLowerCase()}` : null);
const profile = (url) => {
  const key = profileKey(url);
  return key ? `linkedin:${key}` : null;
};
const name = (value) => (value && fold(value).trim() ? fold(value).trim().replace(/\s+/g, ' ') : null);
const host = (website) => {
  if (!website || !String(website).trim()) return null;
  try {
    const text = String(website).trim();
    return `web:${new URL(text.includes('://') ? text : `https://${text}`).hostname.replace(/^www\./, '')}`;
  } catch {
    return null;
  }
};

/**
 * What makes two records look like the same one. Contacts: the same email, LinkedIn profile, or
 * name at the same company. Candidates: the same email, LinkedIn profile or name. Companies: the
 * same name however it's written ("Ledgerlight AI, Inc." and "Ledgerlight"), or the same website.
 */
const KEYS = {
  contacts: (record) => [email(record.email), profile(record.linkedinUrl), name(record.name) && `name:${name(record.name)}@${record.companyId}`],
  candidates: (record) => [email(record.email), profile(record.linkedinUrl), name(record.name) && `name:${name(record.name)}`],
  companies: (record) => [...companyKeys(record.name).map((key) => `company:${key}`), host(record.website)],
};

/**
 * Records that look like duplicates of each other, as a Map from id to a group number. Records
 * that share any key are in the same group, even through a third record.
 */
export function duplicateGroups(kind, records) {
  const parent = new Map();
  const find = (id) => {
    let root = id;
    while (parent.get(root) !== root) root = parent.get(root);
    parent.set(id, root);
    return root;
  };

  const owner = new Map();
  for (const record of records ?? []) {
    parent.set(record.id, record.id);
    for (const key of KEYS[kind](record)) {
      if (!key) continue;
      if (owner.has(key)) parent.set(find(record.id), find(owner.get(key)));
      else owner.set(key, record.id);
    }
  }

  const members = new Map();
  for (const record of records ?? []) {
    const root = find(record.id);
    members.set(root, [...(members.get(root) ?? []), record.id]);
  }

  const groups = new Map();
  let number = 0;
  for (const ids of members.values()) {
    if (ids.length < 2) continue;
    number += 1;
    ids.forEach((id) => groups.set(id, number));
  }
  return groups;
}

/** The rows to show when looking at possible duplicates: only those, each group together. */
export function duplicateRows(rows, groups) {
  return rows
    .filter((row) => groups.has(row.id))
    .sort((first, second) => groups.get(first.id) - groups.get(second.id));
}
