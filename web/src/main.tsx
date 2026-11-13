import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import '@fontsource-variable/manrope'
import './index.css'
import App from './App.tsx'
import { AuthProvider } from './AuthProvider.tsx'
import { PolicyProvider } from './PolicyProvider.tsx'

// A deploy renames the lazy chunks under tabs that are still open; reload once to pick up the new ones.
// The sessionStorage flag stops a loop if the chunk is really gone; without storage we don't reload.
window.addEventListener('vite:preloadError', (event) => {
  try {
    if (sessionStorage.getItem('preload-reloaded')) return
    sessionStorage.setItem('preload-reloaded', '1')
  } catch {
    return
  }
  event.preventDefault()
  window.location.reload()
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <AuthProvider>
        <PolicyProvider>
          <App />
        </PolicyProvider>
      </AuthProvider>
    </BrowserRouter>
  </StrictMode>,
)
