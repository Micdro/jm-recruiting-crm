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

  const behind = peers.filter((peer) => peer.secondsSinceSuccess === null || peer.secondsSinceSuccess > FRESH_SECONDS).length;

  let label;
  let tone;
  if (peers.length === 0) {
    label = 'No other computers yet';
    tone = '';
  } else if (behind === 0) {
    label = 'Synced';
    tone = 'ok';
  } else {
    label = behind === 1 ? '1 computer not reachable' : `${behind} computers not reachable`;
    tone = 'problem';
  }

  return (
    <Link to="/computer" className={`sync-badge ${tone ? `sync-badge-${tone}` : ''}`}>
      {label}
    </Link>
  );
}
