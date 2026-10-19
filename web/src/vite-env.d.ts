/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_AUTH0_DOMAIN: string
  readonly VITE_AUTH0_CLIENT_ID: string
  readonly VITE_AUTH0_AUDIENCE: string
  /** "true" shows the lender and insurer collateral screens, paused by default (2026-10-19). */
  readonly VITE_COLLATERAL_ENABLED?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
