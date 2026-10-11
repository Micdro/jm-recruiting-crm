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

export function onUnauthorized(handler) {
  unauthorizedHandler = handler;
}

client.interceptors.response.use(
  (response) => response,
  async (error) => {
    const { response, config } = error;

    if (response?.status === 401 && !config.url.startsWith('/api/auth/')) {
      unauthorizedHandler();
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
  dashboard: (scope) => get('/api/dashboard', { scope }),
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
