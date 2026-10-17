import { useState } from 'react';
import { Link } from 'react-router';
import { api, errorMessage } from '../api.js';
import { useAuth, useToast } from '../hooks.js';
import { postingState, useLinkedIn } from '../marketing.js';

/**
 * Posts straight to the owner's LinkedIn profile, after one confirming click. Where that can't
 * happen it says why, and offers what can: connecting, or copying the post for the company page.
 */
export default function PostToLinkedIn({ post, onPosted, size = '' }) {
  const { user } = useAuth();
  const toast = useToast();
  const linkedin = useLinkedIn();
  const [armed, setArmed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const state = postingState(post, user, linkedin.data);
  const sm = size === 'sm' ? ' btn-sm' : '';

  async function publish() {
    setBusy(true);
    setError('');
    try {
      const updated = await api.marketing.publish(post.id);
      toast('Posted to LinkedIn');
      setArmed(false);
      onPosted?.(updated);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  async function copy() {
    try {
      await navigator.clipboard.writeText(post.body);
      toast('Copied. Paste it into the company page.');
    } catch {
      setError('Couldn’t copy. Open the post and copy it yourself.');
    }
  }

  if (state === 'done' || state === 'unwritten' || state === 'loading') return null;

  if (state === 'page') {
    return (
      <span className="post-to">
        <button type="button" className={`btn${sm}`} onClick={copy}>
          Copy for the page
        </button>
        {post.picture?.ready && (
          <a className={`btn btn-quiet${sm}`} href={post.picture.url} download={`post-${post.id}.jpg`}>
            Save picture
          </a>
        )}
        {error && <span className="post-to-error" role="alert">{error}</span>}
      </span>
    );
  }

  if (state === 'someone-else') {
    return <span className="post-to-note muted">{post.ownerName?.split(' ')[0] ?? 'Its owner'} posts this one</span>;
  }

  if (state === 'connect') {
    return (
      <Link to="/marketing?view=plan" className={`btn${sm}`}>
        {linkedin.data?.me?.expired ? 'Reconnect LinkedIn' : 'Connect LinkedIn to post'}
      </Link>
    );
  }

  return (
    <span className="post-to">
      {armed ? (
        <>
          <button type="button" className={`btn btn-primary${sm}`} disabled={busy} onClick={publish}>
            {busy ? 'Posting…' : 'Post it now'}
          </button>
          <button type="button" className={`btn btn-quiet${sm}`} disabled={busy} onClick={() => setArmed(false)}>
            Cancel
          </button>
        </>
      ) : (
        <button type="button" className={`btn btn-primary${sm}`} onClick={() => setArmed(true)}>
          Post to LinkedIn
        </button>
      )}
      {error && <span className="post-to-error" role="alert">{error}</span>}
    </span>
  );
}
