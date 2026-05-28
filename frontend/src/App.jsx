import { useEffect, useState } from 'react';
import './App.css';
import { getCompanies } from './api';

function App() {
  const [companies, setCompanies] = useState([]);
  const [isLoadingCompanies, setIsLoadingCompanies] = useState(true);
  const [companyError, setCompanyError] = useState('');

  useEffect(() => {
    async function loadCompanies() {
      try {
        const data = await getCompanies();
        setCompanies(data);
      } catch (error) {
        setCompanyError('Unable to load companies. Make sure the backend is running.');
      } finally {
        setIsLoadingCompanies(false);
      }
    }

    loadCompanies();
  }, []);

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

          <button type="button" className="primary-button">
            Add Company
          </button>
        </section>

        <section className="content-grid">
          <article id="companies" className="card">
            <div className="card-header">
              <h3>Companies</h3>
              <span className="badge">Live API</span>
            </div>

            {isLoadingCompanies && <p>Loading companies...</p>}

            {companyError && <p className="error-message">{companyError}</p>}

            {!isLoadingCompanies && !companyError && companies.length === 0 && (
              <p>No companies found yet.</p>
            )}

            {!isLoadingCompanies && !companyError && companies.length > 0 && (
              <ul className="company-list">
                {companies.map((company) => (
                  <li key={company.id} className="company-list-item">
                    <strong>{company.name}</strong>
                    <span>{company.status || 'No status set'}</span>
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