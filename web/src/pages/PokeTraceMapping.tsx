import { useState } from 'react'
import { apiPath, useApi, type PokeTraceCandidate, type PokeTraceMappingView, type PokeTracePull } from '../api'
import { message } from '../format'
import { useLoad } from '../useLoad'

/**
 * Admin: which PokeTrace card this catalog card is. PokeTrace's search doesn't split editions, so every
 * mapping is confirmed here, and one that disagrees with our card needs a reason. Hidden when PokeTrace
 * isn't enabled on the server.
 */
export function PokeTraceMapping({ catalogCardId, onChanged }: { catalogCardId: string; onChanged?: () => void }) {
  const api = useApi()
  const url = apiPath`/api/admin/catalog-cards/${catalogCardId}/poketrace`
  const { data, reload } = useLoad(() => api<PokeTraceMappingView>('GET', url), [api, url])
  const [candidates, setCandidates] = useState<PokeTraceCandidate[] | null>(null)
  const [reasons, setReasons] = useState<Record<string, string>>({})
  const [err, setErr] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [pulling, setPulling] = useState(false)
  const [pulled, setPulled] = useState<PokeTracePull | null>(null)

  if (data?.enabled !== true) return null

  async function run(action: () => Promise<unknown>) {
    setErr(null)
    setBusy(true)
    try {
      await action()
    } catch (e) {
      setErr(message(e))
    } finally {
      setBusy(false)
    }
  }

  const search = () => run(async () => setCandidates(await api<PokeTraceCandidate[]>('GET', `${url}/candidates`)))
  const map = (c: PokeTraceCandidate) =>
    run(async () => {
      await api('PUT', url, { poketraceId: c.poketraceId, overrideReason: reasons[c.poketraceId]?.trim() || null })
      setCandidates(null)
      reload()
      onChanged?.()
    })
  const unmap = () =>
    run(async () => {
      await api('DELETE', url)
      setPulled(null)
      reload()
      onChanged?.()
    })
  const pull = () =>
    run(async () => {
      setPulled(null)
      setPulling(true)
      try {
        setPulled(await api<PokeTracePull>('POST', `${url}/pull`))
        onChanged?.()
      } finally {
        setPulling(false)
      }
    })

  const card = data.poketraceCard
  return (
    <div>
      <h3>PokeTrace</h3>
      {data.poketraceId ? (
        <p>
          Mapped to <strong>{String(card?.name ?? data.poketraceId)}</strong> #{String(card?.cardNumber ?? '?')},{' '}
          {String(card?.variant ?? 'variant not given')}
          {data.overrideReason && <span className="muted small"> · mapped despite a mismatch: {data.overrideReason}</span>}{' '}
          <button onClick={unmap} disabled={busy}>
            Unmap
          </button>
        </p>
      ) : (
        <p className="muted small">Not mapped: no PokeTrace sales are pulled for this card.</p>
      )}
      <p className="muted small">
        PokeTrace gives one row per day, so each day with a sale is one comp at the day's average. Counts are sale days, a
        lower bound on the sales.
      </p>
      <p>
        <button onClick={search} disabled={busy}>
          {data.poketraceId ? 'Change PokeTrace card' : 'Find on PokeTrace'}
        </button>{' '}
        {data.poketraceId && (
          <button onClick={pull} disabled={busy}>
            Pull sales now
          </button>
        )}
      </p>
      {data.poketraceId && !pulled && !pulling && (
        <p className="muted small">Sales are pulled nightly. A first pull fetches a year of history and can take a minute.</p>
      )}
      {pulling && <p className="muted small">Pulling from PokeTrace…</p>}
      {pulled && (
        <p className="small" role="status">
          Pulled {pulled.fetched} sale days: {pulled.inserted} new, {pulled.duplicates} already stored
          {pulled.rejected > 0 && `, ${pulled.rejected} rejected`}.{' '}
          {pulled.repriced ? 'Prices refreshed.' : 'Another refresh was running: click Refresh prices in a minute.'}
          {pulled.errors.length > 0 && <span className="error"> PokeTrace failed: {pulled.errors.join('; ')}</span>}
        </p>
      )}
      {candidates && candidates.length === 0 && <p className="muted">PokeTrace has no card by this name.</p>}
      {candidates && candidates.length > 0 && (
        <table className="table">
          <thead>
            <tr>
              <th>PokeTrace card</th>
              <th>Doesn't match ours</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {candidates.map((c) => (
              <tr key={c.poketraceId}>
                <td>
                  {c.name} #{c.cardNumber} · {c.setName} · {c.variant}
                </td>
                <td className="small">
                  {c.disagreements.length === 0 ? (
                    <span className="muted">matches</span>
                  ) : (
                    <>
                      <ul className="warn">
                        {c.disagreements.map((d) => (
                          <li key={d}>{d}</li>
                        ))}
                      </ul>
                      <input
                        aria-label={`Reason to map ${c.poketraceId} anyway`}
                        placeholder="Reason to map it anyway"
                        maxLength={1000}
                        value={reasons[c.poketraceId] ?? ''}
                        onChange={(e) => setReasons({ ...reasons, [c.poketraceId]: e.target.value })}
                      />
                    </>
                  )}
                </td>
                <td>
                  <button
                    onClick={() => map(c)}
                    disabled={busy || (c.disagreements.length > 0 && !reasons[c.poketraceId]?.trim())}
                    aria-label={`Map to ${c.poketraceId}`}
                  >
                    Map
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {err && <p className="error" role="alert">{err}</p>}
    </div>
  )
}
