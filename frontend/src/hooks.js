import { useCallback, useContext, useEffect, useState } from 'react';
import { useSearchParams } from 'react-router';
import { api } from './api.js';
import { AuthContext, ToastContext } from './contexts.js';

/**
 * Loads data and reloads it when `deps` change or `reload()` is called.
 * Keeps showing the last data while a reload is in flight.
 */
export function useLoader(fetcher, deps = []) {
  const [version, setVersion] = useState(0);
  const key = JSON.stringify(deps) + ':' + version;
  const [state, setState] = useState({ data: null, error: null, key: null });

  useEffect(() => {
    let active = true;
    fetcher().then(
      (data) => active && setState({ data, error: null, key }),
      (error) => active && setState((current) => ({ data: current.data, error, key })),
    );
    return () => {
      active = false;
    };
    // The fetcher is recreated every render; `key` captures what it depends on.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  const reload = useCallback(() => setVersion((value) => value + 1), []);
  const setData = useCallback(
    (update) =>
      setState((current) => ({
        ...current,
        data: typeof update === 'function' ? update(current.data) : update,
      })),
    [],
  );

  return {
    data: state.data,
    error: state.error,
    loading: state.key !== key,
    reload,
    setData,
  };
}

let usersPromise = null;

/** The team, for owner pickers. Cached for the session; call refreshUsers() after changes. */
export function useUsers() {
  const { data } = useLoader(() => {
    if (!usersPromise) {
      usersPromise = api.users.list().catch((error) => {
        usersPromise = null;
        throw error;
      });
    }
    return usersPromise;
  }, []);
  return data ?? [];
}

export function refreshUsers() {
  usersPromise = null;
}

export function useAuth() {
  return useContext(AuthContext);
}

export function useToast() {
  return useContext(ToastContext);
}

/** Keeps the browser tab title in step with the page. */
export function useTitle(title) {
  useEffect(() => {
    document.title = title ? `${title} | JM Recruiting CRM` : 'JM Recruiting CRM';
  }, [title]);
}

/** Reads and writes list filters in the URL query string, so links and refreshes keep them. */
export function useFilterParams() {
  const [searchParams, setSearchParams] = useSearchParams();
  const get = (key) => searchParams.get(key) ?? '';
  const set = (key) => (value) => {
    const next = new URLSearchParams(searchParams);
    if (value === '' || value === false || value === null) next.delete(key);
    else next.set(key, value === true ? 'true' : value);
    setSearchParams(next, { replace: true });
  };
  return { get, set };
}

/** Case-insensitive match of a query against any of the given fields. */
export function matchesQuery(query, ...fields) {
  const needle = query.trim().toLowerCase();
  if (!needle) return true;
  return fields.some((field) => field && String(field).toLowerCase().includes(needle));
}
