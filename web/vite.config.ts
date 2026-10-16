import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // The Spring Boot app serves the API on 8080 in local development.
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
