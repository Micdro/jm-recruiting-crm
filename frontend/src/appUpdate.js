import { useCallback, useState } from 'react';
import { api, errorMessage } from './api.js';
import { useInterval, useLoader } from './hooks.js';

const BUSY = ['DOWNLOADING', 'INSTALLING', 'RESTARTING'];

/**
 * How often an open page asks this computer whether an update is ready. It's a local question; the
 * computer itself looks at the release page every hour.
 */
export const UPDATE_POLL_MS = 5 * 60 * 1000;

/** When the CRM on this computer last started, from its health check. Null while it's down. */
async function serverStarted() {
  try {
    const response = await fetch('/api/health', { cache: 'no-store' });
    return response.ok ? ((await response.json()).started ?? null) : null;
  } catch {
    return null;
  }
}

let watching = false;

/**
 * Reloads the page once the CRM has restarted (it reports a new start time), so the new version's
 * pages load. Kept outside React so it carries on even if the page signs out meanwhile.
 */
function reloadWhenRestarted(before, onGiveUp) {
  if (watching) return;
  watching = true;
  const deadline = Date.now() + 5 * 60 * 1000;
  const tick = async () => {
    const started = await serverStarted();
    if (started && started !== before) {
      window.location.assign('/');
      return;
    }
    if (Date.now() > deadline) {
      watching = false;
      onGiveUp();
      return;
    }
    setTimeout(tick, 1500);
  };
  setTimeout(tick, 1500);
}

/** This computer's version, whether a newer one is ready, and installing it. */
export function useAppUpdate() {
  const status = useLoader(() => api.desktop.update(), [], 'desktop:update');
  const [working, setWorking] = useState(false);
  const [checking, setChecking] = useState(false);
  const [error, setError] = useState('');
  const data = status.data;
  const phase = data?.phase;
  const busy = (working && phase !== 'FAILED') || BUSY.includes(phase);

  // Quickly while updating; otherwise every few minutes, to show an update soon after it's found.
  useInterval(status.reload, busy ? 1000 : UPDATE_POLL_MS);

  const install = useCallback(async () => {
    setError('');
    setWorking(true);
    const before = await serverStarted();
    try {
      status.setData(await api.desktop.installUpdate());
      reloadWhenRestarted(before, () => {
        setWorking(false);
        setError('The CRM didn’t come back after updating. Open JM CRM from the Start menu.');
      });
    } catch (err) {
      setWorking(false);
      setError(errorMessage(err));
    }
  }, [status]);

  const check = useCallback(async () => {
    setError('');
    setChecking(true);
    try {
      status.setData(await api.desktop.checkForUpdate());
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setChecking(false);
    }
  }, [status]);

  return {
    data,
    busy,
    down: Boolean(status.error),
    checking,
    problem: error || data?.problem || '',
    install,
    check,
  };
}

/** Whether a newer version is waiting to be installed on this computer, for the badge in the menu. */
export function useUpdateReady() {
  const status = useLoader(() => api.desktop.update(), [], 'desktop:update');
  useInterval(status.reload, UPDATE_POLL_MS);
  const data = status.data;
  return Boolean(data?.supported && data.available && !BUSY.includes(data.phase));
}

/** What's happening with an update, in a sentence. */
export function updateMessage(update) {
  const { data } = update;
  if (!data) return '';
  if (update.busy) {
    if (data.phase === 'DOWNLOADING') return `Downloading version ${data.latest}… ${data.percent ?? 0}%`;
    if (data.phase === 'INSTALLING') return `Installing version ${data.latest}…`;
    if (data.phase === 'RESTARTING' || update.down) {
      return 'Restarting the CRM. This page reloads when it’s back, in about a minute. Then sign in again.';
    }
    return 'Starting the update…';
  }
  if (data.available) return `Version ${data.latest} is ready. Installing it restarts the CRM, and you sign in again.`;
  return '';
}
