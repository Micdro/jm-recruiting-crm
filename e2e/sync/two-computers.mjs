// Runs desktop copies of the CRM side by side (as separate computers would) and checks that they
// set up, join and sync both ways through a shared folder (standing in for Google Drive), even
// when they're never on at the same time; that a third computer can join from a folder whose old
// files were folded into a snapshot; and that each copy stays locked (no key on disk, nothing
// readable in the database or the shared folder) until someone signs in.
//
//   node sync/two-computers.mjs <path to desktop jar>
//
// Exit code 0 means everything synced.

import { existsSync, mkdtempSync, readFileSync, readdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  check,
  companyNames,
  computer,
  csrf,
  eventually,
  filesContaining,
  locked,
  request,
  run,
  signIn as signInAs,
  signOut,
  sleep,
  start as startComputer,
  stop,
  waitForHealth,
} from './desktop.mjs';

const jar = process.argv[2];
if (!jar) {
  console.error('Usage: node sync/two-computers.mjs <desktop jar>');
  process.exit(2);
}

const PASSWORD = 'two-computers-password';
const EMAIL = 'michael@two-computers.test';

const first = computer('first', 4780);
const second = computer('second', 4790);
const third = computer('third', 4800);
const computers = [first, second, third];

// What Google Drive would keep the same on every computer. The CRM creates the folder itself.
const shared = mkdtempSync(join(tmpdir(), 'jmcrm-drive-'));
const syncFolder = join(shared, 'JM CRM Sync');
const OTHER_KEY = 'ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=';
const SECRETS = ['Dana Reyes', 'dana@acme.test', 'Secret notes', 'Acme AI'];

// Small numbers, so a snapshot replaces old sync files within this test.
const start = (pc) => startComputer(pc, jar, ['-Dapp.sync.interval-ms=1000', '-Dapp.sync.folder.compact-after=4']);
const signIn = (pc, password = PASSWORD) => signInAs(pc, EMAIL, password);

async function main() {
  start(first);
  start(second);
  await Promise.all([waitForHealth(first), waitForHealth(second)]);

  // The first computer starts the CRM in the shared folder.
  const status = await request(first, 'GET', '/api/setup/status');
  check(status.desktop && status.needsSetup, 'a fresh desktop copy asks to be set up');
  await csrf(first);
  const { key } = await request(first, 'POST', '/api/setup/start', {
    computerName: 'First PC',
    name: 'Michael Drougas',
    email: EMAIL,
    password: PASSWORD,
    syncFolder,
  });
  check(typeof key === 'string' && key.length === 44, 'starting a CRM returns a new key');
  check(existsSync(join(syncFolder, 'crm.check')) && existsSync(join(syncFolder, 'computer-1')),
    'the sync folder is created and marked as this CRM\'s');
  const settings = readFileSync(join(first.home, 'settings.properties'), 'utf8');
  check(settings.includes('sync.token') && !settings.includes(key.slice(0, 40)), 'only the sync token is saved, not the key');
  check(await locked(first), 'the CRM is locked until someone signs in');

  await signIn(first);
  check(!(await locked(first)), 'signing in unlocks it');
  check((await request(first, 'GET', '/api/auth/me')).crmLocked === false, 'the app is told it is unlocked');
  const acme = await request(first, 'POST', '/api/companies', { name: 'Acme AI', status: 'LEAD', notes: 'Secret notes' });
  const contact = await request(first, 'POST', '/api/contacts', {
    name: 'Dana Reyes',
    email: 'dana@acme.test',
    companyId: acme.id,
  });
  check(acme.id > 1_000_000_000 && acme.id < 2_000_000_000, 'the first computer uses ids from its own range');
  const folderStatus = (await request(first, 'GET', '/api/desktop/status')).folder;
  check(folderStatus.path === syncFolder && !folderStatus.problem, 'the first computer reports the sync folder as working');

  // A wrong key is refused.
  await csrf(second);
  let refused = false;
  try {
    await request(second, 'POST', '/api/setup/join', { syncFolder, key: OTHER_KEY, computerName: 'Second PC' });
  } catch (error) {
    refused = error.message.includes('different key');
  }
  check(refused, 'joining with the wrong key is refused');

  // The second computer joins through the folder and copies everything.
  await sleep(2000);
  const joined = await request(second, 'POST', '/api/setup/join', { syncFolder, key, computerName: 'Second PC' });
  check(joined.nodeId === 2 && joined.computers === 1 && joined.copied > 0 && !joined.warning,
    'the second computer joins as computer 2 and copies the first computer\'s records');
  check(await locked(second), 'the joined computer is locked until someone signs in');
  check(!readFileSync(join(second.home, 'settings.properties'), 'utf8').includes(key.slice(0, 40)), 'the joined computer does not save the key');

  await signIn(second);
  check(!(await locked(second)), 'the password unlocks the joined computer (its copy of the key came with the account)');
  await eventually('the second computer has the first computer\'s companies', async () =>
    (await companyNames(second)).includes('Acme AI'));
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

  await eventually('the first computer lists the second and reports it as in step', async () => {
    const peers = (await request(first, 'GET', '/api/desktop/status')).sync.peers;
    const peer = peers.find((candidate) => candidate.name === 'Second PC');
    return peers.length === 1 && peer && peer.secondsSinceSuccess !== null && peer.secondsSinceSuccess < 30
      && !peer.lastError && peer.secondsSinceSeen !== null && peer.secondsSinceSeen < 300;
  });

  // The unlock screen's endpoint checks the key and the password.
  let wrongKey = false;
  try {
    await request(second, 'POST', '/api/desktop/unlock', { key: OTHER_KEY, password: PASSWORD });
  } catch (error) {
    wrongKey = error.message.includes('400');
  }
  check(wrongKey, 'unlocking with another CRM\'s key is refused');
  await request(second, 'POST', '/api/desktop/unlock', { key, password: PASSWORD });
  check(!(await locked(second)), 'the CRM key and password unlock it');

  // Signing out locks it again; sync keeps running while locked.
  await signOut(first);
  check(await locked(first), 'signing out locks the CRM');
  await request(second, 'POST', '/api/companies', { name: 'Added While Locked', status: 'LEAD' });
  await sleep(4000);
  await signIn(first);
  await eventually('changes sync to a computer while it is locked', async () =>
    (await companyNames(first)).includes('Added While Locked'));

  // Nothing readable in the database files or the shared folder.
  const readable = filesContaining(join(first.home, 'database'), SECRETS);
  check(readable.length === 0, `no names, emails or notes are readable in the database files${readable.length ? `: ${readable.join(', ')}` : ''}`);
  const inFolder = filesContaining(syncFolder, SECRETS);
  check(inFolder.length === 0, `nothing is readable in the shared folder${inFolder.length ? `: ${inFolder.join(', ')}` : ''}`);

  // The computers never need to be on at the same time: the second gets the first's work while the
  // first is off.
  await stop(second);
  await request(first, 'POST', '/api/companies', { name: 'Made While Off', status: 'LEAD' });
  await sleep(3000);
  await stop(first);
  start(second);
  await waitForHealth(second);
  check(await locked(second), 'a restarted computer starts locked');
  await signIn(second);
  await eventually('work done while a computer was off arrives even with the other computer off', async () =>
    (await companyNames(second)).includes('Made While Off'));
  check((await companyNames(second)).includes('Acme AI'), 'a restarted computer keeps its own copy');

  // Enough changes for old sync files to be folded into a snapshot.
  for (let i = 1; i <= 6; i++) {
    await request(second, 'POST', '/api/companies', { name: `Batch Co ${i}`, status: 'LEAD' });
    await sleep(1500);
  }
  await eventually('old sync files are replaced by a snapshot', async () =>
    readdirSync(join(syncFolder, 'computer-2')).some((file) => file.startsWith('snapshot-')));

  // A third computer joins from the compacted folder while the first is still off.
  start(third);
  await waitForHealth(third);
  await csrf(third);
  const thirdJoined = await request(third, 'POST', '/api/setup/join', { syncFolder, key, computerName: 'Third PC' });
  check(thirdJoined.nodeId === 3 && thirdJoined.computers === 2, 'a third computer joins as computer 3');
  await signIn(third);
  await eventually('the third computer has everything, from the snapshot and the newer files', async () => {
    const names = await companyNames(third);
    return ['Acme AI', 'Made While Off', 'Added While Locked', 'Batch Co 6'].every((name) => names.includes(name))
      && !names.includes('Zest Risk');
  });

  // The first computer comes back and catches up on everything it missed.
  start(first);
  await waitForHealth(first);
  await signIn(first);
  await eventually('a computer that was off catches up when it is back', async () => {
    const names = await companyNames(first);
    return names.includes('Batch Co 6');
  });
  await eventually('the first computer lists both other computers', async () =>
    (await request(first, 'GET', '/api/desktop/status')).sync.peers.length === 2);

  // A forgotten password: the CRM key sets a new one, which then unlocks the CRM.
  await signOut(second);
  let resetRefused = false;
  try {
    await request(second, 'POST', '/api/setup/reset-password', {
      email: EMAIL,
      key: OTHER_KEY,
      password: 'a-brand-new-password',
    });
  } catch (error) {
    resetRefused = error.message.includes('400');
  }
  check(resetRefused, 'resetting a password needs this CRM\'s key');
  await request(second, 'POST', '/api/setup/reset-password', { email: EMAIL, key, password: 'a-brand-new-password' });
  check(await locked(second), 'resetting a password does not unlock the CRM by itself');
  await signIn(second, 'a-brand-new-password');
  check(!(await locked(second)), 'the new password unlocks the CRM');

  console.log('All sync checks passed');
}

await run(computers, main);
