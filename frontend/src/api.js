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
    /** Hands the account owner role to this teammate. Returns the team. */
    makeOwner: (id) => post(`/api/users/${id}/account-owner`),
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
  /** Merges a duplicate into the record kept: { kind: CONTACT | COMPANY | CANDIDATE, keepId, mergeId }. */
  merge: (body) => post('/api/merge', body),
  exportData: (parts) => get('/api/export', parts ? { include: parts.join(',') } : undefined),
  reports: {
    weekly: (scope) => get('/api/reports/weekly', { scope }),
  },
  companies: {
    ...crud('/api/companies'),
    /** Moves the follow-up date: until is 'TOMORROW' (next business day) or 'NEXT_WEEK'. */
    snooze: (id, until) => post(`/api/companies/${id}/snooze`, { until }),
    undo: (id) => post(`/api/companies/${id}/undo`),
    /** Bulk action on the list: { ids, action: OWNER | STAGE | PRIORITY | FOLLOW_UP_TODAY | DELETE, ... }. */
    bulk: (body) => post('/api/companies/bulk', body),
    undoBulk: (undoId) => post(`/api/companies/bulk/${undoId}/undo`),
  },
  contacts: {
    ...crud('/api/contacts'),
    logActivity: (id, body) => post(`/api/contacts/${id}/activities`, body),
    restartSequence: (id) => post(`/api/contacts/${id}/restart-sequence`),
    fillQueue: (count) => post('/api/contacts/fill-queue', { count }),
    snooze: (id, until) => post(`/api/contacts/${id}/snooze`, { until }),
    /** Undoes your last touch, reply, note or snooze on this contact, within a few minutes. */
    undo: (id) => post(`/api/contacts/${id}/undo`),
    /** Bulk action on the list: { ids, action: OWNER | STATUS | TODAY | DELETE, ownerId, status }. */
    bulk: (body) => post('/api/contacts/bulk', body),
    undoBulk: (undoId) => post(`/api/contacts/bulk/${undoId}/undo`),
  },
  searches: {
    ...crud('/api/searches'),
    pipeline: (id) => get(`/api/searches/${id}/pipeline`),
    /** { notes, companyId } gives { search, companyHeard, companies, hiringManager, provider, costCents }. Not saved. */
    readIntake: (body) => post('/api/searches/read-intake', body),
    /** AI writes a job description from the search's brief: { jobDescription, provider, costCents }. Not saved. */
    draftJobDescription: (id) => post(`/api/searches/${id}/job-description/draft`),
    saveJobDescription: (id, jobDescription) => put(`/api/searches/${id}/job-description`, { jobDescription }),
  },
  candidates: {
    ...crud('/api/candidates'),
    pipeline: (id) => get(`/api/candidates/${id}/pipeline`),
    logActivity: (id, body) => post(`/api/candidates/${id}/activities`, body),
    snooze: (id, until) => post(`/api/candidates/${id}/snooze`, { until }),
    undo: (id) => post(`/api/candidates/${id}/undo`),
    files: (id) => get(`/api/candidates/${id}/files`),
    /** Sends the file as is, with its name; the server encrypts it before storing it. */
    addFile: (id, file) =>
      client
        .post(`/api/candidates/${id}/files`, file, {
          params: { name: file.name },
          headers: { 'Content-Type': 'application/octet-stream' },
        })
        .then((r) => r.data),
    removeFile: (id, fileId) => del(`/api/candidates/${id}/files/${fileId}`),
    fileUrl: (id, fileId, download = false) => `/api/candidates/${id}/files/${fileId}${download ? '?download=true' : ''}`,
    /** AI reads a resume for a new candidate: { candidate, matches, fileName, provider, costCents }. Not saved. */
    readResume: (file) =>
      client
        .post('/api/candidates/read-resume', file, {
          params: { name: file.name },
          headers: { 'Content-Type': 'application/octet-stream' },
        })
        .then((r) => r.data),
    /** AI reads a resume already on the candidate; same answer as readResume. */
    readFile: (id, fileId) => post(`/api/candidates/${id}/files/${fileId}/read`),
    /** { type: CALL | MEETING, notes } gives { update, nextStep, provider, costCents }. Not saved. */
    readCallNotes: (id, body) => post(`/api/candidates/${id}/call-notes/read`, body),
    /** Logs the call and makes its changes; null fields stay as they are. */
    saveCall: (id, body) => post(`/api/candidates/${id}/call-notes`, body),
  },
  pipeline: {
    add: (body) => post('/api/pipeline', body),
    update: (id, body) => put(`/api/pipeline/${id}`, body),
    /** AI drafts why the candidate fits the search: { rationale, provider, costCents }. Not saved. */
    draftRationale: (id) => post(`/api/pipeline/${id}/rationale/draft`),
    remove: (id) => del(`/api/pipeline/${id}`),
    /** { date: 'YYYY-MM-DD', time: 'HH:MM' or null, label }. A new date or time starts both debriefs over. */
    scheduleInterview: (id, body) => put(`/api/pipeline/${id}/interview`, body),
    cancelInterview: (id) => del(`/api/pipeline/${id}/interview`),
    /** { with: 'CANDIDATE' | 'CLIENT', notes }. */
    debrief: (id, body) => post(`/api/pipeline/${id}/debrief`, body),
  },
  placements: {
    ...crud('/api/placements'),
    invoice: (id, body) => put(`/api/placements/${id}/invoice`, body),
    nextInvoiceNumber: () => get('/api/placements/next-invoice-number'),
    /** Records the guarantee check-in that's due: { notes }. */
    checkIn: (id, body) => post(`/api/placements/${id}/check-in`, body),
  },
  firm: {
    invoiceSettings: () => get('/api/firm/invoice-settings'),
    saveInvoiceSettings: (body) => put('/api/firm/invoice-settings', body),
  },
  prospects: {
    status: () => get('/api/prospects'),
    summary: () => get('/api/prospects/summary'),
    companies: () => get('/api/prospects/companies'),
    run: () => post('/api/prospects/run'),
    saveKey: (apiKey) => put('/api/prospects/key', { apiKey }),
    removeKey: () => del('/api/prospects/key'),
    saveSettings: (body) => put('/api/prospects/settings', body),
    saveLimits: (body) => put('/api/prospects/limits', body),
    /** Drafts the next message to a contact with Claude: { subject, body, basis, costCents }. */
    draft: (body) => post('/api/prospects/draft', body),
    runHere: () => post('/api/prospects/run-here'),
    accept: (id) => post(`/api/prospects/companies/${id}/accept`),
    /** Why it isn't a fit ({ reason, note }) teaches the finder's next searches. */
    dismiss: (id, body) => post(`/api/prospects/companies/${id}/dismiss`, body),
  },
  /** Which AI writes drafts, the OpenAI key and the shared spending limits: the account owner's to change. */
  ai: {
    status: () => get('/api/ai'),
    /** { provider: 'CLAUDE' | 'OPENAI', model }. */
    choose: (body) => put('/api/ai/provider', body),
    saveOpenAiKey: (apiKey) => put('/api/ai/openai-key', { apiKey }),
    removeOpenAiKey: () => del('/api/ai/openai-key'),
  },
  marketing: {
    /** The week holding `date` (YYYY-MM-DD, this week if left out) and the ideas without a date. */
    week: (date) => get('/api/marketing/week', { date }),
    /** Posts from today on that aren't posted yet, and missed ones from the past week. */
    upcoming: () => get('/api/marketing/upcoming'),
    posts: crud('/api/marketing/posts'),
    /** Saved post templates, by name: { name, title, body, notes, theme, account }. */
    templates: crud('/api/marketing/templates'),
    /** { postedOn, postUrl }; the day defaults to today. */
    posted: (id, body) => post(`/api/marketing/posts/${id}/posted`, body),
    results: (id, body) => put(`/api/marketing/posts/${id}/results`, body),
    /** Drafts the saved post with AI: { body, provider, costCents }. Not saved. */
    draft: (id, body) => post(`/api/marketing/posts/${id}/draft`, body),
    /** { count, focus } gives { ideas, provider, costCents }. Not saved. */
    suggest: (body) => post('/api/marketing/ideas/suggest', body),
    /** The style, posting days and the focus of `month` (YYYY-MM). */
    plan: (month) => get('/api/marketing/plan', { month }),
    savePlan: (body) => put('/api/marketing/plan', body),
    /** { date, focus }: fills the week's open posting days with AI drafts. */
    setUpWeek: (body) => post('/api/marketing/week/setup', body),
    /** Sends the picture file as is; the server encrypts it before storing it. */
    uploadPicture: (id, file) =>
      client
        .post(`/api/marketing/posts/${id}/image`, file, { headers: { 'Content-Type': 'application/octet-stream' } })
        .then((r) => r.data),
    /** { direction } gives { post, costCents }. */
    generatePicture: (id, body) => post(`/api/marketing/posts/${id}/image/generate`, body),
    removePicture: (id) => del(`/api/marketing/posts/${id}/image`),
    /** Posts it to its owner's LinkedIn profile and marks it posted. */
    publish: (id) => post(`/api/marketing/posts/${id}/publish`),
  },
  linkedin: {
    status: () => get('/api/linkedin'),
    /** { clientId, clientSecret } of the firm's LinkedIn app. */
    saveApp: (body) => put('/api/linkedin/app', body),
    removeApp: () => del('/api/linkedin/app'),
    /** { url }: LinkedIn's sign-in page for the firm's app. */
    connect: () => get('/api/linkedin/connect'),
    disconnect: () => del('/api/linkedin/connection'),
  },
  backups: {
    status: () => get('/api/backups'),
    backUp: () => post('/api/backups'),
    restore: (name) => post(`/api/backups/${encodeURIComponent(name)}/restore`),
    fileUrl: (name) => `/api/backups/${encodeURIComponent(name)}`,
  },
  templates: crud('/api/templates'),
  signals: {
    list: () => get('/api/signals'),
    summary: () => get('/api/signals/summary'),
    check: () => post('/api/signals/check'),
    findBoards: () => post('/api/signals/find-boards'),
    company: (id) => get(`/api/signals/companies/${id}`),
    /** `{ link }` sets the board, `{ none: true }` marks the company as having none, `{}` clears it. */
    setBoard: (id, body) => put(`/api/signals/companies/${id}/board`, body),
    confirm: (id) => post(`/api/signals/companies/${id}/confirm`),
    checkCompany: (id) => post(`/api/signals/companies/${id}/check`),
    reviewed: (id) => post(`/api/signals/companies/${id}/reviewed`),
  },
  trash: {
    list: () => get('/api/trash'),
    restore: (id) => post(`/api/trash/${id}/restore`),
    undo: (kind, recordId) => client.post('/api/trash/undo', null, { params: { kind, recordId } }).then((r) => r.data),
  },
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
