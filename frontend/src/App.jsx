import { useEffect, useState } from 'react';
import './App.css';
import { createCompany, createContact, getCompanies, getContacts } from './api';

const emptyCompanyForm = {
  name: '',
  website: '',
  location: '',
  companySize: '',
  status: '',
  linkedinProfile: '',
  notes: '',
};

const emptyContactForm = {
  name: '',
  title: '',
  email: '',
  linkedinUrl: '',
  relationshipStatus: '',
  lastContactedDate: '',
  nextFollowUpDate: '',
  notes: '',
  companyId: '',
};

function emptyStringToNull(value) {
  return value.trim() === '' ? null : value;
}

function App() {
  const [companies, setCompanies] = useState([]);
  const [isLoadingCompanies, setIsLoadingCompanies] = useState(true);
  const [companyError, setCompanyError] = useState('');
  const [companyForm, setCompanyForm] = useState(emptyCompanyForm);
  const [isSavingCompany, setIsSavingCompany] = useState(false);

  const [contacts, setContacts] = useState([]);
  const [isLoadingContacts, setIsLoadingContacts] = useState(true);
  const [contactError, setContactError] = useState('');
  const [contactForm, setContactForm] = useState(emptyContactForm);
  const [isSavingContact, setIsSavingContact] = useState(false);

  useEffect(() => {
    async function loadCompanies() {
      try {
        const data = await getCompanies();
        setCompanies(data);
      } catch {
        setCompanyError('Unable to load companies. Make sure the backend is running.');
      } finally {
        setIsLoadingCompanies(false);
      }
    }

    async function loadContacts() {
      try {
        const data = await getContacts();
        setContacts(data);
      } catch {
        setContactError('Unable to load contacts. Make sure the backend is running.');
      } finally {
        setIsLoadingContacts(false);
      }
    }

    loadCompanies();
    loadContacts();
  }, []);

  function handleCompanyFormChange(event) {
    const { name, value } = event.target;

    setCompanyForm((currentForm) => ({
      ...currentForm,
      [name]: value,
    }));
  }

  async function handleCreateCompany(event) {
    event.preventDefault();

    setCompanyError('');
    setIsSavingCompany(true);

    try {
      const createdCompany = await createCompany(companyForm);

      setCompanies((currentCompanies) => [
        ...currentCompanies,
        createdCompany,
      ]);

      setCompanyForm(emptyCompanyForm);
    } catch {
      setCompanyError('Unable to create company. Check the form and make sure the backend is running.');
    } finally {
      setIsSavingCompany(false);
    }
  }

  function handleContactFormChange(event) {
    const { name, value } = event.target;

    setContactForm((currentForm) => ({
      ...currentForm,
      [name]: value,
    }));
  }

  async function handleCreateContact(event) {
    event.preventDefault();

    setContactError('');
    setIsSavingContact(true);

    const contactPayload = {
      name: contactForm.name.trim(),
      title: emptyStringToNull(contactForm.title),
      email: emptyStringToNull(contactForm.email),
      linkedinUrl: emptyStringToNull(contactForm.linkedinUrl),
      relationshipStatus: emptyStringToNull(contactForm.relationshipStatus),
      lastContactedDate: contactForm.lastContactedDate || null,
      nextFollowUpDate: contactForm.nextFollowUpDate || null,
      notes: emptyStringToNull(contactForm.notes),
      companyId: Number(contactForm.companyId),
    };

    try {
      const createdContact = await createContact(contactPayload);

      setContacts((currentContacts) => [
        ...currentContacts,
        createdContact,
      ]);

      setContactForm(emptyContactForm);
    } catch {
      setContactError('Unable to create contact. Check the form and make sure the backend is running.');
    } finally {
      setIsSavingContact(false);
    }
  }

  return (
      <div className="app">
        <aside className="sidebar">
          <div className="brand">
            <h1>JM Recruiting CRM</h1>
            <p>AI talent search operating system</p>
          </div>

          <nav className="nav">
            <a href="#companies">Companies</a>
            <a href="#contacts">Contacts</a>
          </nav>
        </aside>

        <main className="main-content">
          <section className="page-header">
            <div>
              <p className="eyebrow">Dashboard</p>
              <h2>Recruiting pipeline workspace</h2>
            </div>

            <a href="#company-form" className="primary-button">
              Add Company
            </a>
          </section>

          <section className="content-grid">
            <article id="companies" className="card">
              <div className="card-header">
                <h3>Companies</h3>
                <span className="badge">Live API</span>
              </div>

              <form id="company-form" className="company-form" onSubmit={handleCreateCompany}>
                <label>
                  Company name
                  <input
                      type="text"
                      name="name"
                      value={companyForm.name}
                      onChange={handleCompanyFormChange}
                      placeholder="Jane Michael LLC"
                      required
                  />
                </label>

                <label>
                  Website
                  <input
                      type="url"
                      name="website"
                      value={companyForm.website}
                      onChange={handleCompanyFormChange}
                      placeholder="https://example.com"
                  />
                </label>

                <label>
                  Location
                  <input
                      type="text"
                      name="location"
                      value={companyForm.location}
                      onChange={handleCompanyFormChange}
                      placeholder="New York, NY"
                  />
                </label>

                <label>
                  Company size
                  <input
                      type="text"
                      name="companySize"
                      value={companyForm.companySize}
                      onChange={handleCompanyFormChange}
                      placeholder="51-200"
                  />
                </label>

                <label>
                  Status
                  <input
                      type="text"
                      name="status"
                      value={companyForm.status}
                      onChange={handleCompanyFormChange}
                      placeholder="Prospect"
                  />
                </label>

                <label>
                  LinkedIn profile
                  <input
                      type="text"
                      name="linkedinProfile"
                      value={companyForm.linkedinProfile}
                      onChange={handleCompanyFormChange}
                      placeholder="https://linkedin.com/company/example"
                  />
                </label>

                <label>
                  Notes
                  <textarea
                      name="notes"
                      value={companyForm.notes}
                      onChange={handleCompanyFormChange}
                      placeholder="Target account notes"
                      rows="4"
                  />
                </label>

                <button type="submit" className="secondary-button" disabled={isSavingCompany}>
                  {isSavingCompany ? 'Saving...' : 'Save Company'}
                </button>
              </form>

              {isLoadingCompanies && <p>Loading companies...</p>}

              {companyError && <p className="error-message">{companyError}</p>}

              {!isLoadingCompanies && !companyError && companies.length === 0 && (
                  <p>No companies found yet.</p>
              )}

              {!isLoadingCompanies && companies.length > 0 && (
                  <ul className="company-list">
                    {companies.map((company) => (
                        <li key={company.id} className="company-list-item">
                          <strong>{company.name}</strong>
                          <span>{company.status || 'No status set'}</span>
                          {company.location && <span>{company.location}</span>}
                          {company.companySize && <span>{company.companySize}</span>}
                          {company.website && <span>{company.website}</span>}
                        </li>
                    ))}
                  </ul>
              )}
            </article>

            <article id="contacts" className="card">
              <div className="card-header">
                <h3>Contacts</h3>
                <span className="badge">Live API</span>
              </div>

              <form className="contact-form" onSubmit={handleCreateContact}>
                <label>
                  Contact name
                  <input
                      type="text"
                      name="name"
                      value={contactForm.name}
                      onChange={handleContactFormChange}
                      placeholder="Jane Doe"
                      required
                  />
                </label>

                <label>
                  Company
                  <select
                      name="companyId"
                      value={contactForm.companyId}
                      onChange={handleContactFormChange}
                      required
                  >
                    <option value="">Select a company</option>
                    {companies.map((company) => (
                        <option key={company.id} value={company.id}>
                          {company.name}
                        </option>
                    ))}
                  </select>
                </label>

                <label>
                  Title
                  <input
                      type="text"
                      name="title"
                      value={contactForm.title}
                      onChange={handleContactFormChange}
                      placeholder="VP Engineering"
                  />
                </label>

                <label>
                  Email
                  <input
                      type="email"
                      name="email"
                      value={contactForm.email}
                      onChange={handleContactFormChange}
                      placeholder="jane@example.com"
                  />
                </label>

                <label>
                  LinkedIn profile
                  <input
                      type="url"
                      name="linkedinUrl"
                      value={contactForm.linkedinUrl}
                      onChange={handleContactFormChange}
                      placeholder="https://linkedin.com/in/janedoe"
                  />
                </label>

                <label>
                  Relationship status
                  <input
                      type="text"
                      name="relationshipStatus"
                      value={contactForm.relationshipStatus}
                      onChange={handleContactFormChange}
                      placeholder="Warm"
                  />
                </label>

                <label>
                  Last contacted date
                  <input
                      type="date"
                      name="lastContactedDate"
                      value={contactForm.lastContactedDate}
                      onChange={handleContactFormChange}
                  />
                </label>

                <label>
                  Next follow-up date
                  <input
                      type="date"
                      name="nextFollowUpDate"
                      value={contactForm.nextFollowUpDate}
                      onChange={handleContactFormChange}
                  />
                </label>

                <label>
                  Notes
                  <textarea
                      name="notes"
                      value={contactForm.notes}
                      onChange={handleContactFormChange}
                      placeholder="Relationship context or follow-up notes"
                      rows="4"
                  />
                </label>

                <button
                    type="submit"
                    className="secondary-button"
                    disabled={isSavingContact || companies.length === 0}
                >
                  {isSavingContact ? 'Saving...' : 'Save Contact'}
                </button>
              </form>

              {isLoadingContacts && <p>Loading contacts...</p>}

              {contactError && <p className="error-message">{contactError}</p>}

              {!isLoadingContacts && !contactError && contacts.length === 0 && (
                  <p>No contacts found yet.</p>
              )}

              {!isLoadingContacts && contacts.length > 0 && (
                  <ul className="contact-list">
                    {contacts.map((contact) => (
                        <li key={contact.id} className="contact-list-item">
                          <strong>{contact.name}</strong>
                          <span>{contact.title || 'No title set'}</span>
                          <span>{contact.companyName || 'No company set'}</span>
                          {contact.relationshipStatus && <span>Status: {contact.relationshipStatus}</span>}
                          {contact.nextFollowUpDate && <span>Next follow-up: {contact.nextFollowUpDate}</span>}
                        </li>
                    ))}
                  </ul>
              )}
            </article>
          </section>
        </main>
      </div>
  );
}

export default App;