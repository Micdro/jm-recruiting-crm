import { createContext } from 'react';

export const AuthContext = createContext({
  user: null,
  ready: false,
  login: async () => {},
  logout: async () => {},
});

export const ToastContext = createContext(() => {});

/** True when this is the desktop app (its own database, synced with the team's other computers). */
export const DesktopContext = createContext(false);
