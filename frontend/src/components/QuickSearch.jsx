import { useEffect, useId, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import { api } from '../api.js';
import { useDesktop, useLoader } from '../hooks.js';
import { CANDIDATE_STATUS, COMPANY_STATUS, OUTREACH_STATUS, SEARCH_STATUS } from '../labels.js';
import { buildIndex, fold, search } from '../quickSearch.js';

const PER_GROUP = 5;

const PAGES = [
  { title: 'Today', path: '/' },
  { title: 'Contacts', path: '/contacts' },
  { title: 'Companies', path: '/companies' },
  { title: 'Searches', path: '/searches' },
  { title: 'Candidates', path: '/candidates' },
  { title: 'Placements', path: '/placements' },
  { title: 'Numbers', path: '/numbers' },
  { title: 'Templates', path: '/templates' },
  { title: 'Add from LinkedIn', path: '/linkedin' },
  { title: 'Import', path: '/import' },
  { title: 'Export', path: '/export' },
  { title: 'Team', path: '/team' },
  { title: 'Trash', path: '/trash' },
];

const joined = (...parts) => parts.filter(Boolean).join(', ');

/** What each kind of record is found by, and how it shows in the results. */
const KINDS = [
  {
    key: 'contacts',
    label: 'Contacts',
    load: () => api.contacts.list(),
    title: (contact) => contact.name,
    sub: (contact) => joined([contact.title, contact.companyName].filter(Boolean).join(' at '), OUTREACH_STATUS[contact.status]),
    fields: (contact) => [contact.title, contact.companyName, contact.email],
    path: (contact) => `/contacts/${contact.id}`,
  },
  {
    key: 'companies',
    label: 'Companies',
    load: () => api.companies.list(),
    title: (company) => company.name,
    sub: (company) => joined(COMPANY_STATUS[company.status], company.location),
    fields: (company) => [company.focus, company.location, company.website],
    path: (company) => `/companies/${company.id}`,
  },
  {
    key: 'candidates',
    label: 'Candidates',
    load: () => api.candidates.list(),
    title: (candidate) => candidate.name,
    sub: (candidate) =>
      joined([candidate.currentTitle, candidate.currentCompany].filter(Boolean).join(' at '), CANDIDATE_STATUS[candidate.status]),
    fields: (candidate) => [candidate.currentTitle, candidate.currentCompany, candidate.email, candidate.location],
    path: (candidate) => `/candidates/${candidate.id}`,
  },
  {
    key: 'searches',
    label: 'Searches',
    load: () => api.searches.list(),
    title: (job) => job.title,
    sub: (job) => joined(job.companyName, SEARCH_STATUS[job.status]),
    fields: (job) => [job.companyName, job.location],
    path: (job) => `/searches/${job.id}`,
  },
];

/** What was opened from here lately, newest first. Kept in memory for this tab only. */
let recent = [];

function remember(item) {
  recent = [item, ...recent.filter((other) => other.path !== item.path)].slice(0, 5);
}

/**
 * Finds any contact, company, candidate or search by name (or email, title, company), or jumps to
 * a page. Opened with Ctrl+K (⌘K on a Mac) from anywhere in the CRM.
 */
export default function QuickSearch({ onClose }) {
  const navigate = useNavigate();
  const desktop = useDesktop();
  const dialog = useRef(null);
  const list = useRef(null);
  const baseId = useId();
  const [query, setQuery] = useState('');
  const [chosen, setChosen] = useState(0);

  const contacts = useLoader(KINDS[0].load, [], 'contacts');
  const companies = useLoader(KINDS[1].load, [], 'companies');
  const candidates = useLoader(KINDS[2].load, [], 'candidates');
  const searches = useLoader(KINDS[3].load, [], 'searches');
  const loaded = [contacts, companies, candidates, searches];

  const contactIndex = useMemo(() => buildIndex(contacts.data, KINDS[0]), [contacts.data]);
  const companyIndex = useMemo(() => buildIndex(companies.data, KINDS[1]), [companies.data]);
  const candidateIndex = useMemo(() => buildIndex(candidates.data, KINDS[2]), [candidates.data]);
  const searchIndex = useMemo(() => buildIndex(searches.data, KINDS[3]), [searches.data]);
  const indexes = [contactIndex, companyIndex, candidateIndex, searchIndex];

  const pages = desktop ? [...PAGES, { title: 'This computer', path: '/computer' }] : PAGES;
  const typed = query.trim();

  const groups = [];
  if (!typed) {
    if (recent.length) groups.push({ label: 'Recent', items: recent });
    groups.push({ label: 'Go to', items: pages });
  } else {
    KINDS.forEach((kind, position) => {
      const items = search(indexes[position], typed, PER_GROUP).map((record) => ({
        title: kind.title(record),
        sub: kind.sub(record),
        path: kind.path(record),
      }));
      if (items.length) groups.push({ label: kind.label, items });
    });
    const pageMatches = pages.filter((page) => fold(page.title).includes(fold(typed)));
    if (pageMatches.length) groups.push({ label: 'Go to', items: pageMatches });
  }
  const options = groups.flatMap((group) => group.items);
  const active = Math.min(chosen, Math.max(0, options.length - 1));
  const stillLoading = typed && loaded.some((loader) => !loader.data);

  useEffect(() => {
    const before = document.activeElement;
    const element = dialog.current;
    if (element && !element.open) element.showModal();
    // Back to where the person was when it closes.
    return () => before?.focus?.();
  }, []);

  useEffect(() => {
    list.current?.querySelector('[aria-selected="true"]')?.scrollIntoView({ block: 'nearest' });
  }, [active, query]);

  function open(item) {
    if (!item) return;
    if (item.sub !== undefined) remember(item);
    onClose();
    navigate(item.path);
  }

  function onKeyDown(event) {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      if (options.length === 0) return;
      const step = event.key === 'ArrowDown' ? 1 : -1;
      setChosen((active + step + options.length) % options.length);
    } else if (event.key === 'Enter') {
      event.preventDefault();
      open(options[active]);
    }
  }

  let position = -1;

  return (
    <dialog
      ref={dialog}
      className="quick-search"
      aria-label="Quick search"
      onCancel={(event) => {
        event.preventDefault();
        onClose();
      }}
      onClick={(event) => {
        if (event.target === dialog.current) onClose();
      }}
    >
      <div className="quick-search-box">
        <svg className="quick-search-icon" viewBox="0 0 20 20" width="18" height="18" aria-hidden="true">
          <circle cx="8.5" cy="8.5" r="5.5" fill="none" stroke="currentColor" strokeWidth="1.8" />
          <path d="M13 13l4.5 4.5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
        </svg>
        <input
          type="text"
          role="combobox"
          aria-expanded="true"
          aria-controls={`${baseId}-list`}
          aria-autocomplete="list"
          aria-activedescendant={options.length ? `${baseId}-${active}` : undefined}
          aria-label="Search contacts, companies, candidates and searches"
          placeholder="Search contacts, companies, candidates, searches"
          autoComplete="off"
          spellCheck="false"
          value={query}
          onChange={(event) => {
            setQuery(event.target.value);
            setChosen(0);
          }}
          onKeyDown={onKeyDown}
          autoFocus
        />
      </div>

      <ul className="quick-search-results" id={`${baseId}-list`} role="listbox" ref={list} aria-label="Results">
        {groups.map((group) => (
          <li key={group.label} role="presentation">
            <div className="quick-search-group" id={`${baseId}-${group.label}`}>
              {group.label}
            </div>
            <ul role="group" aria-labelledby={`${baseId}-${group.label}`}>
              {group.items.map((item) => {
                position += 1;
                const index = position;
                return (
                  <li
                    key={item.path}
                    id={`${baseId}-${index}`}
                    role="option"
                    aria-selected={index === active}
                    className={index === active ? 'quick-search-option quick-search-active' : 'quick-search-option'}
                    onMouseMove={() => index !== active && setChosen(index)}
                    onClick={() => open(item)}
                  >
                    <span className="quick-search-title">{item.title}</span>
                    {item.sub && <span className="quick-search-sub">{item.sub}</span>}
                  </li>
                );
              })}
            </ul>
          </li>
        ))}
      </ul>

      {typed && options.length === 0 && (
        <p className="quick-search-empty">
          {stillLoading ? 'Loading the CRM…' : `Nothing matches “${typed}”. Names, emails, titles and companies are searched.`}
        </p>
      )}
      <p className="quick-search-help" aria-hidden="true">
        <kbd>↑</kbd> <kbd>↓</kbd> to move, <kbd>Enter</kbd> to open, <kbd>Esc</kbd> to close
      </p>
    </dialog>
  );
}
