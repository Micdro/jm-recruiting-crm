import { useState } from 'react';
import { Link } from 'react-router';
import { api, errorMessage } from '../api.js';
import { formatDate, href } from '../format.js';
import { useAuth, useToast, useUsers } from '../hooks.js';
import { POST_ACCOUNT, POST_STATUS, POST_THEME, options } from '../labels.js';
import {
  POST_LIMIT,
  TITLE_LIMIT,
  applyTemplate,
  dollars,
  fold,
  postingState,
  useLinkedIn,
  usePostTemplates,
} from '../marketing.js';
import { ConfirmButton, ErrorNote, ExternalLink } from './Bits.jsx';
import { OwnerField, SelectField, TextArea, TextField } from './Fields.jsx';
import { SaveAsTemplate } from './PostTemplates.jsx';

const asText = (value) => (value === null || value === undefined ? '' : String(value));
const asNumber = (value) => (value === '' ? null : Number(value));

const PICTURE_TYPES = 'image/jpeg,image/png,image/gif,image/webp';
const PICTURE_LIMIT = 8 * 1024 * 1024;

/**
 * Adds or changes a post: fills it in from a template, drafts it with AI, gives it a picture
 * (uploaded or made by AI), posts it to LinkedIn or records it as posted, and keeps its results.
 * `template`, if given, is the template a new post starts from.
 */
export default function PostEditor({ post, template = null, onClose, onChanged }) {
  const toast = useToast();
  const { user } = useAuth();
  const users = useUsers();
  const templates = usePostTemplates();
  const [saved, setSaved] = useState(post.id ? post : null);
  const [initial] = useState(() => ({
    title: post.title ?? '',
    body: post.body ?? '',
    notes: post.notes ?? '',
    theme: post.theme ?? 'OTHER',
    account: post.account ?? 'PERSONAL',
    status: post.status ?? 'IDEA',
    plannedFor: post.plannedFor ?? '',
    // A new post is yours unless you pick someone else.
    ownerId: asText(post.id ? post.ownerId : user?.id),
  }));
  const [form, setForm] = useState(() => (template ? applyTemplate(initial, template) : initial));
  // The template last picked, and the form as it was before, for Undo.
  const [picked, setPicked] = useState(() => (template ? { id: template.id, name: template.name, before: initial } : null));
  const [savingTemplate, setSavingTemplate] = useState(false);
  const [direction, setDirection] = useState('');
  const [drafted, setDrafted] = useState(null);
  const [postUrl, setPostUrl] = useState(post.postUrl ?? '');
  const [results, setResults] = useState(() => ({
    impressions: asText(post.impressions),
    reactions: asText(post.reactions),
    comments: asText(post.comments),
    conversations: asText(post.conversations),
  }));
  const [pictureDirection, setPictureDirection] = useState('');
  const [pictureCost, setPictureCost] = useState(null);
  const [armed, setArmed] = useState(false);
  const linkedin = useLinkedIn();
  const [busy, setBusy] = useState(null);
  const [error, setError] = useState('');
  const set = (field) => (value) => setForm((current) => ({ ...current, [field]: value }));
  const setResult = (field) => (value) => setResults((current) => ({ ...current, [field]: value }));
  const isPosted = saved?.status === 'POSTED';
  const picture = saved?.picture ?? null;

  function payload() {
    return {
      title: form.title,
      body: form.body.trim() ? form.body : null,
      notes: form.notes.trim() ? form.notes : null,
      theme: form.theme,
      account: form.account,
      // Writing the post moves an idea on to a draft.
      status: form.status === 'IDEA' && form.body.trim() ? 'DRAFT' : form.status,
      plannedFor: form.plannedFor || null,
      ownerId: form.ownerId ? Number(form.ownerId) : null,
    };
  }

  /** Saves what's in the form and hands back the saved post. */
  async function persist() {
    const record = saved ? await api.marketing.posts.update(saved.id, payload()) : await api.marketing.posts.create(payload());
    setSaved(record);
    setForm((current) => ({ ...current, status: record.status, ownerId: asText(record.ownerId) }));
    onChanged();
    return record;
  }

  async function run(name, work) {
    setBusy(name);
    setError('');
    try {
      await work();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(null);
    }
  }

  function pickTemplate(id) {
    const chosen = (templates.data ?? []).find((option) => String(option.id) === id);
    if (!chosen) return;
    setError('');
    setPicked({ id: chosen.id, name: chosen.name, before: picked?.before ?? form });
    setForm((current) => applyTemplate(picked ? picked.before : current, chosen));
  }

  function undoTemplate() {
    setForm(picked.before);
    setPicked(null);
  }

  function submit(event) {
    event.preventDefault();
    run('save', async () => {
      const record = await persist();
      toast(post.id ? 'Saved' : `Added ${record.title}`);
      onClose();
    });
  }

  function draftWithAi() {
    if (!form.title.trim()) {
      setError('Give the post a title first. The draft is written from it and the notes.');
      return;
    }
    run('draft', async () => {
      const record = await persist();
      const draft = await api.marketing.draft(record.id, { direction: direction.trim() || undefined });
      setForm((current) => ({ ...current, body: draft.body }));
      setDrafted(draft);
    });
  }

  /** Pictures and posting need the post saved first; this asks for the title it needs. */
  function needsTitle(why) {
    if (form.title.trim()) return false;
    setError(`Give the post a title first. ${why}`);
    return true;
  }

  function uploadPicture(file) {
    if (!file) return;
    if (file.size > PICTURE_LIMIT) {
      setError('Pictures can be up to 8 MB.');
      return;
    }
    if (needsTitle('The picture is saved with the post.')) return;
    run('picture', async () => {
      const record = await persist();
      setSaved(await api.marketing.uploadPicture(record.id, file));
      setPictureCost(null);
      onChanged();
    });
  }

  function makePicture() {
    if (needsTitle('The picture is made from it.')) return;
    run('picture', async () => {
      const record = await persist();
      const made = await api.marketing.generatePicture(record.id, { direction: pictureDirection.trim() || undefined });
      setSaved(made.post);
      setPictureCost(made.costCents);
      onChanged();
    });
  }

  function removePicture() {
    run('picture', async () => {
      setSaved(await api.marketing.removePicture(saved.id));
      setPictureCost(null);
      onChanged();
    });
  }

  function publish() {
    run('publish', async () => {
      const record = await persist();
      const updated = await api.marketing.publish(record.id);
      setSaved(updated);
      setForm((current) => ({ ...current, status: updated.status }));
      setArmed(false);
      onChanged();
      toast('Posted to LinkedIn');
    });
  }

  async function copy() {
    try {
      await navigator.clipboard.writeText(form.body);
      toast('Copied. Paste it into LinkedIn.');
    } catch {
      setError('Couldn’t copy. Select the post and copy it yourself.');
    }
  }

  function markPosted() {
    run('posted', async () => {
      const record = await persist();
      const updated = await api.marketing.posted(record.id, { postUrl: postUrl.trim() || null });
      setSaved(updated);
      setForm((current) => ({ ...current, status: updated.status }));
      onChanged();
      toast('Marked as posted. Add its results in a few days.');
    });
  }

  function saveResults() {
    run('results', async () => {
      const updated = await api.marketing.results(saved.id, {
        impressions: asNumber(results.impressions),
        reactions: asNumber(results.reactions),
        comments: asNumber(results.comments),
        conversations: asNumber(results.conversations),
      });
      setSaved(updated);
      onChanged();
      toast('Results saved');
    });
  }

  // What posting would do with the post as it is in the form now.
  const postState = postingState(
    {
      status: saved?.status ?? form.status,
      body: form.body,
      account: form.account,
      ownerId: form.ownerId ? Number(form.ownerId) : null,
    },
    user,
    linkedin.data,
  );
  const ownerFirstName = users.find((person) => String(person.id) === form.ownerId)?.displayName.split(' ')[0] ?? 'Its owner';
  const statuses = isPosted ? POST_STATUS : { IDEA: POST_STATUS.IDEA, DRAFT: POST_STATUS.DRAFT, READY: POST_STATUS.READY };
  const tooLong = form.body.length > POST_LIMIT;
  const opening = fold(form.body.trim());

  const templateList = templates.data ?? [];

  return (
    <form className="form post-form" onSubmit={submit}>
      <div className="post-template-bar">
        {/* A posted post can't change, but it can still be saved as a template for the next one. */}
        {!isPosted && (
          <SelectField
            label="Template"
            value={picked ? String(picked.id) : ''}
            onChange={pickTemplate}
            options={templateList.map((option) => ({ value: String(option.id), label: option.name }))}
            placeholder={templateList.length > 0 ? 'Choose a template' : 'No templates yet'}
            disabled={templateList.length === 0}
            className="post-template-pick"
          />
        )}
        <p className="field-hint post-template-note">
          {picked && (
            <>
              Filled in from {picked.name}.{' '}
              <button type="button" className="link-button" onClick={undoTemplate}>
                Undo
              </button>
            </>
          )}
          {!savingTemplate && (
            <>
              {picked && ' · '}
              <button type="button" className="link-button" onClick={() => setSavingTemplate(true)}>
                Save as template
              </button>
            </>
          )}
        </p>
      </div>
      {savingTemplate && (
        <SaveAsTemplate
          form={form}
          templates={templateList}
          defaultName={picked?.name ?? form.title}
          onCancel={() => setSavingTemplate(false)}
          onSaved={(record, updated) => {
            setSavingTemplate(false);
            setPicked(null);
            templates.reload();
            toast(updated ? `Updated the template ${record.name}` : `Saved the template ${record.name}`);
          }}
        />
      )}

      <TextField
        label="Title"
        value={form.title}
        onChange={set('title')}
        required
        maxLength={TITLE_LIMIT}
        autoFocus={!post.id && !template}
      />

      <div className="form-row">
        <TextField
          label="Day to post"
          type="date"
          value={form.plannedFor}
          onChange={set('plannedFor')}
          hint="Leave it empty to keep this as an idea."
        />
        <SelectField label="Status" value={form.status} onChange={set('status')} options={options(statuses)} required />
      </div>

      <div className="form-row">
        <SelectField label="Theme" value={form.theme} onChange={set('theme')} options={options(POST_THEME)} required />
        <OwnerField users={users} value={form.ownerId} onChange={set('ownerId')} label="Who posts it" />
      </div>

      <fieldset className="field post-account">
        <legend>Goes on</legend>
        <span className="segmented segmented-small">
          {Object.entries(POST_ACCOUNT).map(([value, label]) => (
            <label key={value} className={form.account === value ? 'segment segment-on' : 'segment'}>
              <input type="radio" name="post-account" checked={form.account === value} onChange={() => set('account')(value)} />
              {label}
            </label>
          ))}
        </span>
      </fieldset>

      <TextArea
        label="Notes"
        rows={3}
        value={form.notes}
        onChange={set('notes')}
        maxLength={2000}
        hint="Facts and points to make. AI drafts use only these and the title, and never name a client or candidate."
      />

      <div className="composer-draft">
        <TextField
          label="Direction for the draft (optional)"
          value={direction}
          onChange={setDirection}
          maxLength={1000}
          placeholder="For example: shorter, or open with the question"
          className="post-direction"
        />
        <button type="button" className="btn btn-sm" disabled={busy === 'draft'} onClick={draftWithAi}>
          {busy === 'draft' ? 'Drafting…' : drafted || form.body.trim() ? 'Redraft with AI' : 'Draft with AI'}
        </button>
      </div>
      {drafted && (
        <p className="field-hint">
          Drafted by {drafted.provider} for {dollars(drafted.costCents)}. Read it over, then save. Drafting saves the title and
          notes first.
        </p>
      )}

      <div className="field">
        <label htmlFor="post-body">Post</label>
        <textarea id="post-body" rows={10} value={form.body} onChange={(event) => set('body')(event.target.value)} />
        <p className={`field-hint ${tooLong ? 'composer-warn' : ''}`}>
          {form.body.length.toLocaleString()} of {POST_LIMIT.toLocaleString()} characters
          {form.body.trim() && (
            <>
              {' · '}
              <button type="button" className="link-button" onClick={copy}>
                Copy post
              </button>
            </>
          )}
        </p>
        {opening.shown && (
          <div className="post-fold" aria-label="How it opens on LinkedIn">
            <p className="post-fold-label">How it opens in the feed</p>
            <p className="post-fold-text">
              {opening.shown}
              {opening.cut && <span className="post-fold-more">…see more</span>}
            </p>
            <p className="field-hint">
              {opening.cut
                ? 'LinkedIn shows about this much before “see more”. These lines decide whether people read on.'
                : 'The whole post shows in the feed.'}
            </p>
          </div>
        )}
      </div>

      <div className="post-picture-field">
        <span className="post-picture-label">Picture</span>
        <div className="post-picture">
          <div className="post-picture-frame">
            {picture ? (
              picture.ready ? (
                <img src={picture.url} alt="The post’s picture" />
              ) : (
                <p className="muted">The picture is on its way from another computer.</p>
              )
            ) : (
              <p className="muted">No picture yet</p>
            )}
          </div>
          <div className="post-picture-actions">
            <span className="post-picture-buttons">
              <label htmlFor="post-picture-file" className="btn btn-sm">
                {picture ? 'Upload another' : 'Upload picture'}
              </label>
              <input
                id="post-picture-file"
                type="file"
                accept={PICTURE_TYPES}
                className="visually-hidden"
                onChange={(event) => {
                  uploadPicture(event.target.files?.[0]);
                  event.target.value = '';
                }}
              />
              {picture?.ready && (
                <a className="btn btn-sm btn-quiet" href={picture.url} download={`post-${saved.id}.jpg`}>
                  Save picture
                </a>
              )}
              {picture && (
                <button type="button" className="btn btn-sm btn-quiet" disabled={busy === 'picture'} onClick={removePicture}>
                  Remove picture
                </button>
              )}
            </span>
            <TextField
              label="What the AI picture should show (optional)"
              value={pictureDirection}
              onChange={setPictureDirection}
              maxLength={500}
              placeholder="For example: a bridge at dawn"
            />
            <button type="button" className="btn btn-sm" disabled={busy === 'picture'} onClick={makePicture}>
              {busy === 'picture' ? 'Working…' : picture ? 'Make a new one with AI' : 'Make one with AI'}
            </button>
            {pictureCost !== null && <p className="field-hint">Made by OpenAI for {dollars(pictureCost)}.</p>}
          </div>
        </div>
      </div>

      {!isPosted && postState !== 'unwritten' && postState !== 'loading' && (
        <div className="post-publish">
          {postState === 'ready' &&
            (armed ? (
              <>
                <button type="button" className="btn btn-primary" disabled={busy === 'publish' || tooLong} onClick={publish}>
                  {busy === 'publish' ? 'Posting…' : 'Post it now'}
                </button>
                <button type="button" className="btn btn-quiet" disabled={busy === 'publish'} onClick={() => setArmed(false)}>
                  Cancel
                </button>
                <span className="field-hint">
                  It goes live on your LinkedIn{picture ? ', with the picture' : ''}, and saves your changes first.
                </span>
              </>
            ) : (
              <button type="button" className="btn btn-primary" disabled={tooLong} onClick={() => setArmed(true)}>
                Post to LinkedIn
              </button>
            ))}
          {postState === 'connect' && (
            <span className="field-hint">
              To post from here,{' '}
              <Link to="/marketing?view=plan" onClick={onClose}>
                {linkedin.data?.me?.expired ? 'connect your LinkedIn again' : 'connect your LinkedIn'}
              </Link>
              .
            </span>
          )}
          {postState === 'someone-else' && <span className="field-hint">{ownerFirstName} posts this one from their CRM.</span>}
          {postState === 'page' && (
            <span className="field-hint">
              Company page posts go up by hand: copy the post{picture ? ' and save the picture' : ''}, post it on the page, then
              mark it posted below.
            </span>
          )}
        </div>
      )}

      {saved && !isPosted && (
        <div className="post-step">
          <TextField
            label="LinkedIn link, once it’s up (optional)"
            type="url"
            value={postUrl}
            onChange={setPostUrl}
            maxLength={500}
            placeholder="https://www.linkedin.com/posts/…"
          />
          <button type="button" className="btn" disabled={busy === 'posted'} onClick={markPosted}>
            {busy === 'posted' ? 'Saving…' : 'Mark as posted'}
          </button>
        </div>
      )}

      {isPosted && (
        <fieldset className="post-results">
          <legend>
            Results{' '}
            <span className="muted">
              Posted {formatDate(saved.postedOn)}
              {saved.postUrl && (
                <>
                  {' · '}
                  <ExternalLink href={href(saved.postUrl)}>Open on LinkedIn</ExternalLink>
                </>
              )}
            </span>
          </legend>
          <div className="post-results-grid">
            <TextField label="Impressions" type="number" min="0" value={results.impressions} onChange={setResult('impressions')} />
            <TextField label="Reactions" type="number" min="0" value={results.reactions} onChange={setResult('reactions')} />
            <TextField label="Comments" type="number" min="0" value={results.comments} onChange={setResult('comments')} />
            <TextField
              label="Conversations"
              type="number"
              min="0"
              value={results.conversations}
              onChange={setResult('conversations')}
              hint="DMs or calls it led to"
            />
          </div>
          <div className="form-actions form-actions-start">
            <button type="button" className="btn" disabled={busy === 'results'} onClick={saveResults}>
              {busy === 'results' ? 'Saving…' : 'Save results'}
            </button>
          </div>
        </fieldset>
      )}

      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        {saved && (
          <span className="form-actions-left">
            <ConfirmButton
              className="btn btn-quiet"
              onConfirm={() =>
                run('delete', async () => {
                  await api.marketing.posts.remove(saved.id);
                  onChanged();
                  toast(`Deleted ${saved.title}`);
                  onClose();
                })
              }
            />
          </span>
        )}
        <button type="button" className="btn" onClick={onClose}>
          {saved && !post.id ? 'Close' : 'Cancel'}
        </button>
        <button type="submit" className="btn btn-primary" disabled={busy === 'save' || tooLong}>
          {busy === 'save' ? 'Saving…' : saved ? 'Save' : 'Add post'}
        </button>
      </div>
    </form>
  );
}

