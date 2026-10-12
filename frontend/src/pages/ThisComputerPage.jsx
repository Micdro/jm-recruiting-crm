import { useState } from 'react';
import { api, errorMessage } from '../api.js';
import { ConfirmButton, Empty, ErrorNote, Loading, PageHeader } from '../components/Bits.jsx';
import FolderField from '../components/FolderField.jsx';
import { timeAgo } from '../format.js';
import { useAuth, useInterval, useLoader, useTitle, useToast } from '../hooks.js';

/** A computer counts as in step if it synced within this long (sync runs every few seconds). */
const FRESH_SECONDS = 60;

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
              const fresh = peer.secondsSinceSuccess !== null && peer.secondsSinceSuccess < FRESH_SECONDS && !peer.lastError;
              const ago = timeAgo(peer.secondsSinceSuccess, peer.lastSuccess);
              const problem = !fresh && peer.lastError;
              const running = runningLabel(peer.secondsSinceSeen);
              return (
                <li key={peer.id}>
                  <div>
                    <span className="computer-name">{peer.name}</span>{' '}
                    {running && <span className="muted">{running}</span>}
                    <p className={`computer-state ${fresh ? 'computer-state-ok' : problem ? 'computer-state-problem' : ''}`}>
                      {fresh
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
