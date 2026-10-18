import { useEffect, useRef } from 'react';
import { useLocation } from 'react-router';
import { updateMessage, useAppUpdate } from '../appUpdate.js';

/**
 * A navy strip pinned to the top of every page when a newer version is ready, with Update now, and
 * the download and install progress once it starts (desktop app only).
 */
export default function UpdateBanner() {
  const location = useLocation();
  const update = useAppUpdate();
  const ref = useRef(null);
  const { data } = update;
  // The This computer page shows a ready update in its own row.
  const shown = Boolean(data?.supported) && (update.busy || (data.available && location.pathname !== '/computer'));

  // Other bars pinned to the top (like the bulk actions bar) sit just below it.
  useEffect(() => {
    const banner = ref.current;
    if (!shown || !banner) return undefined;
    const root = document.documentElement;
    const measure = () => root.style.setProperty('--update-banner-height', `${banner.offsetHeight}px`);
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(banner);
    return () => {
      observer.disconnect();
      root.style.removeProperty('--update-banner-height');
    };
  }, [shown]);

  if (!shown) return null;

  return (
    <div ref={ref} className="update-banner" role="status">
      <svg className="update-banner-icon" viewBox="0 0 24 24" width="24" height="24" aria-hidden="true">
        <circle cx="12" cy="12" r="10.5" fill="none" stroke="currentColor" strokeWidth="1.8" />
        <path d="M12 6.5v8.5m-4-4 4 4 4-4" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
        <path d="M7.5 17.5h9" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
      </svg>
      <p className="update-banner-text">
        <strong>{update.busy ? 'Updating' : 'Update ready'}</strong> {updateMessage(update)}
        {update.problem && <span className="update-problem">{update.problem}</span>}
      </p>
      {!update.busy && (
        <button type="button" className="btn update-banner-button" onClick={update.install}>
          {update.problem ? 'Try again' : 'Update now'}
        </button>
      )}
    </div>
  );
}
