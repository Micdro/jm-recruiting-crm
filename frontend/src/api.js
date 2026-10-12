import axios from 'axios';

// Same origin in development (Vite proxy) and production (Spring serves the app),
// so the session cookie and the XSRF-TOKEN cookie are sent automatically.
const client = axios.create({
  withCredentials: true,
  withXSRFToken: true,
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
});

let unauthorizedHandler = () => {};
let lockedHandler = () => {};

export function onUnauthorized(handler) {
  unauthorizedHandler = handler;
}

/** Called when the desktop CRM answers 423: signed in, but the CRM is locked on this computer. */
export function onLocked(handler) {
  lockedHandler = handler;
}

client.interceptors.response.use(
  (response) => response,
  async (error) => {
    const { response, config } = error;

    if (response?.status === 401 && !config.url.startsWith('/api/auth/')) {
      unauthorizedHandler();
    }
    if (response?.status === 423) {
      lockedHandler();
    }

    // A missing or stale CSRF token: fetch a fresh one and retry once.
    const isAppError = Array.isArray(response?.data?.messages);
    if (response?.status === 403 && !isAppError && !config.retriedForCsrf && config.method !== 'get') {
      config.retriedForCsrf = true;
      await client.get('/api/auth/csrf');
      return client(config);
    }

    return Promise.reject(error);
  },
);

/** A readable message for any failed request. */
export function errorMessage(error, fallback = 'Something went wrong. Try again.') {
  const data = error?.response?.data;
  if (Array.isArray(data?.messages) && data.messages.length > 0) {
    return data.messages.join(' ');
  }
  if (!error?.response) {
    return 'Can’t reach the server. Check your connection and try again.';
  }
  if (error.response.status >= 500) {
    return 'The server hit an error. Try again, and if it keeps happening, check the server logs.';
  }
  return fallback;
}

function clean(params) {
  if (!params) return undefined;
  return Object.fromEntries(
    Object.entries(params).filter(([, value]) => value !== undefined && value !== null && value !== ''),
  );
}

const get = (url, params) => client.get(url, { params: clean(params) }).then((r) => r.data);
const post = (url, body) => client.post(url, body).then((r) => r.data);
const put = (url, body) => client.put(url, body).then((r) => r.data);
const del = (url) => client.delete(url).then((r) => r.data);

function crud(base) {
  return {
    list: (params) => get(base, params),
    get: (id) => get(`${base}/${id}`),
    create: (body) => post(base, body),
    update: (id, body) => put(`${base}/${id}`, body),
    remove: (id) => del(`${base}/${id}`),
  };
}

export const api = {
  auth: {
    me: () => get('/api/auth/me'),
    csrf: () => client.get('/api/auth/csrf'),
    login: (email, password) =>
      client.post('/api/auth/login', new URLSearchParams({ email, password })),
    logout: () => client.post('/api/auth/logout'),
  },
  users: {
    list: () => get('/api/users'),
    create: (body) => post('/api/users', body),
    update: (id, body) => put(`/api/users/${id}`, body),
    resetPassword: (id, password) => post(`/api/users/${id}/password`, { password }),
    changeOwnPassword: (currentPassword, newPassword) =>
      post('/api/users/me/password', { currentPassword, newPassword }),
  },
  setup: {
    status: () => get('/api/setup/status'),
    start: (body) => post('/api/setup/start', body),
    join: (body) => post('/api/setup/join', body),
    resetPassword: (body) => post('/api/setup/reset-password', body),
    folders: () => get('/api/setup/folders'),
  },
  desktop: {
    status: () => get('/api/desktop/status'),
    syncNow: () => post('/api/desktop/sync-now'),
    setStartup: (enabled) => put('/api/desktop/startup', { enabled }),
    setReminder: (enabled, time) => put('/api/desktop/reminder', { enabled, time }),
    showReminder: () => post('/api/desktop/reminder/show'),
    firewall: () => get('/api/desktop/firewall'),
    allowFirewall: () => post('/api/desktop/firewall'),
    removeComputer: (id) => del(`/api/desktop/computers/${id}`),
    useFolder: (path) => put('/api/desktop/folder', { path }),
    setDirect: (enabled) => put('/api/desktop/direct', { enabled }),
    update: () => get('/api/desktop/update'),
    checkForUpdate: () => post('/api/desktop/update/check'),
    installUpdate: () => post('/api/desktop/update'),
    quit: () => post('/api/desktop/quit'),
    unlock: (key, password) => post('/api/desktop/unlock', { key, password }),
  },
  dashboard: (scope) => get('/api/dashboard', { scope }),
  exportData: (parts) => get('/api/export', parts ? { include: parts.join(',') } : undefined),
  reports: {
    weekly: (scope) => get('/api/reports/weekly', { scope }),
  },
  companies: crud('/api/companies'),
  contacts: {
    ...crud('/api/contacts'),
    logActivity: (id, body) => post(`/api/contacts/${id}/activities`, body),
    restartSequence: (id) => post(`/api/contacts/${id}/restart-sequence`),
    fillQueue: (count) => post('/api/contacts/fill-queue', { count }),
  },
  searches: {
    ...crud('/api/searches'),
    pipeline: (id) => get(`/api/searches/${id}/pipeline`),
  },
  candidates: {
    ...crud('/api/candidates'),
    pipeline: (id) => get(`/api/candidates/${id}/pipeline`),
    logActivity: (id, body) => post(`/api/candidates/${id}/activities`, body),
  },
  pipeline: {
    add: (body) => post('/api/pipeline', body),
    update: (id, body) => put(`/api/pipeline/${id}`, body),
    remove: (id) => del(`/api/pipeline/${id}`),
  },
  placements: crud('/api/placements'),
  templates: crud('/api/templates'),
  activities: {
    list: (params) => get('/api/activities', params),
    note: (body) => post('/api/activities', body),
  },
  imports: {
    companies: (body) => post('/api/import/companies', body),
    contacts: (body) => post('/api/import/contacts', body),
    candidates: (body) => post('/api/import/candidates', body),
  },
};
