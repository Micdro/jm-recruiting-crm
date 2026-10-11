import { useState } from 'react';
import { api, errorMessage } from '../api.js';
import { ConfirmButton, Empty, ErrorNote, Loading, PageHeader } from '../components/Bits.jsx';
import { timeAgo } from '../format.js';
import { useAuth, useInterval, useLoader, useTitle, useToast } from '../hooks.js';

/** A computer counts as in step if it synced within this long (sync runs every few seconds). */
const FRESH_SECONDS = 60;

export default function ThisComputerPage() {
  useTitle('This computer');
  const { user } = useAuth();
  const toast = useToast();
  const status = useLoader(() => api.desktop.status(), [], 'desktop:status');
  const firewall = useLoader(() => api.desktop.firewall(), [], 'desktop:firewall');
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');

  useInterval(status.reload, 5000);

  async function act(name, action, done) {
    setBusy(name);
    setError('');
    try {
      const result = await action();
      if (result) status.setData(result);
      if (done) toast(done);
    } catch (err) {
      setError(errorMessage(err));
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

      <section className="panel" aria-labelledby="computers-heading">
        <div className="panel-head">
          <h2 id="computers-heading">Other computers</h2>
        </div>
        {peers.length === 0 ? (
          <Empty title="No other computers yet">Set up the CRM on another computer and choose “Join the team’s CRM”.</Empty>
        ) : (
          <ul className="computers">
            {peers.map((peer) => {
              const fresh = peer.secondsSinceSuccess !== null && peer.secondsSinceSuccess < FRESH_SECONDS;
              const ago = timeAgo(peer.secondsSinceSuccess, peer.lastSuccess);
              const problem = !fresh && peer.lastError;
              return (
                <li key={peer.id}>
                  <div>
                    <span className="computer-name">{peer.name}</span>{' '}
                    <span className="muted">{peer.address ?? 'address not known yet'}</span>
                    <p className={`computer-state ${fresh ? 'computer-state-ok' : problem ? 'computer-state-problem' : ''}`}>
                      {fresh
                        ? `In step. Last synced ${ago}.`
                        : problem
                          ? `${peer.lastError} Last synced ${ago}.`
                          : peer.lastSuccess
                            ? `Last synced ${ago}.`
                            : 'Not synced yet. It catches up when both computers are on.'}
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

        {data.windows && (
          <div className="setting-row">
            <p>
              <strong>Windows Firewall</strong>
              {firewall.data?.allowed
                ? 'Your other computers can reach this one through Tailscale.'
                : 'Other computers can’t reach this one yet. Allow it, then click Yes when Windows asks.'}
            </p>
            {!firewall.data?.allowed && (
              <button
                type="button"
                className="btn btn-sm"
                onClick={() =>
                  act('firewall', async () => {
                    await api.desktop.allowFirewall();
                    setTimeout(() => firewall.reload(), 8000);
                  })
                }
              >
                Allow
              </button>
            )}
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
          <li>Install Tailscale on it and sign in to the same Tailscale account as this computer.</li>
          <li>Install JM CRM on it and open it.</li>
          <li>
            Choose <strong>Join the team’s CRM</strong>, enter this computer’s Tailscale name, and paste the CRM key from your password
            manager.
          </li>
          <li>Sign in with that person’s CRM account. Admins add accounts on the Team page.</li>
        </ol>
      </section>
    </>
  );
}
