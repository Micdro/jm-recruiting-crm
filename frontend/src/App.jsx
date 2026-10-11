import { Suspense, lazy } from 'react';
import { Navigate, Route, Routes, useLocation } from 'react-router';
import Layout from './components/Layout.jsx';
import { Loading } from './components/Bits.jsx';
import { useAuth } from './hooks.js';
import LoginPage from './pages/LoginPage.jsx';
import TodayPage from './pages/TodayPage.jsx';
import ContactsPage from './pages/ContactsPage.jsx';
import ContactDetailPage from './pages/ContactDetailPage.jsx';
import CompaniesPage from './pages/CompaniesPage.jsx';
import CompanyDetailPage from './pages/CompanyDetailPage.jsx';
import SearchesPage from './pages/SearchesPage.jsx';
import SearchDetailPage from './pages/SearchDetailPage.jsx';
import CandidatesPage from './pages/CandidatesPage.jsx';
import CandidateDetailPage from './pages/CandidateDetailPage.jsx';
import PlacementsPage from './pages/PlacementsPage.jsx';
// The CSV reader is only needed on the import page, so it loads when that page opens.
const ImportPage = lazy(() => import('./pages/ImportPage.jsx'));
import TeamPage from './pages/TeamPage.jsx';
import ThisComputerPage from './pages/ThisComputerPage.jsx';
import NotFoundPage from './pages/NotFoundPage.jsx';

function RequireAuth({ children }) {
  const { user, ready } = useAuth();
  const location = useLocation();

  if (!ready) {
    return (
      <div className="boot">
        <Loading label="Opening the CRM" />
      </div>
    );
  }
  if (!user) {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  }
  return children;
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        element={
          <RequireAuth>
            <Layout />
          </RequireAuth>
        }
      >
        <Route index element={<TodayPage />} />
        <Route path="contacts" element={<ContactsPage />} />
        <Route path="contacts/:id" element={<ContactDetailPage />} />
        <Route path="companies" element={<CompaniesPage />} />
        <Route path="companies/:id" element={<CompanyDetailPage />} />
        <Route path="searches" element={<SearchesPage />} />
        <Route path="searches/:id" element={<SearchDetailPage />} />
        <Route path="candidates" element={<CandidatesPage />} />
        <Route path="candidates/:id" element={<CandidateDetailPage />} />
        <Route path="placements" element={<PlacementsPage />} />
        <Route
          path="import"
          element={
            <Suspense fallback={<Loading label="Opening import" />}>
              <ImportPage />
            </Suspense>
          }
        />
        <Route path="team" element={<TeamPage />} />
        <Route path="computer" element={<ThisComputerPage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}
