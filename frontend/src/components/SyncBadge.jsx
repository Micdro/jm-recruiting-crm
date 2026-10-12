import { Link } from 'react-router';
import { api } from '../api.js';
import { useInterval, useLoader } from '../hooks.js';
import { connectedDirectly, inStep } from '../syncState.js';

/** A one-line sync summary under the user's name in the sidebar (desktop app only). */
export default function SyncBadge() {
  const status = useLoader(() => api.desktop.status(), [], 'desktop:status');
  useInterval(status.reload, 15000);

  const peers = status.data?.sync?.peers;
  if (!peers) return null;
  const folder = status.data.folder;

  const behind = peers.filter((peer) => !inStep(peer)).length;

  let label;
  let tone;
  // Without a folder, only computers connected directly sync.
  if (!folder?.path && !peers.some(connectedDirectly)) {
    label = 'Choose a sync folder';
    tone = '';
  } else if (folder?.problem) {
    label = 'Sync folder unavailable';
    tone = 'problem';
  } else if (peers.length === 0) {
    label = 'No other computers yet';
    tone = '';
  } else if (behind === 0) {
    label = 'Synced';
    tone = 'ok';
  } else {
    label = behind === 1 ? '1 computer catching up' : `${behind} computers catching up`;
    tone = 'problem';
  }

  return (
    <Link to="/computer" className={`sync-badge ${tone ? `sync-badge-${tone}` : ''}`}>
      {label}
    </Link>
  );
}
