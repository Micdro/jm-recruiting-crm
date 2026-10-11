import { createContext } from 'react';

export const AuthContext = createContext({
  user: null,
  ready: false,
  login: async () => {},
  logout: async () => {},
});

export const ToastContext = createContext(() => {});
