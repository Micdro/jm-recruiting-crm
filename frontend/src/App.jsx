import './App.css';

function App() {
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
              <span className="badge">Backend ready</span>
            </div>
            <p>
              Track target accounts, client prospects, company status, and notes.
            </p>
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