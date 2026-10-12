import { Link } from 'react-router';
import { api } from '../api.js';
import { useInterval, useLoader } from '../hooks.js';

const FRESH_SECONDS = 60;

/** A one-line sync summary under the user's name in the sidebar (desktop app only). */
export default function SyncBadge() {
  const status = useLoader(() => api.desktop.status(), [], 'desktop:status');
  useInterval(status.reload, 15000);

  const peers = status.data?.sync?.peers;
  if (!peers) return null;
  const folder = status.data.folder;

  const behind = peers.filter(
    (peer) => peer.secondsSinceSuccess === null || peer.secondsSinceSuccess > FRESH_SECONDS || peer.lastError,
  ).length;

  let label;
  let tone;
  if (!folder?.path) {
    label = 'Choose a sync folder';
    tone = '';
  } else if (folder.problem) {
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
