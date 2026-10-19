import { useState } from 'react'
import { useApi, type DownloadLink, type ReportView } from '../api'
import { safeHref } from '../safeHref'

const TYPE_LABEL = { LENDER: 'Lender collateral report', INSURER: 'Insurer replacement value report' } as const

/**
 * Issued report PDFs with download buttons. The API hands back a short-lived signed link, opened in a
 * new tab, so the PDF is fetched without the API token.
 */
export function ReportsList({ reports, downloadPath }: { reports: ReportView[]; downloadPath: (id: string) => string }) {
  const api = useApi()
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState<string | null>(null)

  async function download(id: string) {
    setError(null)
    setBusy(id)
    // Opened before the await so pop-up blockers treat it as the click's own window.
    const tab = window.open('', '_blank')
    try {
      const link = await api<DownloadLink>('GET', downloadPath(id))
      const href = safeHref(link.url)
      if (!href) throw new Error('The download link was not a web address')
      if (tab) tab.location.assign(href)
      else window.location.assign(href)
    } catch (e) {
      tab?.close()
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(null)
    }
  }

  if (reports.length === 0) return <p className="muted">No reports issued yet.</p>
  return (
    <>
      <ul className="list">
        {reports.map((r) => (
          <li key={r.id}>
            <button className="link" onClick={() => download(r.id)} disabled={busy === r.id}>
              {TYPE_LABEL[r.type]}
            </button>{' '}
            <span className="muted">
              issued {new Date(r.issuedAt).toLocaleString()}
              {r.lenderAlpha != null && `, at the lender's alpha of ${Math.round(r.lenderAlpha * 1000) / 10}%`}
              {' · '}SHA-256 <code title={r.sha256}>{r.sha256.slice(0, 12)}…</code>
            </span>
          </li>
        ))}
      </ul>
      {error && <p className="error" role="alert">{error}</p>}
    </>
  )
}
