// Direct connections: two desktop copies whose shared folder is only read every ten minutes, so
// anything that arrives within seconds came over a direct connection. Only the first computer
// accepts connections; the second fetches from it and sends it its own changes.
//
//   node sync/direct.mjs <path to desktop jar>
//
// Exit code 0 means direct sync works both ways and the sync port refuses strangers.

import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  check,
  companyNames,
  computer,
  csrf,
  eventually,
  request,
  run,
  signIn as signInAs,
  start as startComputer,
  waitForHealth,
} from './desktop.mjs';

const jar = process.argv[2];
if (!jar) {
  console.error('Usage: node sync/direct.mjs <desktop jar>');
  process.exit(2);
}

const PASSWORD = 'direct-sync-password';
const EMAIL = 'michael@direct.test';

const office = computer('office', 4810);
const laptop = computer('laptop', 4820);
const computers = [office, laptop];
const syncFolder = join(mkdtempSync(join(tmpdir(), 'jmcrm-drive-')), 'JM CRM Sync');

// Direct rounds every second; the folder only every ten minutes. No router in CI to ask.
const start = (pc) =>
  startComputer(pc, jar, [
    '-Dapp.sync.interval-ms=1000',
    '-Dapp.sync.folder.interval-ms=600000',
    '-Dapp.sync.upnp.enabled=false',
  ]);
const signIn = (pc) => signInAs(pc, EMAIL, PASSWORD);
const desktop = (pc) => request(pc, 'GET', '/api/desktop/status');

async function main() {
  start(office);
  start(laptop);
  await Promise.all([waitForHealth(office), waitForHealth(laptop)]);

  await csrf(office);
  const { key } = await request(office, 'POST', '/api/setup/start', {
    computerName: 'Office PC',
    name: 'Michael Drougas',
    email: EMAIL,
    password: PASSWORD,
    syncFolder,
  });
  await csrf(laptop);
  await request(laptop, 'POST', '/api/setup/join', { syncFolder, key, computerName: 'Laptop' });
  await signIn(office);
  await signIn(laptop);

  // Off by default: nothing listens.
  let refused = false;
  try {
    await fetch(`http://127.0.0.1:${office.sync}/api/sync/changes?since=0`);
  } catch {
    refused = true;
  }
  check(refused, 'the sync port is closed until direct connections are turned on');

  const on = await request(office, 'PUT', '/api/desktop/direct', { enabled: true });
  check(on.direct.enabled && on.direct.listening && on.direct.homeAddresses.length > 0,
    'turning direct connections on opens the port and lists this computer\'s addresses');
  check(on.direct.routerProblem && !on.direct.internetAddress, 'without a router to ask, it says so');

  // A stranger without the key gets nothing.
  const stranger = await fetch(`http://127.0.0.1:${office.sync}/api/sync/changes?since=0`);
  const strangerBody = await stranger.text();
  check(stranger.status === 401 && !strangerBody.includes('Michael'), 'the sync port refuses requests not signed with the key');

  // The laptop learns where to connect from the folder ("Sync now" reads it right away).
  await request(laptop, 'POST', '/api/desktop/sync-now');
  await eventually('the laptop connects to the office computer directly', async () => {
    const peer = (await desktop(laptop)).sync.peers.find((candidate) => candidate.name === 'Office PC');
    return peer && peer.secondsSinceDirect !== null && peer.secondsSinceDirect < 10;
  });

  // The folder is only read every ten minutes now, so these can only travel directly.
  await request(office, 'POST', '/api/companies', { name: 'Direct From Office', status: 'LEAD' });
  await eventually('a change on the computer that accepts connections arrives within seconds', async () =>
    (await companyNames(laptop)).includes('Direct From Office'), 15);

  await request(laptop, 'POST', '/api/companies', { name: 'Direct From Laptop', status: 'LEAD' });
  await eventually('a change on the computer that doesn\'t is sent to the other within seconds', async () =>
    (await companyNames(office)).includes('Direct From Laptop'), 15);

  const officePeer = (await desktop(office)).sync.peers.find((candidate) => candidate.name === 'Laptop');
  check(officePeer && officePeer.secondsSinceDirect === null,
    'the office computer never needed to connect to the laptop');

  // Off again: the port closes.
  const off = await request(office, 'PUT', '/api/desktop/direct', { enabled: false });
  check(!off.direct.enabled && !off.direct.listening, 'turning direct connections off closes the port');
  let closed = false;
  try {
    await fetch(`http://127.0.0.1:${office.sync}/api/sync/changes?since=0`);
  } catch {
    closed = true;
  }
  check(closed, 'nothing answers on the sync port once it is off');

  console.log('All direct sync checks passed');
}

await run(computers, main);
