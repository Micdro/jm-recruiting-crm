import { useLocation } from 'react-router';
import { updateMessage, useAppUpdate } from '../appUpdate.js';

/** A strip at the top of every page when a newer version is ready (desktop app only). */
export default function UpdateBanner() {
  const location = useLocation();
  const update = useAppUpdate();
  const { data } = update;
  if (!data?.supported) return null;
  // The This computer page shows it in its own row.
  if (location.pathname === '/computer' && !update.busy) return null;
  if (!data.available && !update.busy) return null;

  return (
    <div className="update-banner" role="status">
      <p>
        {updateMessage(update)}
        {update.problem && <span className="update-problem">{update.problem}</span>}
      </p>
      {!update.busy && (
        <button type="button" className="btn btn-sm btn-primary" onClick={update.install}>
          {update.problem ? 'Try again' : 'Update now'}
        </button>
      )}
    </div>
  );
}
