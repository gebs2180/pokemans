import { useState } from 'react'
import { Link } from 'react-router'
import { useApi, type PokeTraceApproval, type PokeTracePullGaps, type PokeTraceQueue, type PokeTraceQueueItem } from '../api'
import { message } from '../format'
import { useLoad } from '../useLoad'

const EDITION: Record<string, string> = { UNLIMITED: 'Unlimited', FIRST_EDITION: '1st Edition', SHADOWLESS: 'Shadowless' }
const VARIANT: Record<string, string> = { NORMAL: 'Normal', HOLO: 'Holo', REVERSE_HOLO: 'Reverse holo' }

function suggestion(item: PokeTraceQueueItem) {
  if (item.error) return <span className="error">Search failed: {item.error}</span>
  if (!item.searchedAt) return <span className="muted">not searched yet</span>
  if (item.suggestedId) {
    const c = item.candidates.find((x) => x.poketraceId === item.suggestedId)
    return (
      <>
        {c ? `${c.name} #${c.cardNumber} · ${c.setName} · ${c.variant}` : item.suggestedId} <span className="muted small">matches</span>
      </>
    )
  }
  const n = item.candidates.length
  return (
    <span className="warn">
      {n === 0 ? 'PokeTrace has no card by this name' : `no clean match (${n} candidate${n === 1 ? '' : 's'})`}: decide it on the card
      page
    </span>
  )
}

/**
 * Mapped cards whose PokeTrace pulls have stopped: no successful pull for the gap days since the last one (or
 * since the mapping). The nightly healthcheck fails while any are listed.
 */
function PullsStopped() {
  const api = useApi()
  const { data, error } = useLoad(() => api<PokeTracePullGaps>('GET', '/api/admin/poketrace/pull-gaps'), [api])
  if (error) return <p className="error" role="alert">{error}</p>
  if (!data?.enabled) return null
  const gaps = data.gaps ?? []
  return (
    <section className="panel">
      <h2>Pulls stopped</h2>
      {gaps.length === 0 ? (
        <p className="muted">Every mapped card has been pulled in the last {data.gapDays} days.</p>
      ) : (
        <>
          <p className="muted small">
            Mapped cards with no successful PokeTrace pull for {data.gapDays}+ days. Their prices age until a pull works again.
          </p>
          <div className="scroll">
            <table className="table">
              <thead>
                <tr>
                  <th>Card</th>
                  <th>Last pulled</th>
                  <th>Last error</th>
                </tr>
              </thead>
              <tbody>
                {gaps.map((g) => (
                  <tr key={g.catalogCardId}>
                    <td>
                      <Link to={`/admin/review/${g.orgId}/${g.cardItemId}`}>
                        {g.name}
                        {g.number ? ` #${g.number}` : ''}
                      </Link>
                      <div className="muted small">{[g.setName, g.poketraceId].filter(Boolean).join(' · ')}</div>
                    </td>
                    <td className="small">
                      {g.lastPulledAt ? new Date(g.lastPulledAt).toLocaleDateString() : 'never since mapped'} ({g.daysSince} day
                      {g.daysSince === 1 ? '' : 's'})
                    </td>
                    <td className="small">{g.lastError ? <span className="error">{g.lastError}</span> : <span className="muted">none</span>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </section>
  )
}

/**
 * Admin: identified cards still to map on PokeTrace. Each is searched when it's identified; a card with
 * exactly one candidate that agrees with ours on everything gets a suggestion to approve here. A suggestion
 * prices nothing until it's approved, and mismatches or several matches are decided on the card page.
 */
export function PokeTraceQueuePage() {
  const api = useApi()
  const { data, error, reload } = useLoad(() => api<PokeTraceQueue>('GET', '/api/admin/poketrace/queue'), [api])
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const [status, setStatus] = useState<string | null>(null)
  const [failures, setFailures] = useState<PokeTraceApproval['failures']>([])

  async function run(action: () => Promise<void>) {
    setErr(null)
    setStatus(null)
    setBusy(true)
    try {
      await action()
    } catch (e) {
      setErr(message(e))
    } finally {
      setBusy(false)
    }
  }

  const approve = () =>
    run(async () => {
      const result = await api<PokeTraceApproval>('POST', '/api/admin/poketrace/queue/approve', { catalogCardIds: [...selected] })
      setFailures(result.failures)
      setSelected(new Set())
      setStatus(
        `Mapped ${result.mapped.length} card${result.mapped.length === 1 ? '' : 's'}. Their sales are pulled at the nightly run, or now from each card page.`,
      )
      reload()
    })
  const search = () =>
    run(async () => {
      const result = await api<{ started: number; searching: boolean }>('POST', '/api/admin/poketrace/queue/search')
      setStatus(
        result.started > 0
          ? `Searching ${result.started} card${result.started === 1 ? '' : 's'} on PokeTrace: reload in a minute.`
          : result.searching
            ? 'A search is already running: reload in a minute.'
            : 'Every card here has been searched.',
      )
      reload()
    })

  const items = data?.items ?? []
  const clean = items.filter((i) => i.suggestedId)
  const toggle = (id: string) =>
    setSelected((s) => {
      const next = new Set(s)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })

  return (
    <>
      <p>
        <Link to="/admin">← Admin</Link>
      </p>
      <h1>Cards to map on PokeTrace</h1>
      <p className="muted">
        A card's PokeTrace sales price it only once an admin maps it. Cards are searched when they're identified; one with a single
        PokeTrace card that agrees on number, set, edition, variant and language can be approved here.
      </p>
      {error && <p className="error" role="alert">{error}</p>}
      {data && !data.enabled && <p className="muted">PokeTrace isn't enabled on this server.</p>}
      {data?.enabled && (
        <section className="panel">
          <p>
            <button onClick={approve} disabled={busy || selected.size === 0}>
              Approve selected ({selected.size})
            </button>{' '}
            <button onClick={() => setSelected(new Set(clean.map((i) => i.catalogCardId)))} disabled={busy || clean.length === 0}>
              Select all clean matches
            </button>{' '}
            <button onClick={search} disabled={busy || data.searching}>
              Find suggestions
            </button>
            {data.searching && <span className="muted small"> Searching…</span>}
          </p>
          {status && (
            <p className="small" role="status">
              {status}
            </p>
          )}
          {failures.length > 0 && (
            <ul className="error">
              {failures.map((f) => (
                <li key={f.catalogCardId}>
                  {items.find((i) => i.catalogCardId === f.catalogCardId)?.name ?? f.catalogCardId}: {f.error}
                </li>
              ))}
            </ul>
          )}
          {err && <p className="error" role="alert">{err}</p>}
          {items.length === 0 ? (
            <p className="muted">Every identified card is mapped.</p>
          ) : (
            <div className="scroll">
              <table className="table">
                <thead>
                  <tr>
                    <th />
                    <th>Our card</th>
                    <th>PokeTrace</th>
                  </tr>
                </thead>
                <tbody>
                  {items.map((i) => (
                    <tr key={i.catalogCardId}>
                      <td>
                        {i.suggestedId && (
                          <input
                            type="checkbox"
                            aria-label={`Approve ${i.name} #${i.number}`}
                            checked={selected.has(i.catalogCardId)}
                            onChange={() => toggle(i.catalogCardId)}
                          />
                        )}
                      </td>
                      <td>
                        <Link to={`/admin/review/${i.orgId}/${i.cardItemId}`}>
                          {i.name} #{i.number}
                        </Link>
                        <div className="muted small">
                          {[i.setName, i.edition && EDITION[i.edition], i.variant && VARIANT[i.variant], i.language]
                            .filter(Boolean)
                            .join(' · ')}
                        </div>
                      </td>
                      <td className="small">{suggestion(i)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      )}
      <PullsStopped />
    </>
  )
}
