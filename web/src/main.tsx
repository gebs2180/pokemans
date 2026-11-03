import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import '@fontsource-variable/bricolage-grotesque'
import '@fontsource-variable/figtree'
import './index.css'
import App from './App.tsx'
import { AuthProvider } from './AuthProvider.tsx'

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
        <App />
      </AuthProvider>
    </BrowserRouter>
  </StrictMode>,
)
