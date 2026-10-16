import { useEffect, useState } from 'react';
import { NavLink, Outlet, useLocation } from 'react-router';
import { useAuth, useDesktop } from '../hooks.js';
import logo from '../assets/jm-logo-reversed.png';
import { shortcutLabel } from '../quickSearch.js';
import QuickSearch from './QuickSearch.jsx';
import SyncBadge from './SyncBadge.jsx';
import UpdateBanner from './UpdateBanner.jsx';

const NAV = [
  { to: '/', label: 'Today', end: true },
  { to: '/contacts', label: 'Contacts' },
  { to: '/companies', label: 'Companies' },
  { to: '/signals', label: 'Hiring signals' },
  { to: '/prospects', label: 'AI Found' },
  { to: '/searches', label: 'Searches' },
  { to: '/candidates', label: 'Candidates' },
  { to: '/placements', label: 'Placements' },
  { to: '/numbers', label: 'Numbers' },
];

const TOOLS = [
  { to: '/templates', label: 'Templates' },
  { to: '/linkedin', label: 'Add from LinkedIn' },
  { to: '/import', label: 'Import' },
  { to: '/export', label: 'Export' },
  { to: '/team', label: 'Team' },
  { to: '/trash', label: 'Trash' },
];

export default function Layout() {
  const { user, logout } = useAuth();
  const desktop = useDesktop();
  const tools = desktop ? [...TOOLS, { to: '/computer', label: 'This computer' }] : TOOLS;
  const [openPath, setOpenPath] = useState(null);
  const location = useLocation();
  // The mobile menu closes itself whenever the page changes.
  const menuOpen = openPath === location.pathname;
  const [searching, setSearching] = useState(false);

  // Ctrl+K (⌘K on a Mac) opens the quick search from anywhere.
  useEffect(() => {
    function onKeyDown(event) {
      if ((event.ctrlKey || event.metaKey) && !event.altKey && !event.shiftKey && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        setSearching(true);
      }
    }
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, []);

  return (
    <div className="shell">
      <a className="skip-link" href="#main">
        Skip to content
      </a>

      <header className="sidebar">
        <div className="sidebar-top">
          <NavLink to="/" className="brand" end>
            <img className="brand-logo" src={logo} alt="Jane Michael AI Talent Search" width="168" height="32" />
            <span className="brand-sub">Recruiting CRM</span>
          </NavLink>
          <button
            type="button"
            className="menu-toggle"
            aria-expanded={menuOpen}
            aria-controls="primary-nav"
            onClick={() => setOpenPath(menuOpen ? null : location.pathname)}
          >
            {menuOpen ? 'Close' : 'Menu'}
          </button>
        </div>

        <button type="button" className="search-trigger" onClick={() => setSearching(true)} aria-keyshortcuts="Control+K Meta+K">
          <svg viewBox="0 0 20 20" width="15" height="15" aria-hidden="true">
            <circle cx="8.5" cy="8.5" r="5.5" fill="none" stroke="currentColor" strokeWidth="1.8" />
            <path d="M13 13l4.5 4.5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
          </svg>
          <span>Search</span>
          <kbd>{shortcutLabel()}</kbd>
        </button>

        <nav id="primary-nav" className={`nav ${menuOpen ? 'nav-open' : ''}`} aria-label="Main">
          <ul>
            {NAV.map((item) => (
              <li key={item.to}>
                <NavLink to={item.to} end={item.end}>
                  {item.label}
                </NavLink>
              </li>
            ))}
          </ul>
          <ul className="nav-tools">
            {tools.map((item) => (
              <li key={item.to}>
                <NavLink to={item.to}>{item.label}</NavLink>
              </li>
            ))}
          </ul>
          <div className="nav-user">
            <span className="nav-user-name">{user?.displayName}</span>
            {desktop && <SyncBadge />}
            <button type="button" className="link-button" onClick={logout}>
              Sign out
            </button>
          </div>
        </nav>
      </header>

      <main id="main" className="main" tabIndex={-1}>
        {desktop && <UpdateBanner />}
        <Outlet />
      </main>

      {searching && <QuickSearch onClose={() => setSearching(false)} />}
    </div>
  );
}
