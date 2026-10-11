import { useCallback, useEffect, useState } from 'react';
import { api } from '../api.js';
import { DesktopContext } from '../contexts.js';
import SetupPage from '../pages/SetupPage.jsx';
import { Loading } from './Bits.jsx';

/**
 * On a desktop copy that hasn't been set up, shows the setup screen instead of the app.
 * Everywhere else it just tells the app whether it is the desktop version.
 */
export default function SetupGate({ children }) {
  const [status, setStatus] = useState(null);

  const load = useCallback(
    () =>
      api.setup.status().then(setStatus, () => setStatus({ desktop: false, needsSetup: false })),
    [],
  );

  useEffect(() => {
    load();
  }, [load]);

  if (!status) {
    return (
      <div className="boot">
        <Loading label="Opening the CRM" />
      </div>
    );
  }
  if (status.needsSetup) {
    return <SetupPage status={status} onDone={load} />;
  }
  return <DesktopContext.Provider value={status.desktop}>{children}</DesktopContext.Provider>;
}
