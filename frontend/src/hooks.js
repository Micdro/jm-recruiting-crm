import { useCallback, useContext, useEffect, useState } from 'react';
import { useSearchParams } from 'react-router';
import { api } from './api.js';
import { AuthContext, DesktopContext, ToastContext } from './contexts.js';

// Records the app has already loaded, kept in memory for this tab only. Pages show them
// instantly and refresh in the background, so moving around the CRM doesn't wait on the
// network. Nothing is written to disk, and everything is dropped on sign-out.
const cache = new Map();

export function clearCache() {
  cache.clear();
}

/**
 * Loads data and reloads it when `deps` change or `reload()` is called.
 * Keeps showing the last data while a reload is in flight.
 *
 * With a `cacheKey`, a page opened again shows what it showed last time straight away,
 * then swaps in fresh data when it arrives.
 */
export function useLoader(fetcher, deps = [], cacheKey = null) {
  const [version, setVersion] = useState(0);
  const key = JSON.stringify(deps) + ':' + version;
  const [state, setState] = useState(() => ({
    data: cacheKey && cache.has(cacheKey) ? cache.get(cacheKey) : null,
    error: null,
    key: null,
    cacheKey,
  }));

  useEffect(() => {
    let active = true;
    fetcher().then(
      (data) => {
        if (cacheKey) cache.set(cacheKey, data);
        if (active) setState({ data, error: null, key, cacheKey });
      },
      (error) => active && setState((current) => ({ ...current, error, key })),
    );
    return () => {
      active = false;
    };
    // The fetcher is recreated every render; `key` and `cacheKey` capture what it depends on.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, cacheKey]);

  const reload = useCallback(() => setVersion((value) => value + 1), []);
  const setData = useCallback(
    (update) =>
      setState((current) => {
        const data = typeof update === 'function' ? update(current.data) : update;
        if (current.cacheKey) cache.set(current.cacheKey, data);
        return { ...current, data };
      }),
    [],
  );

  // When the record changes (another contact, say), show its cached copy rather than the last one.
  const switched = cacheKey !== state.cacheKey;
  const data = switched && cacheKey && cache.has(cacheKey) ? cache.get(cacheKey) : state.data;

  return {
    data,
    error: state.error,
    loading: state.key !== key || switched,
    reload,
    setData,
  };
}

/**
 * Loads the main lists in the background after sign-in, so the first visit to each page is
 * instant too. Spaced out so it never competes with the page being opened.
 */
export function prefetch(entries) {
  entries.forEach(([cacheKey, fetcher], index) => {
    setTimeout(() => {
      if (cache.has(cacheKey)) return;
      fetcher().then(
        (data) => cache.set(cacheKey, data),
        () => {},
      );
    }, 800 + index * 250);
  });
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

export function useDesktop() {
  return useContext(DesktopContext);
}

/** Calls `callback` every `ms` milliseconds while the component is shown. */
export function useInterval(callback, ms) {
  useEffect(() => {
    const timer = setInterval(callback, ms);
    return () => clearInterval(timer);
  }, [callback, ms]);
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
