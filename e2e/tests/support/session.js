import { readFileSync } from 'node:fs';
import { AUTH_FILE } from './urls.js';

/** The XSRF-TOKEN cookie value from a request context. Changes must send it back as a header. */
export async function xsrfToken(context) {
  const state = await context.storageState();
  return state.cookies.find((cookie) => cookie.name === 'XSRF-TOKEN')?.value ?? '';
}

/** The XSRF token saved with the signed-in session. */
export function savedXsrfToken() {
  const state = JSON.parse(readFileSync(AUTH_FILE, 'utf8'));
  return state.cookies.find((cookie) => cookie.name === 'XSRF-TOKEN')?.value ?? '';
}
