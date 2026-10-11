// Runs two desktop copies of the CRM side by side (as two computers would) and checks that they
// set up, join, sync both ways, and keep their data across a restart.
//
//   node sync/two-computers.mjs <path to desktop jar>
//
// Each copy gets its own home folder, database and ports. Exit code 0 means everything synced.

import { spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const jar = process.argv[2];
if (!jar) {
  console.error('Usage: node sync/two-computers.mjs <desktop jar>');
  process.exit(2);
}

const PASSWORD = 'two-computers-password';
const EMAIL = 'michael@two-computers.test';

function computer(name, base) {
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

const first = computer('first', 4780);
const second = computer('second', 4790);

function start(pc) {
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
  pc.process = spawn('java', ['-Xmx512m', '-Dapp.sync.interval-ms=1000', '-jar', jar], {
    env,
    stdio: ['ignore', 'ignore', 'ignore'],
  });
}

async function stop(pc) {
  if (!pc.process) return;
  const exited = new Promise((resolve) => pc.process.once('exit', resolve));
  pc.process.kill('SIGTERM');
  await exited;
  pc.process = null;
}

function log(pc) {
  const file = join(pc.home, 'logs', 'jm-crm.log');
  const startup = join(pc.home, 'logs', 'startup-error.txt');
  let text = '';
  if (existsSync(startup)) text += readFileSync(startup, 'utf8');
  if (existsSync(file)) text += readFileSync(file, 'utf8').split('\n').slice(-120).join('\n');
  return text;
}

async function request(pc, method, path, body, { form = false } = {}) {
  const headers = { Accept: 'application/json' };
  const cookie = [...pc.cookies].map(([key, value]) => `${key}=${value}`).join('; ');
  if (cookie) headers.Cookie = cookie;
  if (pc.cookies.has('XSRF-TOKEN')) headers['X-XSRF-TOKEN'] = pc.cookies.get('XSRF-TOKEN');
  let payload;
  if (body !== undefined) {
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
  const text = await response.text();
  const data = text ? JSON.parse(text) : null;
  if (!response.ok) {
    throw new Error(`${pc.name}: ${method} ${path} -> ${response.status} ${text}`);
  }
  return data;
}

async function csrf(pc) {
  await request(pc, 'GET', '/api/auth/csrf');
}

async function signIn(pc) {
  await csrf(pc);
  await request(pc, 'POST', '/api/auth/login', { email: EMAIL, password: PASSWORD }, { form: true });
  await csrf(pc);
}

async function waitForHealth(pc, seconds = 180) {
  const deadline = Date.now() + seconds * 1000;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(`http://127.0.0.1:${pc.ui}/api/health`);
      if (response.ok) return;
    } catch {
      // Not up yet.
    }
    if (pc.process && pc.process.exitCode !== null) break;
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  throw new Error(`${pc.name} did not start.\n${log(pc)}`);
}

async function eventually(description, check, seconds = 30) {
  const deadline = Date.now() + seconds * 1000;
  let last;
  while (Date.now() < deadline) {
    try {
      if (await check()) {
        console.log(`ok - ${description}`);
        return;
      }
    } catch (error) {
      last = error;
    }
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  throw new Error(`Timed out: ${description}${last ? ` (${last.message})` : ''}`);
}

function check(condition, description) {
  if (!condition) throw new Error(`Failed: ${description}`);
  console.log(`ok - ${description}`);
}

const companyNames = async (pc) => (await request(pc, 'GET', '/api/companies')).map((company) => company.name);

async function main() {
  start(first);
  start(second);
  await Promise.all([waitForHealth(first), waitForHealth(second)]);

  // The first computer starts the CRM.
  const status = await request(first, 'GET', '/api/setup/status');
  check(status.desktop && status.needsSetup, 'a fresh desktop copy asks to be set up');
  await csrf(first);
  const { key } = await request(first, 'POST', '/api/setup/start', {
    computerName: 'First PC',
    name: 'Michael Drougas',
    email: EMAIL,
    password: PASSWORD,
  });
  check(typeof key === 'string' && key.length === 44, 'starting a CRM returns a new key');
  check(existsSync(join(first.home, 'settings.properties')), 'the key is saved in the settings file');

  await signIn(first);
  const acme = await request(first, 'POST', '/api/companies', { name: 'Acme AI', status: 'LEAD', notes: 'Secret notes' });
  const contact = await request(first, 'POST', '/api/contacts', {
    name: 'Dana Reyes',
    email: 'dana@acme.test',
    companyId: acme.id,
  });
  check(acme.id > 1_000_000_000 && acme.id < 2_000_000_000, 'the first computer uses ids from its own range');

  // A wrong key is refused.
  await csrf(second);
  let refused = false;
  try {
    await request(second, 'POST', '/api/setup/join', {
      address: `127.0.0.1:${first.sync}`,
      key: 'ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=',
      computerName: 'Second PC',
    });
  } catch (error) {
    refused = error.message.includes('different CRM key');
  }
  check(refused, 'joining with the wrong key is refused');

  // The second computer joins and copies everything.
  const joined = await request(second, 'POST', '/api/setup/join', {
    address: `127.0.0.1:${first.sync}`,
    key,
    computerName: 'Second PC',
  });
  check(joined.nodeId === 2 && joined.joinedThrough === 'First PC' && !joined.warning, 'the second computer joins as computer 2');

  await signIn(second);
  check((await companyNames(second)).includes('Acme AI'), 'the second computer has the first computer\'s companies');
  const copied = await request(second, 'GET', `/api/contacts/${contact.id}`);
  check(copied.email === 'dana@acme.test', 'encrypted fields arrive readable with the shared key');

  // Changes flow both ways.
  const fromSecond = await request(second, 'POST', '/api/companies', { name: 'Zest Risk', status: 'LEAD' });
  check(fromSecond.id > 2_000_000_000, 'the second computer uses ids from its own range');
  await eventually('a company added on the second computer reaches the first', async () =>
    (await companyNames(first)).includes('Zest Risk'));

  await request(first, 'PUT', `/api/companies/${acme.id}`, { name: 'Acme AI', status: 'ENGAGED', ownerId: null });
  await eventually('an edit on the first computer reaches the second', async () =>
    (await request(second, 'GET', `/api/companies/${acme.id}`)).status === 'ENGAGED');

  await request(second, 'POST', `/api/contacts/${contact.id}/activities`, { type: 'LINKEDIN' });
  await eventually('a touch logged on the second computer reaches the first', async () =>
    (await request(first, 'GET', `/api/contacts/${contact.id}`)).touchCount === 1);

  await request(second, 'DELETE', `/api/companies/${fromSecond.id}`);
  await eventually('a delete on the second computer reaches the first', async () =>
    !(await companyNames(first)).includes('Zest Risk'));

  const desktop = await request(first, 'GET', '/api/desktop/status');
  check(desktop.sync.peers.length === 1 && desktop.sync.peers[0].name === 'Second PC', 'the first computer lists the second');
  await eventually('the first computer reports the second as in step', async () => {
    const peer = (await request(first, 'GET', '/api/desktop/status')).sync.peers[0];
    return peer.secondsSinceSuccess !== null && peer.secondsSinceSuccess < 30 && !peer.lastError;
  });

  // Work done while one computer is off arrives when it comes back.
  await stop(second);
  await request(first, 'POST', '/api/companies', { name: 'Made While Off', status: 'LEAD' });
  start(second);
  await waitForHealth(second);
  await signIn(second);
  await eventually('changes made while a computer was off arrive when it is back', async () =>
    (await companyNames(second)).includes('Made While Off'));
  check((await companyNames(second)).includes('Acme AI'), 'a restarted computer keeps its own copy');

  console.log('All sync checks passed');
}

try {
  await main();
} catch (error) {
  console.error(error.message);
  console.error('--- first computer log ---\n' + log(first));
  console.error('--- second computer log ---\n' + log(second));
  process.exitCode = 1;
} finally {
  await Promise.all([stop(first), stop(second)]);
}
