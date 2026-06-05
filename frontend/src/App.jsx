import { useEffect, useState } from 'react';
import './App.css';
import { createCompany, getCompanies } from './api';

const emptyCompanyForm = {
  name: '',
  website: '',
  location: '',
  companySize: '',
  status: '',
  linkedinProfile: '',
  notes: '',
};

function App() {
  const [companies, setCompanies] = useState([]);
  const [isLoadingCompanies, setIsLoadingCompanies] = useState(true);
  const [companyError, setCompanyError] = useState('');
  const [companyForm, setCompanyForm] = useState(emptyCompanyForm);
  const [isSavingCompany, setIsSavingCompany] = useState(false);

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

    loadCompanies();
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
                  type="url"
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
              <span className="badge">Backend ready</span>
            </div>
            <p>
              Manage hiring leaders, recruiters, stakeholders, follow-ups, and relationship status.
            </p>
          </article>
        </section>
      </main>
    </div>
  );
}

export default App;