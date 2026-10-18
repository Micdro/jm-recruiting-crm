import { useState } from 'react';
import { api, errorMessage } from '../api.js';
import { ConfirmButton, Empty, ErrorNote, Loading, PageHeader } from '../components/Bits.jsx';
import BackupsPanel from '../components/BackupsPanel.jsx';
import FolderField from '../components/FolderField.jsx';
import { updateMessage, useAppUpdate } from '../appUpdate.js';
import { timeAgo } from '../format.js';
import { useAuth, useInterval, useLoader, useTitle, useToast } from '../hooks.js';
import { connectedDirectly, inStep } from '../syncState.js';

/** Computers note in the sync folder every couple of minutes that they're running. */
function runningLabel(seconds) {
  if (seconds === null || seconds === undefined) return null;
  if (seconds < 5 * 60) return 'Running now';
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `Last running ${minutes} minutes ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 48) return `Last running ${hours === 1 ? '1 hour' : `${hours} hours`} ago`;
  return `Last running ${Math.round(hours / 24)} days ago`;
}

export default function ThisComputerPage() {
  useTitle('This computer');
  const { user } = useAuth();
  const toast = useToast();
  const status = useLoader(() => api.desktop.status(), [], 'desktop:status');
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [changingFolder, setChangingFolder] = useState(false);
  const [folderPath, setFolderPath] = useState('');

  useInterval(status.reload, 5000);

  async function act(name, action, done) {
    setBusy(name);
    setError('');
    try {
      const result = await action();
      if (result) status.setData(result);
      if (done) toast(done);
      return true;
    } catch (err) {
      setError(errorMessage(err));
      return false;
    } finally {
      setBusy('');
    }
  }

  const data = status.data;
  if (!data) return status.error ? <ErrorNote>{errorMessage(status.error)}</ErrorNote> : <Loading label="Loading" />;

  const peers = data.sync.peers;
  const isAdmin = user?.role === 'ADMIN';
  // The newest version any computer here knows of; computers on an older one should update.
  const newest = Math.max(data.update?.build ?? 0, data.update?.latestBuild ?? 0, ...peers.map((peer) => peer.build ?? 0));

  return (
    <>
      <PageHeader
        title="This computer"
        sub={data.computerName ? `${data.computerName}, computer ${data.nodeId} in your group.` : 'Not set up for sync.'}
      >
        <button type="button" className="btn" disabled={busy === 'sync'} onClick={() => act('sync', api.desktop.syncNow)}>
          {busy === 'sync' ? 'Syncing…' : 'Sync now'}
        </button>
      </PageHeader>
      <ErrorNote>{error}</ErrorNote>

      <section className="panel" aria-labelledby="folder-heading">
        <div className="panel-head">
          <h2 id="folder-heading">Sync folder</h2>
        </div>
        {data.folder?.path && !changingFolder ? (
          <div className="setting-row">
            <p>
              <strong className="folder-path">{data.folder.path}</strong>
              {data.folder.problem ? (
                <span className="computer-state computer-state-problem">{data.folder.problem}</span>
              ) : (
                'The computers leave their changes here for each other, encrypted. Google Drive copies it between them.'
              )}
            </p>
            <button
              type="button"
              className="btn btn-sm"
              onClick={() => {
                setFolderPath(data.folder.path);
                setChangingFolder(true);
              }}
            >
              Change
            </button>
          </div>
        ) : (
          <form
            className="form folder-form"
            onSubmit={(event) => {
              event.preventDefault();
              act('folder', () => api.desktop.useFolder(folderPath), 'Syncing through the folder').then(
                (ok) => ok && setChangingFolder(false),
              );
            }}
          >
            {!data.folder?.path && (
              <p className="form-intro">
                Choose a folder in Google Drive (or OneDrive). The computers sync through it, so they never need to reach each
                other directly. Then share the folder with the team.
              </p>
            )}
            <FolderField value={folderPath} onChange={setFolderPath} required />
            <div className="form-actions">
              <button type="submit" className="btn btn-primary" disabled={busy === 'folder'}>
                {busy === 'folder' ? 'Saving…' : 'Use this folder'}
              </button>
              {changingFolder && (
                <button type="button" className="btn btn-quiet" onClick={() => setChangingFolder(false)}>
                  Cancel
                </button>
              )}
            </div>
          </form>
        )}
      </section>

      <DirectPanel
        direct={data.direct}
        windows={data.windows}
        busy={busy === 'direct'}
        onToggle={() =>
          act('direct', () => api.desktop.setDirect(!data.direct.enabled), data.direct.enabled ? 'Direct connections off' : 'Direct connections on')
        }
      />

      <section className="panel" aria-labelledby="computers-heading">
        <div className="panel-head">
          <h2 id="computers-heading">Other computers</h2>
        </div>
        {peers.length === 0 ? (
          <Empty title="No other computers yet">
            Share the sync folder with the team, then set up the CRM on another computer and choose “Join the team’s CRM”.
          </Empty>
        ) : (
          <ul className="computers">
            {peers.map((peer) => {
              const direct = connectedDirectly(peer);
              const fresh = inStep(peer);
              const ago = timeAgo(peer.secondsSinceSuccess, peer.lastSuccess);
              const problem = !fresh && peer.lastError;
              const running = direct ? 'Running now' : runningLabel(peer.secondsSinceSeen);
              return (
                <li key={peer.id}>
                  <div>
                    <span className="computer-name">{peer.name}</span>{' '}
                    {running && <span className="muted">{running}</span>}
                    {peer.build && (
                      <span className={peer.build < newest ? 'computer-version computer-state-problem' : 'computer-version muted'}>
                        {peer.build < newest ? `Version 1.0.${peer.build}. Update it on that computer.` : `Version 1.0.${peer.build}`}
                      </span>
                    )}
                    <p className={`computer-state ${fresh ? 'computer-state-ok' : problem ? 'computer-state-problem' : ''}`}>
                      {direct
                        ? 'Connected directly. Up to date with its changes.'
                        : fresh
                        ? 'Up to date with its changes.'
                        : problem
                          ? `${peer.lastError}${peer.lastSuccess ? ` Last synced ${ago}.` : ''}`
                          : peer.lastSuccess
                            ? `Last synced ${ago}.`
                            : 'Not synced yet.'}
                    </p>
                  </div>
                  {isAdmin && (
                    <ConfirmButton
                      label="Remove"
                      confirmLabel="Remove computer"
                      className="btn btn-sm btn-quiet"
                      onConfirm={() => act('remove', () => api.desktop.removeComputer(peer.id), `Removed ${peer.name}`)}
                    />
                  )}
                </li>
              );
            })}
          </ul>
        )}
        {data.sync.waiting > 0 && (
          <p className="warn-note">
            {data.sync.waiting} {data.sync.waiting === 1 ? 'change is' : 'changes are'} waiting for related records from another
            computer. They apply as soon as those arrive.
          </p>
        )}
      </section>

      <section className="panel" aria-labelledby="settings-heading">
        <div className="panel-head">
          <h2 id="settings-heading">On this computer</h2>
        </div>

        <VersionRow />

        {data.windows && (
          <div className="setting-row">
            <p>
              <strong>Start with Windows</strong>
              Opens the CRM quietly when you sign in to Windows, so it keeps syncing while you work in other apps.
            </p>
            <button
              type="button"
              className="btn btn-sm"
              disabled={busy === 'startup'}
              onClick={() =>
                act('startup', () => api.desktop.setStartup(!data.startsWithWindows), data.startsWithWindows ? 'Won’t start with Windows' : 'Starts with Windows')
              }
            >
              {data.startsWithWindows ? 'Turn off' : 'Turn on'}
            </button>
          </div>
        )}

        {data.reminder?.supported && (
          <ReminderRow
            reminder={data.reminder}
            busy={busy === 'reminder'}
            onSave={(enabled, time, done) => act('reminder', () => api.desktop.setReminder(enabled, time), done)}
            onShow={() =>
              act('reminder', async () => {
                const result = await api.desktop.showReminder();
                if (!result.shown) throw new Error('Windows didn’t show the notification. Restart the CRM and try again.');
              }, 'Reminder sent. It shows by the clock.')
            }
          />
        )}

        <div className="setting-row">
          <p>
            <strong>Close the CRM</strong>
            Stops the app on this computer. Sync pauses until you open it again; nothing is lost.
          </p>
          <ConfirmButton
            label="Close"
            confirmLabel="Close the CRM"
            className="btn btn-sm"
            onConfirm={async () => {
              await api.desktop.quit();
              document.title = 'JM CRM closed';
              document.body.textContent = 'The CRM is closed. Open JM CRM from the Start menu to use it again.';
            }}
          />
        </div>
      </section>

      <BackupsPanel />

      <section className="panel" aria-labelledby="add-heading">
        <div className="panel-head">
          <h2 id="add-heading">Adding another computer</h2>
        </div>
        <ol className="plain-list numbered">
          <li>
            In Google Drive, share the sync folder with that person as an <strong>Editor</strong>.
          </li>
          <li>
            On their computer, install Google Drive for desktop and sign in. In Google Drive on the web, open{' '}
            <strong>Shared with me</strong>, right-click the folder and choose <strong>Organize</strong>, then{' '}
            <strong>Add shortcut</strong> to My Drive.
          </li>
          <li>
            Install JM CRM, open it, choose <strong>Join the team’s CRM</strong>, pick the folder and paste the CRM key from your
            password manager.
          </li>
          <li>Sign in with that person’s CRM account. Admins add accounts on the Team page.</li>
        </ol>
      </section>
    </>
  );
}

/** The weekday-morning Windows notification with what's due today. */
function ReminderRow({ reminder, busy, onSave, onShow }) {
  const [draft, setDraft] = useState(null);
  const time = draft ?? reminder.time;
  const changed = draft !== null && draft !== reminder.time;

  return (
    <div className="setting-row">
      <div className="setting-text">
        <p>
          <strong>Morning reminder</strong>
          {reminder.enabled
            ? 'Each weekday, a Windows notification lists what’s due today for the last person to sign in here. Click it to open the CRM.'
            : 'Off. Turn it on for a Windows notification each weekday morning with what’s due today.'}
        </p>
        {reminder.preview && (
          <p className="reminder-preview" aria-label="Today’s reminder">
            <strong>{reminder.preview.title}</strong>
            {reminder.preview.body}
          </p>
        )}
        {reminder.enabled && !reminder.ready && (
          <p className="warn-note">Windows didn’t let the CRM add its icon by the clock, so the reminder can’t show. Restart the CRM.</p>
        )}
      </div>
      <div className="setting-actions">
        {reminder.enabled && (
          <form
            className="reminder-time"
            onSubmit={(event) => {
              event.preventDefault();
              onSave(true, time, `Reminder set for ${time}`).then((ok) => ok && setDraft(null));
            }}
          >
            <label htmlFor="reminder-time">At</label>
            <input id="reminder-time" type="time" required value={time} onChange={(event) => setDraft(event.target.value)} />
            {changed && (
              <button type="submit" className="btn btn-sm btn-primary" disabled={busy}>
                Save
              </button>
            )}
          </form>
        )}
        {reminder.enabled && (
          <button type="button" className="btn btn-sm" disabled={busy} onClick={onShow}>
            Show it now
          </button>
        )}
        <button
          type="button"
          className="btn btn-sm"
          disabled={busy}
          onClick={() => onSave(!reminder.enabled, reminder.time, reminder.enabled ? 'Morning reminder off' : 'Morning reminder on')}
        >
          {reminder.enabled ? 'Turn off' : 'Turn on'}
        </button>
      </div>
    </div>
  );
}

/** Whether other computers can connect straight to this one, and where. */
function DirectPanel({ direct, windows, busy, onToggle }) {
  const firewall = useLoader(
    () => (windows && direct.enabled ? api.desktop.firewall() : Promise.resolve({ allowed: true })),
    [windows, direct.enabled],
  );
  const toast = useToast();
  const blocked = direct.enabled && firewall.data && !firewall.data.allowed;

  return (
    <section className="panel" aria-labelledby="direct-heading">
      <div className="panel-head">
        <h2 id="direct-heading">Direct connections</h2>
      </div>
      <div className="setting-row">
        <p>
          <strong>{direct.enabled ? 'On' : 'Off'}</strong>
          {direct.enabled ? (
            <DirectDetails direct={direct} />
          ) : (
            <>
              Changes travel through the sync folder, usually within a minute. Turn this on so the other computers can
              connect to this one directly and get changes within seconds. One computer that’s usually running is enough.
              {windows && ' Windows asks twice about network access: click Allow access, then Yes.'}
            </>
          )}
        </p>
        <button type="button" className="btn btn-sm" disabled={busy} onClick={onToggle}>
          {busy ? (direct.enabled ? 'Turning off…' : 'Turning on…') : direct.enabled ? 'Turn off' : 'Turn on'}
        </button>
      </div>
      {blocked && (
        <div className="setting-row">
          <p>
            <strong>Windows Firewall</strong>
            <span className="computer-state computer-state-problem">Windows is blocking the other computers.</span>
          </p>
          <button
            type="button"
            className="btn btn-sm"
            onClick={async () => {
              await api.desktop.allowFirewall();
              toast('Click Yes when Windows asks');
              setTimeout(firewall.reload, 8000);
            }}
          >
            Allow
          </button>
        </div>
      )}
    </section>
  );
}

function DirectDetails({ direct }) {
  if (!direct.listening) {
    return (
      <span className="computer-state computer-state-problem">
        The CRM couldn’t open its port for other computers. Another program may be using it. Restart the CRM to try again.
      </span>
    );
  }
  return (
    <>
      {direct.internetAddress ? (
        <span className="computer-state computer-state-ok">
          Other homes connect at {direct.internetAddress}, through your router.
        </span>
      ) : (
        direct.routerProblem && <span className="computer-state">{direct.routerProblem}</span>
      )}
      {direct.homeAddresses.length > 0 && (
        <span className="computer-state muted">On this network: {direct.homeAddresses.join(', ')}</span>
      )}
    </>
  );
}

/** This computer's version, and installing a newer one. */
function VersionRow() {
  const update = useAppUpdate();
  const { data } = update;
  if (!data?.supported) return null;

  return (
    <div className="setting-row">
      <p>
        <strong>Version {data.version}</strong>
        {updateMessage(update) || 'Up to date. The CRM checks for a new version every hour.'}
        {update.problem && <span className="computer-state computer-state-problem">{update.problem}</span>}
      </p>
      {!update.busy &&
        (data.available ? (
          <button type="button" className="btn btn-sm btn-primary" onClick={update.install}>
            Update now
          </button>
        ) : (
          <button type="button" className="btn btn-sm" disabled={update.checking} onClick={update.check}>
            {update.checking ? 'Checking…' : 'Check for updates'}
          </button>
        ))}
    </div>
  );
}
