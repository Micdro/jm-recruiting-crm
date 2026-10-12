import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { api, errorMessage } from '../api.js';
import { ConfirmButton, Details, DueTag, ErrorNote, ExternalLink, Loading, Pill, TouchPips } from '../components/Bits.jsx';
import LogActivityForm from '../components/LogActivityForm.jsx';
import MessageComposer from '../components/MessageComposer.jsx';
import Modal from '../components/Modal.jsx';
import Timeline from '../components/Timeline.jsx';
import ContactForm from '../forms/ContactForm.jsx';
import { ACTION_TYPE, ACTIVITY_TYPE, BUYING_ROLE, MAX_TOUCHES, OUTREACH_STATUS } from '../labels.js';
import { formatDate, href, todayIso } from '../format.js';
import { useLoader, useTitle, useToast } from '../hooks.js';

export default function ContactDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const toast = useToast();
  const contact = useLoader(() => api.contacts.get(id), [id], `contact:${id}`);
  const history = useLoader(() => api.activities.list({ contactId: id }), [id], `contact:${id}:history`);
  const [editing, setEditing] = useState(false);
  const [writing, setWriting] = useState(false);
  const [error, setError] = useState('');

  const record = contact.data;
  useTitle(record?.name ?? 'Contact');

  if (contact.error) {
    return (
      <>
        <Link to="/contacts" className="back">
          Contacts
        </Link>
        <ErrorNote>{errorMessage(contact.error)}</ErrorNote>
      </>
    );
  }
  if (!record) return <Loading label="Loading contact" />;

  const refresh = (updated) => {
    if (updated) contact.setData(updated);
    history.reload();
  };

  const cold = record.status === 'NEW' || record.status === 'IN_SEQUENCE';
  const canRestart = record.status !== 'DO_NOT_CONTACT' && (record.touchCount > 0 || !cold);

  return (
    <>
      <Link to="/contacts" className="back">
        Contacts
      </Link>
      <div className="page-header">
        <div className="page-heading">
          <h1>{record.name}</h1>
          <div className="page-sub">
            {record.title ? `${record.title} at ` : ''}
            <Link to={`/companies/${record.companyId}`}>{record.companyName}</Link>
          </div>
        </div>
        <div className="page-actions">
          <button type="button" className="btn" onClick={() => setEditing(true)}>
            Edit
          </button>
          <ConfirmButton
            onConfirm={async () => {
              try {
                await api.contacts.remove(record.id);
                toast(`Deleted ${record.name}`);
                navigate('/contacts');
              } catch (err) {
                setError(errorMessage(err));
              }
            }}
          />
        </div>
      </div>
      <ErrorNote>{error}</ErrorNote>

      <div className="detail-grid">
        <div className="detail-main">
          <section className="panel" aria-labelledby="outreach-heading">
            <div className="panel-head">
              <h2 id="outreach-heading">Outreach</h2>
              {record.status !== 'DO_NOT_CONTACT' && (
                <button type="button" className="btn btn-sm btn-primary" onClick={() => setWriting(true)}>
                  Write message
                </button>
              )}
              {canRestart && (
                <button
                  type="button"
                  className="btn btn-sm"
                  onClick={async () => {
                    try {
                      const updated = await api.contacts.restartSequence(record.id);
                      toast('Sequence restarted. First touch is due today.');
                      refresh(updated);
                    } catch (err) {
                      setError(errorMessage(err));
                    }
                  }}
                >
                  Restart sequence
                </button>
              )}
            </div>
            <dl className="outreach-facts">
              <div>
                <dt>Status</dt>
                <dd>
                  <Pill value={record.status} labels={OUTREACH_STATUS} />
                </dd>
              </div>
              <div>
                <dt>Sequence</dt>
                <dd className="facts-pips">
                  <TouchPips count={record.touchCount} />
                  <span>
                    {record.touchCount} of {MAX_TOUCHES}
                  </span>
                </dd>
              </div>
              <div>
                <dt>Next action</dt>
                <dd>
                  <DueTag date={record.nextActionDate} today={todayIso()} />
                  {record.nextActionType && record.nextActionDate && <span className="cell-sub">{ACTION_TYPE[record.nextActionType]}</span>}
                </dd>
              </div>
              <div>
                <dt>Last contacted</dt>
                <dd>{record.lastContactedDate ? formatDate(record.lastContactedDate) : 'Never'}</dd>
              </div>
            </dl>
            <LogActivityForm kind="contact" record={record} onLogged={refresh} />
          </section>

          <section className="panel">
            <div className="panel-head">
              <h2>Details</h2>
            </div>
            <Details
              items={[
                { label: 'Email', value: record.email && <a href={`mailto:${record.email}`}>{record.email}</a> },
                { label: 'Phone', value: record.phone && <a href={`tel:${record.phone}`}>{record.phone}</a> },
                { label: 'LinkedIn', value: record.linkedinUrl && <ExternalLink href={href(record.linkedinUrl)}>Profile</ExternalLink> },
                { label: 'Buying role', value: BUYING_ROLE[record.buyingRole] },
                { label: 'Owner', value: record.ownerName ?? 'Unassigned' },
              ]}
            />
            {record.notes && <p className="notes">{record.notes}</p>}
          </section>
        </div>

        <section className="panel detail-side" aria-labelledby="history-heading">
          <div className="panel-head">
            <h2 id="history-heading">History</h2>
          </div>
          <Timeline activities={history.data} loading={history.loading} />
        </section>
      </div>

      {writing && (
        <Modal title={`Message for ${record.name}`} onClose={() => setWriting(false)} wide>
          <MessageComposer
            contact={record}
            onLogged={(updated, type) => {
              setWriting(false);
              toast(`${ACTIVITY_TYPE[type]} logged.`);
              refresh(updated);
            }}
          />
        </Modal>
      )}

      {editing && (
        <Modal title={`Edit ${record.name}`} onClose={() => setEditing(false)}>
          <ContactForm
            contact={record}
            onCancel={() => setEditing(false)}
            onSaved={(saved) => {
              setEditing(false);
              toast('Contact saved');
              refresh(saved);
            }}
          />
        </Modal>
      )}
    </>
  );
}
