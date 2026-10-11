import { fileURLToPath } from 'node:url';

// Where the running app lives. Override with env vars to point the tests elsewhere.
export const API_URL = process.env.API_URL ?? 'http://localhost:8080';

// The React dev server. It forwards /api to the backend, so the browser sees one origin.
export const UI_URL = process.env.UI_URL ?? 'http://localhost:5173';

// The account the tests sign in with. CI creates it on startup from the same variables.
export const ADMIN_EMAIL = process.env.ADMIN_EMAIL ?? 'e2e-admin@jane-michael.com';
export const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD ?? 'e2e-admin-password';

// The signed-in session (cookies), saved by auth.setup.js and reused by every test.
export const AUTH_FILE = fileURLToPath(new URL('../../.auth/admin.json', import.meta.url));
