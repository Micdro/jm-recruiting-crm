import { useCallback, useEffect, useMemo, useState } from 'react';
import { api, onUnauthorized } from '../api.js';
import { AuthContext } from '../contexts.js';
import { clearCache, prefetch, refreshUsers } from '../hooks.js';

/** The lists every page needs, loaded quietly after sign-in. Keys match the pages' cache keys. */
const PREFETCH = [
  ['dashboard:mine', () => api.dashboard('mine')],
  ['contacts', () => api.contacts.list()],
  ['companies', () => api.companies.list()],
  ['searches', () => api.searches.list()],
  ['candidates', () => api.candidates.list()],
  ['placements', () => api.placements.list()],
];

export default function AuthProvider({ children }) {
  const [state, setState] = useState({ user: null, ready: false });

  useEffect(() => {
    onUnauthorized(() => {
      clearCache();
      setState({ user: null, ready: true });
    });

    api.auth.me().then(
      (user) => setState({ user, ready: true }),
      () => setState({ user: null, ready: true }),
    );
  }, []);

  const userId = state.user?.id;
  useEffect(() => {
    if (userId) prefetch(PREFETCH);
  }, [userId]);

  const login = useCallback(async (email, password) => {
    clearCache();
    await api.auth.csrf();
    await api.auth.login(email, password);
    refreshUsers();
    const user = await api.auth.me();
    setState({ user, ready: true });
    return user;
  }, []);

  const logout = useCallback(async () => {
    try {
      await api.auth.logout();
    } finally {
      clearCache();
      refreshUsers();
      setState({ user: null, ready: true });
    }
  }, []);

  const value = useMemo(() => ({ ...state, login, logout }), [state, login, logout]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
