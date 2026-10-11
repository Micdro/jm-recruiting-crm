import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In development the React app runs on 5173 and forwards /api to Spring Boot on 8080,
// so the browser sees one origin and the session and CSRF cookies just work.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
});
