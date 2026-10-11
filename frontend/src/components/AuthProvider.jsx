import { useCallback, useEffect, useMemo, useState } from 'react';
import { api, onUnauthorized } from '../api.js';
import { AuthContext } from '../contexts.js';
import { refreshUsers } from '../hooks.js';

export default function AuthProvider({ children }) {
  const [state, setState] = useState({ user: null, ready: false });

  useEffect(() => {
    onUnauthorized(() => setState({ user: null, ready: true }));

    api.auth.me().then(
      (user) => setState({ user, ready: true }),
      () => setState({ user: null, ready: true }),
    );
  }, []);

  const login = useCallback(async (email, password) => {
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
      setState({ user: null, ready: true });
    }
  }, []);

  const value = useMemo(() => ({ ...state, login, logout }), [state, login, logout]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
