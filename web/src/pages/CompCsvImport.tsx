import { useState, type ChangeEvent } from 'react'
import { ApiError, useApi } from '../api'
import { compsFromCsv, type CsvRow } from '../compsCsv'

/** Admin: many sold comps at once from a CSV, each saved exactly as one entered by hand would be. */
export function CompCsvImport({ base, number, onAdded }: { base: string; number: string | null; onAdded: () => void }) {
  const api = useApi()
  const [rows, setRows] = useState<CsvRow[] | null>(null)
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState<{ saved: number; duplicates: number; failed: string[] } | null>(null)
  const ready = rows?.filter((r) => r.comp) ?? []
  const skipped = rows?.filter((r) => r.skipped) ?? []

  async function read(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    e.target.value = ''
    setResult(null)
    if (!file) return
    setRows(compsFromCsv(await file.text(), number, new Date().toLocaleDateString('en-CA')))
  }

  async function importAll() {
    setBusy(true)
    let saved = 0
    let duplicates = 0
    const failed: string[] = []
    for (const r of ready) {
      try {
        await api('POST', `${base}/comps`, r.comp)
        saved++
      } catch (e) {
        // A listing URL already entered is the same sale: the server keys sales by URL.
        if (e instanceof ApiError && e.status === 409) duplicates++
        else failed.push(`Line ${r.line}: ${e instanceof Error ? e.message : String(e)}`)
      }
    }
    setBusy(false)
    setRows(null)
    setResult({ saved, duplicates, failed })
    onAdded()
  }

  return (
    <details>
      <summary>Import sold comps from a CSV</summary>
      <p className="muted small">
        Columns: card, set, number, grader, grade, price, sold_on (YYYY-MM-DD), listing_type, marketplace, url. Sold listings only.
        Rows for another card number are left out.
      </p>
      <input type="file" accept=".csv,text/csv" onChange={read} aria-label="Comps CSV file" />
      {rows && (
        <>
          <p>
            {ready.length} sold comp{ready.length === 1 ? '' : 's'} to import
            {skipped.length > 0 && `, ${skipped.length} left out`}.
          </p>
          {skipped.length > 0 && (
            <ul className="muted small">
              {skipped.map((r) => (
                <li key={r.line}>
                  Line {r.line} ({r.label}): {r.skipped}
                </li>
              ))}
            </ul>
          )}
          <button onClick={importAll} disabled={busy || ready.length === 0}>
            {busy ? 'Importing…' : `Import ${ready.length} sold comp${ready.length === 1 ? '' : 's'}`}
          </button>
        </>
      )}
      {result && (
        <p className={result.failed.length ? 'warn' : 'ok'} role="status">
          Saved {result.saved}
          {result.duplicates > 0 && `, ${result.duplicates} already recorded`}. Refresh prices to use them.
        </p>
      )}
      {result?.failed.map((f) => (
        <p key={f} className="error small">
          {f}
        </p>
      ))}
    </details>
  )
}
