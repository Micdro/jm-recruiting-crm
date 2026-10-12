import { useState } from 'react';
import { NavLink, Outlet, useLocation } from 'react-router';
import { useAuth, useDesktop } from '../hooks.js';
import SyncBadge from './SyncBadge.jsx';
import UpdateBanner from './UpdateBanner.jsx';

const NAV = [
  { to: '/', label: 'Today', end: true },
  { to: '/contacts', label: 'Contacts' },
  { to: '/companies', label: 'Companies' },
  { to: '/searches', label: 'Searches' },
  { to: '/candidates', label: 'Candidates' },
  { to: '/placements', label: 'Placements' },
];

const TOOLS = [
  { to: '/templates', label: 'Templates' },
  { to: '/import', label: 'Import' },
  { to: '/team', label: 'Team' },
];

export default function Layout() {
  const { user, logout } = useAuth();
  const desktop = useDesktop();
  const tools = desktop ? [...TOOLS, { to: '/computer', label: 'This computer' }] : TOOLS;
  const [openPath, setOpenPath] = useState(null);
  const location = useLocation();
  // The mobile menu closes itself whenever the page changes.
  const menuOpen = openPath === location.pathname;

  return (
    <div className="shell">
      <a className="skip-link" href="#main">
        Skip to content
      </a>

      <header className="sidebar">
        <div className="sidebar-top">
          <NavLink to="/" className="brand" end>
            <span className="brand-name">Jane Michael</span>
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
    </div>
  );
}
