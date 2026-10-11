import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router';
// The typeface ships with the app, so no request ever goes to a font service.
import '@fontsource/ibm-plex-sans/latin-400.css';
import '@fontsource/ibm-plex-sans/latin-400-italic.css';
import '@fontsource/ibm-plex-sans/latin-500.css';
import '@fontsource/ibm-plex-sans/latin-600.css';
import './styles.css';
import App from './App.jsx';
import AuthProvider from './components/AuthProvider.jsx';
import SetupGate from './components/SetupGate.jsx';
import ToastProvider from './components/ToastProvider.jsx';

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <BrowserRouter>
      <ToastProvider>
        <SetupGate>
          <AuthProvider>
            <App />
          </AuthProvider>
        </SetupGate>
      </ToastProvider>
    </BrowserRouter>
  </StrictMode>,
);
