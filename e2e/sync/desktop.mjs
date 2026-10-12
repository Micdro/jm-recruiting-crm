// Helpers for the sync tests: run desktop copies of the CRM side by side (as separate computers
// would), each with its own home folder, database and ports, and talk to them over HTTP.

import { spawn } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

export const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** A computer: its home folder and ports. The sync port is `base + 1`. */
export function computer(name, base) {
  return {
    name,
    home: mkdtempSync(join(tmpdir(), `jmcrm-${name}-`)),
    ui: base,
    sync: base + 1,
    db: base + 2,
    process: null,
    cookies: new Map(),
  };
}

export function start(pc, jar, options = []) {
  pc.cookies.clear();
  const env = {
    ...process.env,
    JMCRM_DESKTOP: 'true',
    JMCRM_HOME: pc.home,
    JMCRM_PORT: String(pc.ui),
    JMCRM_SYNC_PORT: String(pc.sync),
    JMCRM_DB_PORT: String(pc.db),
    JMCRM_NO_BROWSER: '1',
  };
  // CI points the server tests at its own database; the desktop copies bring their own.
  delete env.SPRING_DATASOURCE_URL;
  delete env.SPRING_DATASOURCE_USERNAME;
  delete env.SPRING_DATASOURCE_PASSWORD;
  pc.process = spawn('java', ['-Xmx512m', ...options, '-jar', jar], {
    env,
    stdio: ['ignore', 'ignore', 'ignore'],
  });
}

export async function stop(pc) {
  if (!pc.process) return;
  const exited = new Promise((resolve) => pc.process.once('exit', resolve));
  pc.process.kill('SIGTERM');
  await exited;
  pc.process = null;
}

export function log(pc) {
  const file = join(pc.home, 'logs', 'jm-crm.log');
  const startup = join(pc.home, 'logs', 'startup-error.txt');
  let text = '';
  if (existsSync(startup)) text += readFileSync(startup, 'utf8');
  if (existsSync(file)) text += readFileSync(file, 'utf8').split('\n').slice(-120).join('\n');
  return text;
}

/**
 * One API call with the computer's cookies. {@code raw} sends bytes as the body (a file upload);
 * {@code binary} returns the answer as bytes instead of JSON.
 */
export async function request(pc, method, path, body, { form = false, raw = null, binary = false } = {}) {
  const headers = { Accept: 'application/json' };
  const cookie = [...pc.cookies].map(([key, value]) => `${key}=${value}`).join('; ');
  if (cookie) headers.Cookie = cookie;
  if (pc.cookies.has('XSRF-TOKEN')) headers['X-XSRF-TOKEN'] = pc.cookies.get('XSRF-TOKEN');
  let payload;
  if (raw) {
    headers['Content-Type'] = 'application/octet-stream';
    payload = raw;
  } else if (body !== undefined) {
    if (form) {
      headers['Content-Type'] = 'application/x-www-form-urlencoded';
      payload = new URLSearchParams(body).toString();
    } else {
      headers['Content-Type'] = 'application/json';
      payload = JSON.stringify(body);
    }
  }
  const response = await fetch(`http://127.0.0.1:${pc.ui}${path}`, { method, headers, body: payload });
  for (const setCookie of response.headers.getSetCookie()) {
    const [pair] = setCookie.split(';');
    const index = pair.indexOf('=');
    pc.cookies.set(pair.slice(0, index), pair.slice(index + 1));
  }
  if (binary && response.ok) {
    return Buffer.from(await response.arrayBuffer());
  }
  const text = await response.text();
  const data = text ? JSON.parse(text) : null;
  if (!response.ok) {
    throw new Error(`${pc.name}: ${method} ${path} -> ${response.status} ${text}`);
  }
  return data;
}

export async function csrf(pc) {
  await request(pc, 'GET', '/api/auth/csrf');
}

export async function signIn(pc, email, password) {
  await csrf(pc);
  await request(pc, 'POST', '/api/auth/login', { email, password }, { form: true });
  await csrf(pc);
}

export async function signOut(pc) {
  await request(pc, 'POST', '/api/auth/logout');
  pc.cookies.delete('JSESSIONID');
  await csrf(pc);
}

export const locked = async (pc) => (await request(pc, 'GET', '/api/setup/status')).locked;

export const companyNames = async (pc) => (await request(pc, 'GET', '/api/companies')).map((company) => company.name);

/** Every file in a folder (recursively) that contains any of the given strings. */
export function filesContaining(folder, needles) {
  const found = [];
  const buffers = needles.map((needle) => Buffer.from(needle, 'utf8'));
  const walk = (dir) => {
    for (const name of readdirSync(dir)) {
      const path = join(dir, name);
      const info = statSync(path, { throwIfNoEntry: false });
      if (!info) continue;
      if (info.isDirectory()) walk(path);
      else if (info.isFile()) {
        let content;
        try {
          content = readFileSync(path);
        } catch {
          continue; // Held open by the database on some systems.
        }
        if (buffers.some((needle) => content.includes(needle))) found.push(path);
      }
    }
  };
  walk(folder);
  return found;
}

export async function waitForHealth(pc, seconds = 180) {
  const deadline = Date.now() + seconds * 1000;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(`http://127.0.0.1:${pc.ui}/api/health`);
      if (response.ok) return;
    } catch {
      // Not up yet.
    }
    if (pc.process && pc.process.exitCode !== null) break;
    await sleep(1000);
  }
  throw new Error(`${pc.name} did not start.\n${log(pc)}`);
}

export async function eventually(description, condition, seconds = 30) {
  const deadline = Date.now() + seconds * 1000;
  let last;
  while (Date.now() < deadline) {
    try {
      if (await condition()) {
        console.log(`ok - ${description}`);
        return;
      }
    } catch (error) {
      last = error;
    }
    await sleep(1000);
  }
  throw new Error(`Timed out: ${description}${last ? ` (${last.message})` : ''}`);
}

export function check(condition, description) {
  if (!condition) throw new Error(`Failed: ${description}`);
  console.log(`ok - ${description}`);
}

/** Runs a test, printing every computer's log if it fails. */
export async function run(computers, main) {
  try {
    await main();
  } catch (error) {
    console.error(error.message);
    for (const pc of computers) {
      console.error(`--- ${pc.name} computer log ---\n` + log(pc));
    }
    process.exitCode = 1;
  } finally {
    await Promise.all(computers.map(stop));
  }
}
